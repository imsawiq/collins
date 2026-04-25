package org.sawiq.collins.fabric.client.video;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves YouTube URLs to local cached playable files using yt-dlp + ffmpeg.
 */
public final class YouTubeResolver {
    private static final String META_PREFIX = "CollinsMeta:";
    private static final Pattern DOWNLOAD_PROGRESS_PATTERN = Pattern.compile(".*?(\\d{1,3}(?:\\.\\d+)?)%.*?of\\s+~?\\s*(\\d+(?:\\.\\d+)?)(?:\\s*)(B|KiB|MiB|GiB|TiB).*");
    private static final Pattern DOWNLOAD_PERCENT_PATTERN = Pattern.compile(".*?(\\d{1,3}(?:\\.\\d+)?)%.*");
    private static final Pattern DOWNLOAD_SIZE_PATTERN = Pattern.compile(".*?(\\d+(?:\\.\\d+)?)(?:\\s*)(B|KiB|MiB|GiB|TiB).*");
    private static final Pattern DURATION_JSON_PATTERN = Pattern.compile("\"duration\"\\s*:\\s*([0-9.]+)");
    private static final Pattern IS_LIVE_PATTERN = Pattern.compile("\"is_live\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_STATUS_PATTERN = Pattern.compile("\"live_status\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private static final boolean DEBUG = true;

    private static void dbg(String msg) {
        if (!DEBUG) return;
        try {
            System.out.println("[CollinsYT] " + msg);
        } catch (Exception ignored) {
        }
    }

    private static final Pattern YOUTUBE_PATTERN = Pattern.compile(
        "(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|youtube\\.com/v/|youtube\\.com/shorts/|youtube\\.com/live/)([a-zA-Z0-9_-]{11})"
    );
    private static final Pattern TWITCH_CHANNEL_PATTERN = Pattern.compile(
        "(?:https?://)?(?:www\\.)?twitch\\.tv/(?!directory|downloads|jobs|p/|settings|inventory|wallet)([a-zA-Z0-9_]+)(?:[/?#].*)?"
    );

    /**
     * Resolved URL cache. Bounded LRU (max {@value #URL_CACHE_MAX} entries)
     * to prevent unbounded growth across long sessions; entries also expire
     * after {@link #URL_CACHE_TTL_MS}. Each cache key is
     * {@code videoId|quality} so the cap is plenty for any realistic
     * single-user playback history.
     */
    private static final int URL_CACHE_MAX = 64;
    @SuppressWarnings("serial")
    private static final java.util.Map<String, ResolvedUrl> URL_CACHE =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, ResolvedUrl>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, ResolvedUrl> eldest) {
                    return size() > URL_CACHE_MAX;
                }
            });
    private static final long URL_CACHE_TTL_MS = 5L * 60L * 60L * 1000L;
    private static final long YTDLP_REFRESH_COOLDOWN_MS = 10L * 60L * 1000L;
    private static final String YTDLP_DOWNLOAD_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final String FFMPEG_DOWNLOAD_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
    private static final Object TOOL_LOCK = new Object();

    private static volatile boolean ytdlpAvailable = false;
    private static volatile boolean ytdlpChecked = false;
    private static volatile boolean ytdlpDownloading = false;
    private static volatile int ytdlpDownloadProgress = 0;
    private static volatile boolean ffmpegAvailable = false;
    private static volatile boolean ffmpegChecked = false;
    private static volatile long lastYtdlpRefreshAttemptMs = 0L;

    private record ResolvedUrl(String directUrl, String videoId, long resolvedAtMs, long durationMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - resolvedAtMs > URL_CACHE_TTL_MS;
        }
    }

    public record YouTubeResult(
        String directUrl,
        String videoId,
        long durationMs,
        String error,
        boolean needsDownload,
        boolean live
    ) {
        public boolean isSuccess() {
            return directUrl != null && !directUrl.isBlank();
        }
    }

    private record StreamMeta(String sourceId, long durationMs, boolean live) {
    }

    public static boolean isYouTubeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("youtube.com") || u.contains("youtu.be");
    }

    public static boolean isTwitchUrl(String url) {
        if (url == null || url.isBlank()) return false;
        return url.toLowerCase(Locale.ROOT).contains("twitch.tv");
    }

    public static boolean isSupportedPlatformUrl(String url) {
        return isYouTubeUrl(url) || isTwitchUrl(url);
    }

    public static String extractVideoId(String url) {
        if (url == null) return null;
        Matcher m = YOUTUBE_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static String extractTwitchChannel(String url) {
        if (url == null) return null;
        Matcher m = TWITCH_CHANNEL_PATTERN.matcher(url);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    public static YouTubeResult resolve(String url) {
        return resolve(url, YouTubeQuality.DEFAULT);
    }

    public static YouTubeResult resolve(String url, int preferredHeight) {
        return resolve(url, preferredHeight, null);
    }

    public static YouTubeResult resolve(String url, int preferredHeight, VideoPlayer.FrameSink sink) {
        if (url == null || url.isBlank()) {
            return new YouTubeResult(null, null, 0, "Empty URL", false, false);
        }

        boolean youtube = isYouTubeUrl(url);
        boolean twitch = isTwitchUrl(url);
        if (!youtube && !twitch) {
            return new YouTubeResult(null, null, 0, "Not a supported platform URL", false, false);
        }

        int targetHeight = normalizeTargetHeight(preferredHeight);

        String sourceId = youtube ? extractVideoId(url) : extractTwitchChannel(url);
        if (sourceId == null || sourceId.isBlank()) {
            sourceId = Integer.toHexString(url.hashCode());
        }

        // === Twitch fast path: direct GraphQL + HLS, no yt-dlp required. ===
        // Resolves in ~1-2s vs ~10-20s for the previous yt-dlp invocation, and
        // pins a single quality variant (no ABR transitions = no green stripes).
        // Whichever path produces the playlist URL, we wrap it through
        // TwitchHlsProxy so stitched ads (#EXT-X-DATERANGE CLASS="twitch-stitched-ad")
        // are filtered out before FFmpeg sees them.
        if (twitch) {
            String channel = extractTwitchChannel(url);
            if (channel != null && !channel.isBlank()) {
                String hlsUrl = TwitchStreamClient.resolveHlsUrl(channel, targetHeight);
                if (hlsUrl != null) {
                    dbg("resolve: twitch direct HLS for " + channel);
                    return new YouTubeResult(TwitchHlsProxy.wrap(hlsUrl), channel, 0L, null, false, true);
                }
                dbg("resolve: twitch direct HLS unavailable, falling back to yt-dlp");
            }

            if (!ensureYtdlpAvailable()) {
                if (ytdlpDownloading) {
                    return new YouTubeResult(null, sourceId, 0, "yt-dlp downloading: " + ytdlpDownloadProgress + "%", true, false);
                }
                return new YouTubeResult(null, sourceId, 0, "twitch resolver unavailable", true, false);
            }
            try {
                String directUrl = resolveDirectStreamUrl(url, targetHeight, false);
                return new YouTubeResult(TwitchHlsProxy.wrap(directUrl), sourceId, 0L, null, false, true);
            } catch (Exception e) {
                dbg("resolve: twitch yt-dlp fallback error " + e.getMessage());
                return new YouTubeResult(null, sourceId, 0, "Resolution failed: " + e.getMessage(), false, false);
            }
        }

        // === YouTube cache fast path (replays, no network). ===
        if (sourceId != null && !sourceId.isBlank() && !sourceId.equals(Integer.toHexString(url.hashCode()))) {
            String cacheKey = cacheKey(sourceId, targetHeight);
            ResolvedUrl cached = URL_CACHE.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                Path cachedPath = Path.of(cached.directUrl());
                if (Files.isRegularFile(cachedPath)) {
                    notifyCachedFileUsed(cachedPath, sink);
                    dbg("resolve: fast path memory-cached local file for " + sourceId);
                    return new YouTubeResult(cached.directUrl, sourceId, cached.durationMs, null, false, false);
                }
            }
            Path cachedFile = findCachedYoutubeFile(sourceId, targetHeight);
            if (cachedFile != null) {
                long durationMs = readCachedDuration(sourceId);
                URL_CACHE.put(cacheKey, new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                notifyCachedFileUsed(cachedFile, sink);
                dbg("resolve: fast path disk-cached file for " + sourceId + " quality=" + targetHeight);
                return new YouTubeResult(cachedFile.toString(), sourceId, durationMs, null, false, false);
            }
        }

        // === YouTube live fast path: direct hlsManifestUrl, no yt-dlp required. ===
        // If the video is not live, this returns null and we fall through to the
        // yt-dlp VOD download flow below. Passing targetHeight pins FFmpeg to
        // a specific variant so we get consistent quality without ABR jumps.
        String videoId = extractVideoId(url);
        if (videoId != null && !videoId.isBlank()) {
            String liveHls = YouTubeLiveClient.resolveLiveHlsUrl(videoId, targetHeight);
            if (liveHls != null) {
                dbg("resolve: youtube direct live HLS for " + videoId);
                return new YouTubeResult(liveHls, videoId, 0L, null, false, true);
            }
        }

        // === YouTube VOD path: keep the existing yt-dlp + ffmpeg download flow. ===
        if (!ensureYtdlpAvailable()) {
            if (ytdlpDownloading) {
                return new YouTubeResult(null, sourceId, 0, "yt-dlp downloading: " + ytdlpDownloadProgress + "%", true, false);
            }
            return new YouTubeResult(null, sourceId, 0, "yt-dlp not available", true, false);
        }

        try {
            StreamMeta meta = resolveStreamMeta(url, youtube);
            if ((meta.sourceId() != null) && !meta.sourceId().isBlank()) {
                sourceId = meta.sourceId();
            }

            if (meta.live()) {
                String directUrl = resolveDirectStreamUrl(url, targetHeight, youtube);
                return new YouTubeResult(directUrl, sourceId, 0L, null, false, true);
            }

            if (youtube) {
                // Re-check cache with the authoritative video id from metadata.
                String cacheKey = cacheKey(sourceId, targetHeight);
                ResolvedUrl cached = URL_CACHE.get(cacheKey);
                if (cached != null && !cached.isExpired()) {
                    Path cachedPath = Path.of(cached.directUrl());
                    if (Files.isRegularFile(cachedPath)) {
                        notifyCachedFileUsed(cachedPath, sink);
                        dbg("resolve: using memory-cached local file for " + sourceId);
                        return new YouTubeResult(cached.directUrl, sourceId, cached.durationMs, null, false, false);
                    }
                }

                Path cachedFile = findCachedYoutubeFile(sourceId, targetHeight);
                if (cachedFile != null) {
                    long durationMs = readCachedDuration(sourceId);
                    URL_CACHE.put(cacheKey, new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                    notifyCachedFileUsed(cachedFile, sink);
                    dbg("resolve: using disk-cached local file for " + sourceId + " quality=" + targetHeight);
                    return new YouTubeResult(cachedFile.toString(), sourceId, durationMs, null, false, false);
                }

                if (!ensureFfmpegAvailable()) {
                    return new YouTubeResult(null, sourceId, 0, "ffmpeg not available", true, false);
                }
                return downloadYoutubeToCache(sourceId, targetHeight, sink);
            }

            String directUrl = resolveDirectStreamUrl(url, targetHeight, false);
            return new YouTubeResult(directUrl, sourceId, meta.durationMs(), null, false, false);
        } catch (Exception e) {
            dbg("resolve: metadata error " + e.getMessage());

            // Minimal fallback chain: for YouTube we can still try a direct download by video id.
            // Skip expensive yt-dlp binary refresh (it re-downloads ~20MB every failure).
            if (youtube) {
                Path cachedFile = findCachedYoutubeFile(sourceId, targetHeight);
                if (cachedFile != null) {
                    long durationMs = readCachedDuration(sourceId);
                    URL_CACHE.put(cacheKey(sourceId, targetHeight), new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                    notifyCachedFileUsed(cachedFile, sink);
                    dbg("resolve: metadata fallback to cached YouTube file for " + sourceId);
                    return new YouTubeResult(cachedFile.toString(), sourceId, durationMs, null, false, false);
                }

                String extractedVideoId = extractVideoId(url);
                if (extractedVideoId != null && !extractedVideoId.isBlank()) {
                    sourceId = extractedVideoId;
                    if (!ensureFfmpegAvailable()) {
                        return new YouTubeResult(null, sourceId, 0, "ffmpeg not available", true, false);
                    }
                    try {
                        dbg("resolve: metadata fallback to direct YouTube download for " + sourceId);
                        return downloadYoutubeToCache(sourceId, targetHeight, sink);
                    } catch (Exception downloadError) {
                        dbg("resolve: fallback download failed " + downloadError.getMessage());
                        e = downloadError;
                    }
                }

                // Last-resort: try a direct live URL grab. Helps when the stream is live
                // and metadata fetch failed only due to a transient extractor issue.
                try {
                    String directUrl = resolveDirectStreamUrl(url, targetHeight, true);
                    dbg("resolve: metadata fallback to direct YouTube live url");
                    return new YouTubeResult(directUrl, sourceId, 0L, null, false, true);
                } catch (Exception directError) {
                    dbg("resolve: fallback direct YouTube url failed " + directError.getMessage());
                    e = directError;
                }
            } else {
                // Twitch / other live platforms: try a fresh direct URL grab as the only
                // useful retry; do NOT spin up the ~20MB yt-dlp re-download.
                try {
                    String directUrl = resolveDirectStreamUrl(url, targetHeight, false);
                    dbg("resolve: metadata fallback to direct Twitch/live url");
                    return new YouTubeResult(directUrl, sourceId, 0L, null, false, true);
                } catch (Exception directError) {
                    dbg("resolve: fallback direct Twitch url failed " + directError.getMessage());
                    e = directError;
                }
            }

            return new YouTubeResult(null, sourceId, 0, "Resolution failed: " + e.getMessage(), false, false);
        }
    }

    public static boolean isYtdlpAvailable() {
        if (ytdlpChecked) return ytdlpAvailable;

        Path ytdlp = getYtdlpPath();
        ytdlpAvailable = Files.isRegularFile(ytdlp) && Files.isExecutable(ytdlp);
        ytdlpChecked = true;
        return ytdlpAvailable;
    }

    public static boolean isDownloading() {
        return ytdlpDownloading;
    }

    public static int getDownloadProgress() {
        return ytdlpDownloadProgress;
    }

    public static void downloadYtdlpAsync() {
        if (ytdlpDownloading || ytdlpAvailable) return;

        Thread t = new Thread(YouTubeResolver::ensureYtdlpAvailable, "Collins-YtdlpDownload");
        t.setDaemon(true);
        t.start();
    }

    /** Downloads ffmpeg in the background if not already present. Safe to call multiple times. */
    public static void downloadFfmpegAsync() {
        if (ffmpegAvailable) return;

        Thread t = new Thread(YouTubeResolver::ensureFfmpegAvailable, "Collins-FfmpegDownload");
        t.setDaemon(true);
        t.start();
    }

    public static boolean hasCachedVideo(String url) {
        return getCachedVideoPath(url, YouTubeQuality.DEFAULT) != null;
    }

    public static Path getCachedVideoPath(String url) {
        return getCachedVideoPath(url, YouTubeQuality.DEFAULT);
    }

    public static boolean hasCachedVideo(String url, int preferredHeight) {
        return getCachedVideoPath(url, preferredHeight) != null;
    }

    public static Path getCachedVideoPath(String url, int preferredHeight) {
        String videoId = extractVideoId(url);
        if (videoId == null) return null;

        int targetHeight = normalizeTargetHeight(preferredHeight);
        String cacheKey = cacheKey(videoId, targetHeight);

        ResolvedUrl cached = URL_CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            try {
                Path cachedPath = Path.of(cached.directUrl());
                if (Files.isRegularFile(cachedPath)) {
                    return cachedPath;
                }
            } catch (Exception ignored) {
            }
        }

        return findCachedYoutubeFile(videoId, targetHeight);
    }

    private static boolean ensureYtdlpAvailable() {
        if (ytdlpAvailable) return true;

        synchronized (TOOL_LOCK) {
            Path ytdlp = getYtdlpPath();
            if (Files.isRegularFile(ytdlp)) {
                ytdlpAvailable = true;
                ytdlpChecked = true;
                return true;
            }

            dbg("ensureYtdlpAvailable: downloading yt-dlp...");
            ytdlpDownloading = true;
            ytdlpDownloadProgress = 0;

            try {
                Files.createDirectories(getToolsDir());
                downloadFile(YTDLP_DOWNLOAD_URL, ytdlp, true);
                ytdlpAvailable = Files.isRegularFile(ytdlp);
                ytdlpChecked = true;
                return ytdlpAvailable;
            } catch (Exception e) {
                dbg("ensureYtdlpAvailable: download error " + e.getMessage());
                return false;
            } finally {
                ytdlpDownloading = false;
            }
        }
    }

    private static boolean tryRefreshYtdlpBinary() {
        long now = System.currentTimeMillis();
        if ((now - lastYtdlpRefreshAttemptMs) < YTDLP_REFRESH_COOLDOWN_MS) {
            return false;
        }

        synchronized (TOOL_LOCK) {
            now = System.currentTimeMillis();
            if ((now - lastYtdlpRefreshAttemptMs) < YTDLP_REFRESH_COOLDOWN_MS) {
                return false;
            }
            lastYtdlpRefreshAttemptMs = now;

            try {
                dbg("tryRefreshYtdlpBinary: refreshing yt-dlp after resolution failure");
                Files.createDirectories(getToolsDir());
                downloadFile(YTDLP_DOWNLOAD_URL, getYtdlpPath(), false);
                ytdlpAvailable = Files.isRegularFile(getYtdlpPath());
                ytdlpChecked = ytdlpAvailable;
                dbg("tryRefreshYtdlpBinary: refreshed=" + ytdlpAvailable);
                return ytdlpAvailable;
            } catch (Exception e) {
                dbg("tryRefreshYtdlpBinary: refresh failed " + e.getMessage());
                return false;
            }
        }
    }

    private static boolean ensureFfmpegAvailable() {
        if (ffmpegChecked && ffmpegAvailable) return true;

        synchronized (TOOL_LOCK) {
            Path ffmpeg = getFfmpegPath();
            if (Files.isRegularFile(ffmpeg)) {
                ffmpegAvailable = true;
                ffmpegChecked = true;
                return true;
            }

            try {
                Files.createDirectories(getToolsDir());
                Path zipTmp = getToolsDir().resolve("ffmpeg.zip.tmp");
                downloadFile(FFMPEG_DOWNLOAD_URL, zipTmp, false);

                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipTmp))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (!entry.isDirectory() && name.endsWith("ffmpeg.exe")) {
                            Files.copy(zis, ffmpeg, StandardCopyOption.REPLACE_EXISTING);
                            ffmpegAvailable = true;
                        }
                        zis.closeEntry();
                    }
                }

                Files.deleteIfExists(zipTmp);
                ffmpegChecked = true;
                return ffmpegAvailable;
            } catch (Exception e) {
                dbg("ensureFfmpegAvailable: error " + e.getMessage());
                return false;
            }
        }
    }

    private static boolean downloadFile(String urlStr, Path target, boolean trackProgress) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Collins-Fabric");

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return false;
        }

        long contentLength = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream(); var out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long written = 0;
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
                written += r;
                if (trackProgress && contentLength > 0) {
                    ytdlpDownloadProgress = (int) (written * 100 / contentLength);
                }
            }
        }
        conn.disconnect();
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static YouTubeResult downloadYoutubeToCache(String videoId, int preferredHeight, VideoPlayer.FrameSink sink) throws Exception {
        int targetHeight = normalizeTargetHeight(preferredHeight);
        Path cacheDir = getYoutubeCacheDir();
        Files.createDirectories(cacheDir);
        String cachePrefix = buildCachePrefix(videoId, targetHeight);
        deleteYoutubeTempArtifacts(cachePrefix);

        Path outputBase = cacheDir.resolve(cachePrefix + ".%(ext)s");

        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        String selector = buildDownloadFormatSelector(targetHeight);
        // Estimate size from the --print before_dl callback emitted by yt-dlp during the actual download.
        // Skipping the extra --simulate run saves ~5-15s on every YouTube playback.
        long estimatedTotalBytes = 0L;

        List<String> command = new ArrayList<>();
        command.add(getYtdlpPath().toString());
        command.add("-f");
        command.add(selector);
        command.add("--merge-output-format");
        command.add("mkv");
        command.add("--force-overwrites");
        command.add("--concurrent-fragments");
        command.add("4");
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--no-check-certificates");
        command.add("--no-cache-dir");
        command.add("--retries");
        command.add("3");
        command.add("--fragment-retries");
        command.add("5");
        command.add("--socket-timeout");
        command.add("15");
        command.add("--extractor-retries");
        command.add("2");
        command.add("--newline");
        command.add("--progress-template");
        command.add("download:CollinsYTProgress:%(progress.percent)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s");
        command.add("--progress-template");
        command.add("download:fragment:CollinsYTProgress:%(progress.percent)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s");
        command.add("--ffmpeg-location");
        command.add(getToolsDir().toString());
        command.add("--output");
        command.add(outputBase.toString());
        command.add("--print");
        command.add("before_dl:CollinsYTMeta:%(filesize)s:%(filesize_approx)s");
        command.add("--print");
        command.add("after_move:%(filepath)s");
        command.add("--print");
        command.add("%(duration)s");
        addOptionalYouTubeAuthArgs(command);
        command.add(videoUrl);

        dbg("downloadYoutubeToCache: selector=" + selector);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (sink != null) {
            sink.onDownloadStart("collins.video.youtube_downloading");
        }

        Process p = pb.start();
        StringBuilder output = new StringBuilder();
        String finalPath = null;
        long durationMs = 0L;
        AtomicLong totalEstimateBytes = new AtomicLong(Math.max(0L, estimatedTotalBytes));
        AtomicBoolean monitorRunning = new AtomicBoolean(sink != null);
        Thread progressMonitor = null;
        if (sink != null) {
            progressMonitor = new Thread(() -> monitorYoutubeDownloadProgress(cachePrefix, totalEstimateBytes, monitorRunning, sink),
                "Collins-YTProgress-" + cachePrefix);
            progressMonitor.setDaemon(true);
            progressMonitor.start();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                String trimmed = line.trim();
                if (trimmed.startsWith("before_dl:CollinsYTMeta:")) {
                    updatePrintedDownloadSize(trimmed, totalEstimateBytes);
                }
                if (sink != null) {
                    updateDownloadProgressFromYtdlp(trimmed, sink);
                }
                if (trimmed.startsWith("after_move:")) {
                    finalPath = trimmed.substring("after_move:".length()).trim();
                } else if (durationMs == 0L) {
                    durationMs = parseDuration(trimmed);
                }
            }
        }

        boolean finished = p.waitFor(30, TimeUnit.MINUTES);
        monitorRunning.set(false);
        if (progressMonitor != null) {
            progressMonitor.interrupt();
            try {
                progressMonitor.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!finished) {
            p.destroyForcibly();
            return new YouTubeResult(null, videoId, 0, "yt-dlp timeout while downloading video", false, false);
        }

        if (p.exitValue() != 0) {
            String err = output.toString().trim();
            if (err.length() > 300) err = err.substring(0, 300) + "...";
            return new YouTubeResult(null, videoId, 0, "yt-dlp download error: " + err, false, false);
        }

        Path finalFile = null;
        if (finalPath != null && !finalPath.isBlank()) {
            Path candidate = Path.of(finalPath);
            if (Files.isRegularFile(candidate)) {
                finalFile = candidate;
            }
        }
        if (finalFile == null) {
            finalFile = findCachedYoutubeFile(videoId, targetHeight);
        }
        if (finalFile == null) {
            return new YouTubeResult(null, videoId, 0, "Downloaded file not found", false, false);
        }

        writeCachedDuration(videoId, durationMs);
        URL_CACHE.put(cacheKey(videoId, targetHeight), new ResolvedUrl(finalFile.toString(), videoId, System.currentTimeMillis(), durationMs));
        notifyCachedFileUsed(finalFile, sink);
        dbg("downloadYoutubeToCache: cached file=" + finalFile);
        return new YouTubeResult(finalFile.toString(), videoId, durationMs, null, false, false);
    }

    private static void notifyCachedFileUsed(Path file, VideoPlayer.FrameSink sink) {
        if (sink == null || file == null) return;
        try {
            sink.onCachedFileUsed(file.toString(), Files.size(file));
        } catch (Exception ignored) {
        }
    }

    private static void updateDownloadProgressFromYtdlp(String line, VideoPlayer.FrameSink sink) {
        if (line == null || line.isBlank()) return;

        try {
            String clean = line.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
            String payload = null;
            int progressIdx = clean.indexOf("CollinsYTProgress:");
            if (progressIdx >= 0) {
                payload = clean.substring(progressIdx + "CollinsYTProgress:".length());
            }
            if (payload != null) {
                String[] parts = payload.split(":", -1);
                double percentRaw = parts.length > 0 ? parseDoubleSafe(parts[0]) : 0.0;
                long downloadedBytes = parts.length > 1 ? parseLongSafe(parts[1]) : 0L;
                long totalBytes = parts.length > 2 ? parseLongSafe(parts[2]) : 0L;
                long totalEstimateBytes = parts.length > 3 ? parseLongSafe(parts[3]) : 0L;

                long totalBytesFinal = totalBytes > 0 ? totalBytes : totalEstimateBytes;
                long downloadedMb = bytesToMb(downloadedBytes);
                long totalMb = bytesToMb(totalBytesFinal);
                int percent = percentRaw > 0.0 ? (int) Math.round(percentRaw)
                    : (totalBytesFinal > 0 ? (int) Math.round((downloadedBytes * 100.0) / totalBytesFinal) : 0);
                if (percent > 0 || downloadedMb > 0 || totalMb > 0) {
                    sink.onDownloadProgress(percent, downloadedMb, totalMb);
                    return;
                }
            }

            Matcher percentMatcher = DOWNLOAD_PERCENT_PATTERN.matcher(clean);
            if (!percentMatcher.matches()) return;

            int percent = (int) Math.round(Double.parseDouble(percentMatcher.group(1)));
            long downloadedMb = 0L;
            long totalMb = 0L;

            Matcher progressMatcher = DOWNLOAD_PROGRESS_PATTERN.matcher(clean);
            if (progressMatcher.matches()) {
                double totalValue = Double.parseDouble(progressMatcher.group(2));
                String unit = progressMatcher.group(3);
                totalMb = toMegabytes(totalValue, unit);
                downloadedMb = totalMb > 0 ? Math.max(0L, Math.round(totalMb * (percent / 100.0))) : 0L;
            } else {
                Matcher sizeMatcher = DOWNLOAD_SIZE_PATTERN.matcher(clean);
                if (sizeMatcher.matches()) {
                    double downloadedValue = Double.parseDouble(sizeMatcher.group(1));
                    String unit = sizeMatcher.group(2);
                    downloadedMb = toMegabytes(downloadedValue, unit);
                }
            }

            sink.onDownloadProgress(percent, downloadedMb, totalMb);
        } catch (Exception ignored) {
        }
    }

    private static long bytesToMb(long bytes) {
        if (bytes <= 0) return 0L;
        return Math.max(0L, Math.round(bytes / (1024.0 * 1024.0)));
    }

    private static long parseLongSafe(String raw) {
        if (raw == null || raw.isBlank() || "NA".equalsIgnoreCase(raw)) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double parseDoubleSafe(String raw) {
        if (raw == null || raw.isBlank() || "NA".equalsIgnoreCase(raw)) return 0.0;
        try {
            String clean = raw.trim().replace("%", "");
            if (clean.contains(",") && clean.contains(".")) {
                clean = clean.replace(",", "");
            } else {
                clean = clean.replace(",", ".");
            }
            return Double.parseDouble(clean);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static long toMegabytes(double value, String unit) {
        if (unit == null) return 0L;
        return switch (unit) {
            case "TiB" -> Math.max(1L, Math.round(value * 1024.0 * 1024.0));
            case "GiB" -> Math.max(1L, Math.round(value * 1024.0));
            case "KiB" -> Math.max(0L, Math.round(value / 1024.0));
            case "B" -> Math.max(0L, Math.round(value / (1024.0 * 1024.0)));
            default -> Math.max(1L, Math.round(value));
        };
    }

    private static String buildDownloadFormatSelector(int preferredHeight) {
        return "bestvideo[height<=" + preferredHeight + "][fps<=60][vcodec^=avc1]+bestaudio[acodec^=mp4a]"
            + "/bestvideo[height<=" + preferredHeight + "][fps<=60][ext=mp4]+bestaudio[ext=m4a]"
            + "/bestvideo[height<=" + preferredHeight + "][fps<=60][vcodec!*=av01]+bestaudio"
            + "/bestvideo[height<=" + preferredHeight + "]+bestaudio"
            + "/best[height<=" + preferredHeight + "][fps<=60]"
            + "/best[height<=" + preferredHeight + "]"
            + "/best";
    }

    private static void addOptionalYouTubeAuthArgs(List<String> command) {
        Path cookies = getToolsDir().resolve("youtube.cookies.txt");
        if (Files.isRegularFile(cookies)) {
            command.add("--cookies");
            command.add(cookies.toString());
        } else {
            String cookiesFromBrowser = readOptionalTrimmed(getToolsDir().resolve("youtube.cookies-from-browser.txt"));
            if (cookiesFromBrowser != null && !cookiesFromBrowser.isBlank()) {
                command.add("--cookies-from-browser");
                command.add(cookiesFromBrowser);
            }
        }

        String extractorArgs = readOptionalTrimmed(getToolsDir().resolve("youtube.extractor_args.txt"));
        if (extractorArgs != null && !extractorArgs.isBlank()) {
            command.add("--extractor-args");
            command.add(extractorArgs);
        }
    }

    private static void addOptionalTwitchAuthArgs(List<String> command) {
        Path cookies = getToolsDir().resolve("twitch.cookies.txt");
        if (Files.isRegularFile(cookies)) {
            command.add("--cookies");
            command.add(cookies.toString());
        } else {
            String cookiesFromBrowser = readOptionalTrimmed(getToolsDir().resolve("twitch.cookies-from-browser.txt"));
            if (cookiesFromBrowser != null && !cookiesFromBrowser.isBlank()) {
                command.add("--cookies-from-browser");
                command.add(cookiesFromBrowser);
            }
        }
    }

    private static String readOptionalTrimmed(Path path) {
        try {
            if (!Files.isRegularFile(path)) return null;
            return Files.readString(path).trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static StreamMeta resolveStreamMeta(String url, boolean youtube) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(getYtdlpPath().toString());
        command.add("--print");
        command.add(META_PREFIX + "%(id)s\t%(duration)s\t%(is_live)s\t%(live_status)s");
        command.add("--skip-download");
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--no-check-certificates");
        command.add("--no-cache-dir");
        command.add("--quiet");
        command.add("--socket-timeout");
        command.add("10");
        command.add("--retries");
        command.add("1");
        command.add("--extractor-retries");
        command.add("1");
        if (youtube) {
            addOptionalYouTubeAuthArgs(command);
        } else {
            addOptionalTwitchAuthArgs(command);
        }
        command.add(url);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        StreamMeta meta = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                String trimmed = line.trim();
                if (trimmed.startsWith(META_PREFIX)) {
                    meta = parsePrintedStreamMeta(trimmed);
                }
            }
        }

        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("yt-dlp metadata timeout");
        }
        if (process.exitValue() != 0) {
            String err = output.toString().trim();
            if (err.length() > 300) {
                err = err.substring(0, 300) + "...";
            }
            throw new Exception("yt-dlp metadata failed: " + err);
        }

        if (meta == null) {
            throw new Exception("yt-dlp metadata is empty");
        }
        return meta;
    }

    private static String resolveDirectStreamUrl(String url, int preferredHeight, boolean youtube) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(getYtdlpPath().toString());
        command.add("-f");
        command.add(buildLiveFormatSelector(preferredHeight));
        if (!youtube) {
            command.add("-S");
            command.add(buildLiveFormatSort(preferredHeight));
        }
        command.add("-g");
        command.add("--no-playlist");
        command.add("--no-warnings");
        command.add("--no-check-certificates");
        command.add("--no-cache-dir");
        command.add("--socket-timeout");
        command.add("10");
        command.add("--retries");
        command.add("1");
        command.add("--extractor-retries");
        command.add("1");
        if (youtube) {
            addOptionalYouTubeAuthArgs(command);
        } else {
            addOptionalTwitchAuthArgs(command);
        }
        command.add(url);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String directUrl = null;
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                output.append(trimmed).append('\n');
                if (directUrl == null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                    directUrl = trimmed;
                }
            }
        }

        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("yt-dlp direct URL timeout");
        }
        if (process.exitValue() != 0 || directUrl == null || directUrl.isBlank()) {
            String err = output.toString().trim();
            if (err.length() > 200) {
                err = err.substring(0, 200) + "...";
            }
            throw new Exception("yt-dlp direct URL failed: " + err);
        }

        return directUrl;
    }

    private static int normalizeTargetHeight(int preferredHeight) {
        return Math.max(360, Math.min(2160, preferredHeight));
    }

    private static String cacheKey(String videoId, int preferredHeight) {
        return videoId + "@" + normalizeTargetHeight(preferredHeight);
    }

    private static String buildCachePrefix(String videoId, int preferredHeight) {
        return videoId + "." + normalizeTargetHeight(preferredHeight) + "p";
    }

    private static String buildLiveFormatSelector(int preferredHeight) {
        return "best[height<=" + preferredHeight + "][protocol*=m3u8]"
            + "/best[height<=" + preferredHeight + "]"
            + "/best";
    }

    private static String buildLiveFormatSort(int preferredHeight) {
        return "res:" + normalizeTargetHeight(preferredHeight) + ",fps";
    }

    private static StreamMeta parsePrintedStreamMeta(String line) {
        String payload = line.substring(META_PREFIX.length());
        String[] parts = payload.split("\t", -1);
        String sourceId = parts.length > 0 ? blankToNull(parts[0]) : null;
        String durationRaw = parts.length > 1 ? parts[1] : "";
        String isLiveRaw = parts.length > 2 ? parts[2] : "";
        String liveStatusRaw = parts.length > 3 ? parts[3] : "";

        boolean live = Boolean.parseBoolean(isLiveRaw)
            || "is_live".equalsIgnoreCase(liveStatusRaw)
            || "post_live".equalsIgnoreCase(liveStatusRaw);
        long durationMs = live ? 0L : parseDurationToken(durationRaw);
        return new StreamMeta(sourceId, durationMs, live);
    }

    private static int qualityDistance(Path path, int preferredHeight) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int requested = normalizeTargetHeight(preferredHeight);
        int quality = extractCachedQuality(name);

        if (quality <= 0) {
            return 10_000 + Math.abs(requested - YouTubeQuality.DEFAULT);
        }
        if (quality > requested) {
            return 5_000 + (quality - requested);
        }
        return requested - quality;
    }

    private static int extractCachedQuality(String name) {
        try {
            Matcher matcher = Pattern.compile("\\.(\\d{3,4})p\\.[^.]+$").matcher(name);
            if (!matcher.find()) {
                return 0;
            }
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Path findCachedYoutubeFile(String videoId, int preferredHeight) {
        try {
            Path dir = getYoutubeCacheDir();
            if (!Files.isDirectory(dir)) return null;
            String exactPrefix = buildCachePrefix(videoId, preferredHeight) + ".";
            try (var stream = Files.list(dir)) {
                Path exact = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(exactPrefix))
                    .filter(YouTubeResolver::isCompletedYoutubeMediaFile)
                    .sorted(Comparator.comparingLong(YouTubeResolver::safeLastModified).reversed())
                    .findFirst()
                    .orElse(null);
                if (exact != null) {
                    return exact;
                }
            }

            if (normalizeTargetHeight(preferredHeight) != YouTubeQuality.DEFAULT) {
                return null;
            }

            String legacyPrefix = videoId + ".";
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(legacyPrefix) && extractCachedQuality(name.toLowerCase(Locale.ROOT)) == 0;
                    })
                    .filter(YouTubeResolver::isCompletedYoutubeMediaFile)
                    .sorted(Comparator.comparingLong(YouTubeResolver::safeLastModified).reversed())
                    .findFirst()
                    .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static long readCachedDuration(String videoId) {
        try {
            Path path = getYoutubeCacheDir().resolve(videoId + ".duration.txt");
            if (!Files.isRegularFile(path)) return 0L;
            return Long.parseLong(Files.readString(path).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void writeCachedDuration(String videoId, long durationMs) {
        if (durationMs <= 0) return;
        try {
            Files.createDirectories(getYoutubeCacheDir());
            Files.writeString(getYoutubeCacheDir().resolve(videoId + ".duration.txt"), Long.toString(durationMs));
        } catch (Exception ignored) {
        }
    }

    private static long parseDuration(String duration) {
        if (duration == null || duration.isBlank()) return 0;

        try {
            String[] parts = duration.split(":");
            long seconds = 0;

            if (parts.length == 1) {
                double rawSeconds = Double.parseDouble(parts[0]);
                seconds = Math.round(rawSeconds);
            } else if (parts.length == 2) {
                seconds = Long.parseLong(parts[0]) * 60 + Math.round(Double.parseDouble(parts[1]));
            } else if (parts.length == 3) {
                seconds = Long.parseLong(parts[0]) * 3600
                    + Long.parseLong(parts[1]) * 60
                    + Math.round(Double.parseDouble(parts[2]));
            }

            return seconds * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseDurationFromMetadata(String json) {
        if (json == null || json.isBlank()) {
            return 0L;
        }
        try {
            Matcher matcher = DURATION_JSON_PATTERN.matcher(json);
            if (!matcher.find()) {
                return 0L;
            }
            return Math.max(0L, Math.round(Double.parseDouble(matcher.group(1)) * 1000.0));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseDurationToken(String token) {
        if (token == null || token.isBlank() || "NA".equalsIgnoreCase(token)) {
            return 0L;
        }
        try {
            return Math.max(0L, Math.round(Double.parseDouble(token.trim()) * 1000.0));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean isAuthenticationChallengeError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("sign in to confirm")
            || lower.contains("you're not a bot")
            || lower.contains("cookies-from-browser")
            || lower.contains("use --cookies")
            || lower.contains("confirm you");
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isCompletedYoutubeMediaFile(Path path) {
        if (path == null) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".description")) return false;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) return false;
        if (name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith(".ytdl") || name.contains(".part")) return false;
        return name.endsWith(".mp4")
            || name.endsWith(".mkv")
            || name.endsWith(".webm")
            || name.endsWith(".mov")
            || name.endsWith(".m4v");
    }

    private static long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteYoutubeTempArtifacts(String cachePrefix) {
        try {
            Path dir = getYoutubeCacheDir();
            if (!Files.isDirectory(dir)) return;
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(cachePrefix + "."))
                    .filter(path -> !isCompletedYoutubeMediaFile(path))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
            }
        } catch (Exception ignored) {
        }
    }

    private static void updatePrintedDownloadSize(String line, AtomicLong totalEstimateBytes) {
        if (line == null || totalEstimateBytes == null) return;
        String payload = line.substring("before_dl:CollinsYTMeta:".length());
        String[] parts = payload.split(":", -1);
        long exactBytes = parts.length > 0 ? parseLongSafe(parts[0]) : 0L;
        long estimatedBytes = parts.length > 1 ? parseLongSafe(parts[1]) : 0L;
        long totalBytes = exactBytes > 0 ? exactBytes : estimatedBytes;
        if (totalBytes > 0) {
            totalEstimateBytes.set(totalBytes);
        }
    }

    private static void monitorYoutubeDownloadProgress(String cachePrefix, AtomicLong totalEstimateBytes,
                                                       AtomicBoolean running, VideoPlayer.FrameSink sink) {
        long lastBytes = -1L;
        while (running.get()) {
            try {
                long downloadedBytes = measureYoutubeDownloadBytes(cachePrefix);
                if (downloadedBytes > 0 && downloadedBytes != lastBytes) {
                    lastBytes = downloadedBytes;
                    long totalBytes = totalEstimateBytes.get();
                    if (totalBytes > 0 && downloadedBytes > totalBytes) {
                        downloadedBytes = totalBytes;
                    }
                    long downloadedMb = bytesToMb(downloadedBytes);
                    long totalMb = bytesToMb(totalBytes);
                    int percent = totalBytes > 0
                        ? (int) Math.max(0L, Math.min(100L, Math.round((downloadedBytes * 100.0) / totalBytes)))
                        : 0;
                    sink.onDownloadProgress(percent, downloadedMb, totalMb);
                }
                Thread.sleep(750L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private static long estimateDownloadSizeBytes(String videoUrl, String selector) {
        try {
            List<String> command = new ArrayList<>();
            command.add(getYtdlpPath().toString());
            command.add("-f");
            command.add(selector);
            command.add("--simulate");
            command.add("--no-playlist");
            command.add("--no-warnings");
            command.add("--no-check-certificates");
            command.add("--print");
            command.add("CollinsYTMeta:%(filesize)s:%(filesize_approx)s");
            addOptionalYouTubeAuthArgs(command);
            command.add(videoUrl);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            long totalBytes = 0L;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("CollinsYTMeta:")) {
                        String payload = trimmed.substring("CollinsYTMeta:".length());
                        String[] parts = payload.split(":", -1);
                        long exactBytes = parts.length > 0 ? parseLongSafe(parts[0]) : 0L;
                        long approxBytes = parts.length > 1 ? parseLongSafe(parts[1]) : 0L;
                        totalBytes = exactBytes > 0 ? exactBytes : approxBytes;
                        if (totalBytes > 0) {
                            break;
                        }
                    }
                }
            }
            process.waitFor(20, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            return totalBytes;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long measureYoutubeDownloadBytes(String cachePrefix) {
        long total = 0L;
        try {
            Path dir = getYoutubeCacheDir();
            if (!Files.isDirectory(dir)) return 0L;
            try (var stream = Files.list(dir)) {
                total = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(cachePrefix + "."))
                    .filter(path -> !path.getFileName().toString().endsWith(".txt"))
                    .filter(path -> !path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".description"))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception e) {
                            return 0L;
                        }
                    })
                    .sum();
            }
        } catch (Exception ignored) {
            return 0L;
        }
        return total;
    }

    private static Path getYtdlpPath() {
        try {
            return getToolsDir().resolve("yt-dlp.exe");
        } catch (Exception e) {
            return Path.of("collins-tools", "yt-dlp.exe");
        }
    }

    private static Path getFfmpegPath() {
        try {
            return getToolsDir().resolve("ffmpeg.exe");
        } catch (Exception e) {
            return Path.of("collins-tools", "ffmpeg.exe");
        }
    }

    private static Path getToolsDir() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("collins-tools");
        } catch (Exception e) {
            return Path.of("collins-tools");
        }
    }

    private static Path getYoutubeCacheDir() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("collins-cache").resolve("youtube");
        } catch (Exception e) {
            return Path.of("collins-cache", "youtube");
        }
    }

    public static void clearCache() {
        URL_CACHE.clear();
    }

    private static boolean isLiveJson(String json) {
        Matcher isLiveMatcher = IS_LIVE_PATTERN.matcher(json);
        if (isLiveMatcher.find() && Boolean.parseBoolean(isLiveMatcher.group(1))) {
            return true;
        }

        Matcher liveStatusMatcher = LIVE_STATUS_PATTERN.matcher(json);
        if (!liveStatusMatcher.find()) {
            return false;
        }

        String status = liveStatusMatcher.group(1);
        return "is_live".equalsIgnoreCase(status) || "post_live".equalsIgnoreCase(status);
    }

    public static String getYtdlpVersion() {
        if (!ytdlpAvailable) return "not installed";

        try {
            Path ytdlp = getYtdlpPath();
            ProcessBuilder pb = new ProcessBuilder(ytdlp.toString(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String version = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                return version != null ? version.trim() : "unknown";
            }
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    public static boolean updateYtdlp() {
        if (!ytdlpAvailable) return false;

        try {
            Path ytdlp = getYtdlpPath();
            ProcessBuilder pb = new ProcessBuilder(ytdlp.toString(), "-U");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) {
                }
            }

            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            dbg("updateYtdlp: error " + e.getMessage());
            return false;
        }
    }
}
