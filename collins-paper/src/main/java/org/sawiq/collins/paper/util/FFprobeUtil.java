package org.sawiq.collins.paper.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
        CachedDuration cached = getCached(url);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached.durationMs());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                ProbeResult result = isYtDlpUrl(url) ? getDurationViaYtDlp(url) : getDurationViaFFprobe(url);
                if (url != null && !url.isBlank()) {
                    DURATION_CACHE.put(url, new CachedDuration(result.durationMs(), result.live(), System.currentTimeMillis()));
                }
                return result.durationMs();
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("FFprobe/yt-dlp error for " + shortenUrl(url) + ": " + e.getMessage());
                }
                // Cache the failure as "live-like" so the plugin's
                // requestDurationIfNeeded() short-circuits via isKnownLive()
                // and we don't spawn a fresh yt-dlp process every 10s for
                // URLs whose duration cannot be parsed (e.g. some VK
                // videos). Treating them as "live" is also the right
                // user-facing behavior: playback works fine, we just
                // don't know the length, so end-of-video detection is
                // disabled for that screen until cache TTL expires.
                if (url != null && !url.isBlank()) {
                    DURATION_CACHE.put(url, new CachedDuration(0L, true, System.currentTimeMillis()));
                }
                return 0L;
            }
        });
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

    private static ProbeResult getDurationViaYtDlp(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            getYtdlpPath(),
            "--dump-single-json",
            "--no-playlist",
            "--no-download",
            "--no-warnings",
            "--quiet",
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
