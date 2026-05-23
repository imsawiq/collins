package org.sawiq.collins.paper.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Получение длительности видео через FFprobe/yt-dlp (асинхронно)
 */
public class FFprobeUtil {
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;

    private static final Pattern DURATION_PATTERN = Pattern.compile("duration[\"=:]\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_DURATION_PATTERN = Pattern.compile("\"duration\"\\s*:\\s*([0-9.]+)");
    private static final Pattern IS_LIVE_PATTERN = Pattern.compile("\"is_live\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_STATUS_PATTERN = Pattern.compile("\"live_status\"\\s*:\\s*\"([^\"]+)\"");

    private record ProbeResult(long durationMs, boolean live) {
    }

    private record CachedDuration(long durationMs, boolean live, long cachedAtMs) {
        boolean isFresh() {
            return (System.currentTimeMillis() - cachedAtMs) <= CACHE_TTL_MS;
        }
    }

    private static final Map<String, CachedDuration> DURATION_CACHE = new ConcurrentHashMap<>();

    /**
     * Hard upper bound on the duration cache. The TTL-based eviction in
     * {@link #getCached} only fires when the same URL is asked for again
     * after expiry, so on a server where users keep queuing fresh links
     * (each {@code /collins seturl} adds an entry) the map would grow
     * without bound. Evicting eagerly when we cross this threshold keeps
     * the resident set predictable across long uptimes.
     *
     * <p>2048 cached durations at ~250 B per entry (URL string + the
     * record itself + ConcurrentHashMap overhead) is roughly 0.5 MB of
     * heap — well under the noise floor on any real server, even one
     * cycling through thousands of unique links a day.</p>
     */
    private static final int CACHE_MAX_ENTRIES = 2_048;

    /**
     * Negative cache for URLs whose duration cannot be probed (yt-dlp/
     * ffprobe both fail). Stored separately from {@link #DURATION_CACHE}
     * so we never falsely mark a finite-length video as a live stream
     * (doing so would permanently disable end-of-video detection on the
     * server and leave {@code screen.playing()=true} forever).
     *
     * <p>TTL is intentionally short: long enough to keep logs quiet
     * (no fresh yt-dlp process every 10s) but short enough that an
     * eventual yt-dlp upgrade or platform fix will be picked up
     * automatically without a server restart.
     */
    private static final long FAILURE_CACHE_TTL_MS = 10L * 60L * 1000L;

    /**
     * Long-lived negative cache TTL applied when the upstream platform
     * returns a hard, non-transient error: YouTube "Sign in to confirm
     * you're not a bot" (PO token gate), "Video unavailable", "Private
     * video", "Failed to extract any player response", etc. These don't
     * recover within minutes; retrying them on the regular 10 minute
     * loop just spams the server log. Six hours matches our positive
     * duration TTL so a single yt-dlp upgrade cycle clears them.
     */
    private static final long PERMANENT_FAILURE_TTL_MS = 6L * 60L * 60L * 1000L;

    /**
     * Substrings of yt-dlp / ffprobe error messages that signal a
     * persistent upstream failure. Match is case-insensitive on a
     * lower-cased message; keep entries lower-case here.
     */
    private static final String[] PERMANENT_FAILURE_MARKERS = {
            "sign in to confirm",
            "video unavailable",
            "private video",
            "this video is private",
            "members-only",
            "failed to extract any player response",
            "this live event will begin",
            "premiere will begin",
            "age-restricted",
            "removed by the uploader",
            "account associated with this video has been terminated",
    };

    private static final Map<String, Long> FAILURE_CACHE = new ConcurrentHashMap<>();
    // Stored value is the absolute expiry timestamp in epoch ms, NOT the
    // time the failure was recorded. Different errors live in the cache
    // for different lengths of time (regular network blip = 10 min,
    // permanent platform error = 6 h), so encoding the deadline directly
    // is simpler than carrying TTL separately.

    /**
     * Singleflight registry: when several callers ask for the same URL
     * inside a short window we want exactly one yt-dlp / ffprobe
     * subprocess to run, and every caller to wait on the same future.
     * Without this, three screens all set to the same YouTube link
     * would each fork their own yt-dlp on every periodic
     * {@code checkVideoEndings} tick, multiplying CPU/network use and
     * the rate at which we hit YouTube's bot-detection.
     */
    private static final Map<String, CompletableFuture<Long>> IN_FLIGHT = new ConcurrentHashMap<>();

    private static Logger logger;
    private static String configFfprobePath = "";
    private static String configYtdlpPath = "";
    private static int timeoutSeconds = 30;

    public static void init(Logger log, String ffprobe, String ytdlp, int timeout) {
        logger = log;
        configFfprobePath = ffprobe != null ? ffprobe : "";
        configYtdlpPath = ytdlp != null ? ytdlp : "";
        if (timeout > 0) timeoutSeconds = timeout;
    }

    private static String getFfprobePath() {
        if (!configFfprobePath.isEmpty() && !configFfprobePath.equals("auto") && !configFfprobePath.equals("ffprobe")) {
            return configFfprobePath;
        }
        return ToolsDownloader.getFfprobePath();
    }

    private static String getYtdlpPath() {
        if (!configYtdlpPath.isEmpty() && !configYtdlpPath.equals("auto") && !configYtdlpPath.equals("yt-dlp")) {
            return configYtdlpPath;
        }
        return ToolsDownloader.getYtdlpPath();
    }

    public static CompletableFuture<Long> getDurationMs(String url) {
        if (!isProbeSafeUrl(url)) {
            // Refuse to launch yt-dlp/ffprobe on URLs that aren't plain
            // http(s)/rtmp(s)/rtsp. file://, gopher://, /etc/passwd or
            // values that start with '-' (which would be parsed as a CLI
            // flag) must never reach the subprocess - they are an SSRF /
            // local-file-disclosure / arg-injection vector.
            return CompletableFuture.completedFuture(0L);
        }
        CachedDuration cached = getCached(url);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached.durationMs());
        }
        if (isFailureCached(url)) {
            return CompletableFuture.completedFuture(0L);
        }

        // Singleflight: one yt-dlp / ffprobe per URL at a time. Multiple
        // concurrent callers (e.g. checkVideoEndings firing on three
        // screens that all share the same YouTube link) share the same
        // future and the same subprocess.
        CompletableFuture<Long> existing = IN_FLIGHT.get(url);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Long> future = new CompletableFuture<>();
        CompletableFuture<Long> raced = IN_FLIGHT.putIfAbsent(url, future);
        if (raced != null) {
            return raced;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                ProbeResult result = isYtDlpUrl(url) ? probeYtDlpWithFallback(url) : getDurationViaFFprobe(url);
                if (url != null && !url.isBlank()) {
                    if (DURATION_CACHE.size() >= CACHE_MAX_ENTRIES) {
                        evictOldestEntries(DURATION_CACHE.size() - CACHE_MAX_ENTRIES + 1);
                    }
                    DURATION_CACHE.put(url, new CachedDuration(result.durationMs(), result.live(), System.currentTimeMillis()));
                    FAILURE_CACHE.remove(url);
                }
                return result.durationMs();
            } catch (Exception e) {
                String msg = e.getMessage();
                boolean permanent = isPermanentFailure(msg);
                if (logger != null) {
                    // Log permanent failures at INFO instead of WARNING
                    // so a single dead YouTube link does not pollute the
                    // server log every time the duration check runs. The
                    // long TTL means we only print this once every 6 h
                    // anyway, but use INFO so admins do not get paged.
                    String rendered = "FFprobe/yt-dlp error for " + shortenUrl(url) + ": " + msg;
                    if (permanent) {
                        logger.info(rendered + " (suppressing for 6 h)");
                    } else {
                        logger.warning(rendered);
                    }
                }
                // Negative cache (FAILURE_CACHE) - NOT the duration cache.
                // Stops log spam without flagging the URL as a live stream,
                // because doing the latter would make isVideoEnded() return
                // false forever and pin screen.playing()=true.
                if (url != null && !url.isBlank()) {
                    if (FAILURE_CACHE.size() >= CACHE_MAX_ENTRIES) {
                        evictOldestFailureEntries(FAILURE_CACHE.size() - CACHE_MAX_ENTRIES + 1);
                    }
                    long ttl = permanent ? PERMANENT_FAILURE_TTL_MS : FAILURE_CACHE_TTL_MS;
                    FAILURE_CACHE.put(url, System.currentTimeMillis() + ttl);
                }
                return 0L;
            }
        }).whenComplete((v, t) -> {
            try {
                if (t != null) {
                    future.completeExceptionally(t);
                } else {
                    future.complete(v != null ? v : 0L);
                }
            } finally {
                IN_FLIGHT.remove(url, future);
            }
        });

        return future;
    }

    /**
     * Recognises error messages that mean the URL will not become
     * probe-able again any time soon (YouTube bot gate, "Video
     * unavailable", private/age-restricted, premiere not started, etc.).
     * Used to extend the negative-cache TTL from 10 min to 6 h so the
     * server does not re-run yt-dlp on the same dead link every minute.
     */
    private static boolean isPermanentFailure(String message) {
        if (message == null || message.isEmpty()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String marker : PERMANENT_FAILURE_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Whitelist URL schemes for any URL that will be passed to a
     * subprocess (yt-dlp / ffprobe). Rejects:
     * <ul>
     *   <li>{@code null} / blank</li>
     *   <li>values starting with {@code -} (would be treated as a flag)</li>
     *   <li>schemes other than http/https/rtmp/rtmps/rtsp</li>
     * </ul>
     * In particular this blocks {@code file://}, {@code gopher://}, raw
     * filesystem paths, and other schemes that ffmpeg/ffprobe natively
     * understand and that could exfiltrate local files or do SSRF.
     */
    static boolean isProbeSafeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        if (url.charAt(0) == '-') return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
            || lower.startsWith("https://")
            || lower.startsWith("rtmp://")
            || lower.startsWith("rtmps://")
            || lower.startsWith("rtsp://");
    }

    private static boolean isFailureCached(String url) {
        if (url == null || url.isBlank()) return false;
        Long expiresAtMs = FAILURE_CACHE.get(url);
        if (expiresAtMs == null) return false;
        if (System.currentTimeMillis() >= expiresAtMs) {
            FAILURE_CACHE.remove(url);
            return false;
        }
        return true;
    }

    public static long getCachedDurationMs(String url) {
        CachedDuration cached = getCached(url);
        if (cached == null) {
            return 0L;
        }
        return cached.durationMs();
    }

    public static boolean isKnownLive(String url) {
        CachedDuration cached = getCached(url);
        return cached != null && cached.live();
    }

    private static CachedDuration getCached(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        CachedDuration cached = DURATION_CACHE.get(url);
        if (cached == null) {
            return null;
        }
        if (!cached.isFresh()) {
            DURATION_CACHE.remove(url);
            return null;
        }
        return cached;
    }

    /**
     * Drops every stale entry from both the duration and failure caches.
     * Lazy-eviction in {@link #getCached} only fires when the same URL
     * is asked for again, so on a server where every {@code /collins
     * seturl} adds a fresh URL the maps would otherwise keep growing
     * until heap exhaustion. Call this on a fixed schedule (e.g. once a
     * minute) from the plugin to keep the resident set bounded.
     */
    public static void pruneStaleEntries() {
        long now = System.currentTimeMillis();
        DURATION_CACHE.entrySet().removeIf(e -> (now - e.getValue().cachedAtMs()) > CACHE_TTL_MS);
        // FAILURE_CACHE values are absolute expiry timestamps now, so
        // drop anything whose deadline is in the past.
        FAILURE_CACHE.entrySet().removeIf(e -> now >= e.getValue());
    }

    /**
     * Drops every cached duration / failure record. Called by the host
     * plugin's memory watchdog when used heap crosses the high-water
     * mark; the trade-off is a brief spike in yt-dlp/ffprobe runs for
     * actively-playing screens (next call to
     * {@link #getDurationMs(String)} just re-probes), versus the JVM
     * dying under unbounded cache growth.
     *
     * <p>Does not cancel in-flight probes — those finish on their own
     * threads and write back into the (now empty) cache. That is fine:
     * the cache is allowed to refill from a clean slate.</p>
     */
    public static void emergencyClear() {
        DURATION_CACHE.clear();
        FAILURE_CACHE.clear();
    }

    /**
     * Approximate heap footprint of the duration / failure caches in
     * bytes. Used by the host plugin's memory watchdog to decide whether
     * dropping our caches would actually move the needle: if global
     * heap is high but the bulk of it is owned by some other plugin,
     * clearing our 50 KB of cached durations would just suppress an
     * unrelated symptom while costing us a yt-dlp re-probe storm.
     *
     * <p>Estimation uses a per-entry cost (URL length + a fixed
     * record/overhead constant). Order-of-magnitude correct; we are
     * not trying to match a real heap profiler.</p>
     */
    public static long estimatedFootprintBytes() {
        // ~80 B for ConcurrentHashMap.Node + Long key, ~64 B for the
        // CachedDuration record, plus 2 chars per URL char. Round up
        // to 200 B fixed + 2 * URL length to be conservative.
        long total = 0L;
        for (var e : DURATION_CACHE.entrySet()) {
            String k = e.getKey();
            total += 200L + (k != null ? 2L * k.length() : 0L);
        }
        for (var e : FAILURE_CACHE.entrySet()) {
            String k = e.getKey();
            total += 120L + (k != null ? 2L * k.length() : 0L);
        }
        return total;
    }

    /**
     * Hard-cap eviction. Called when the cache hits {@link #CACHE_MAX_ENTRIES}.
     * Picks the oldest {@code n} entries by {@code cachedAtMs} and drops
     * them. We sort instead of using {@code LinkedHashMap} access-order
     * because {@link ConcurrentHashMap} does not preserve insertion order
     * and a synchronized LinkedHashMap would serialize every probe call.
     */
    private static void evictOldestEntries(int n) {
        if (n <= 0) return;
        DURATION_CACHE.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue(
                        java.util.Comparator.comparingLong(CachedDuration::cachedAtMs)))
                .limit(n)
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(DURATION_CACHE::remove);
    }

    private static void evictOldestFailureEntries(int n) {
        if (n <= 0) return;
        FAILURE_CACHE.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue())
                .limit(n)
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(FAILURE_CACHE::remove);
    }

    private static boolean isYtDlpUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("youtube.com")
            || lower.contains("youtu.be")
            || lower.contains("ytimg.com")
            || lower.contains("twitch.tv")
            || lower.contains("rutube.ru")
            || lower.contains("vkvideo.ru")
            || lower.contains("vk.com/video")
            || lower.contains("vk.com/clip")
            || lower.contains("vk.com/video_ext.php");
    }

    /**
     * Probe duration via yt-dlp with a fallback path. The primary path
     * uses {@code --dump-single-json} which produces a JSON object that
     * we can parse for {@code duration}/{@code is_live}. Some platforms
     * (notably VK for certain video IDs) fail JSON metadata extraction
     * even though the underlying media stream is fine; for those we
     * fall back to {@code yt-dlp -g} (resolve direct stream URL) and
     * then ffprobe the resolved stream for its container duration.
     */
    private static ProbeResult probeYtDlpWithFallback(String url) throws Exception {
        Exception primary;
        try {
            return getDurationViaYtDlp(url);
        } catch (Exception e) {
            primary = e;
        }

        try {
            String resolved = resolveStreamUrlViaYtDlp(url);
            if (resolved == null || resolved.isBlank()) {
                throw new Exception("yt-dlp -g returned empty");
            }
            return getDurationViaFFprobe(resolved);
        } catch (Exception fallback) {
            throw new Exception(primary.getMessage() + "; fallback failed: " + fallback.getMessage());
        }
    }

    private static String resolveStreamUrlViaYtDlp(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            getYtdlpPath(),
            "-g",
            "--no-playlist",
            "--no-warnings",
            "--quiet",
            "--",  // end-of-options sentinel: defends against URLs that
                   // happen to start with '-' even after isProbeSafeUrl,
                   // e.g. a future scheme we add to the whitelist that
                   // contains a leading dash in its hostname.
            url
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        String firstUrl = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // yt-dlp -g may print video and audio URLs on separate
                // lines; we want the first http(s) URL only. stderr is
                // merged via redirectErrorStream so we filter non-URL
                // lines (errors/warnings) out.
                String trimmed = line.trim();
                if (firstUrl == null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                    firstUrl = trimmed;
                }
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("yt-dlp -g timeout");
        }
        if (firstUrl == null) {
            throw new Exception("yt-dlp -g returned no stream URL");
        }
        return firstUrl;
    }

    private static ProbeResult getDurationViaYtDlp(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            getYtdlpPath(),
            "--dump-single-json",
            "--no-playlist",
            "--no-download",
            "--no-warnings",
            "--quiet",
            "--",
            url
        );
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("yt-dlp timeout");
        }
        
        String result = output.toString().trim();
        if (result.isEmpty()) {
            throw new Exception("yt-dlp returned empty duration");
        }

        if (isLiveJson(result)) {
            return new ProbeResult(0L, true);
        }

        long ms = parseYtDlpDurationMs(result);
        if (ms <= 0) {
            throw new Exception("yt-dlp returned no parsable duration: " + shortenUrl(result));
        }

        return new ProbeResult(ms, false);
    }

    private static ProbeResult getDurationViaFFprobe(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            getFfprobePath(),
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            url
        );
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("ffprobe timeout");
        }
        
        String result = output.toString().trim();
        if (result.isEmpty() || result.equals("N/A")) {
            throw new Exception("ffprobe returned no duration");
        }
        
        double seconds = Double.parseDouble(result);
        long ms = (long) (seconds * 1000);

        return new ProbeResult(ms, false);
    }

    private static String shortenUrl(String url) {
        if (url == null) return "null";
        if (url.length() <= 50) return url;
        return url.substring(0, 47) + "...";
    }

    private static long parseYtDlpDurationMs(String raw) {
        Matcher jsonMatcher = YT_DURATION_PATTERN.matcher(raw);
        if (jsonMatcher.find()) {
            return parseSecondsToMs(jsonMatcher.group(1));
        }

        Matcher directMatcher = DURATION_PATTERN.matcher(raw);
        if (directMatcher.find()) {
            return parseSecondsToMs(directMatcher.group(1));
        }

        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                return parseSecondsToMs(trimmed);
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private static long parseSecondsToMs(String rawSeconds) {
        double seconds = Double.parseDouble(rawSeconds);
        return (long) (seconds * 1000.0);
    }

    private static boolean isLiveJson(String raw) {
        Matcher liveMatcher = IS_LIVE_PATTERN.matcher(raw);
        if (liveMatcher.find() && Boolean.parseBoolean(liveMatcher.group(1))) {
            return true;
        }

        Matcher statusMatcher = LIVE_STATUS_PATTERN.matcher(raw);
        if (!statusMatcher.find()) {
            return false;
        }

        String status = statusMatcher.group(1);
        return "is_live".equalsIgnoreCase(status) || "post_live".equalsIgnoreCase(status);
    }
}
