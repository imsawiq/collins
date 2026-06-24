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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Live set of yt-dlp / ffmpeg subprocesses that {@link VideoPlayer#stop()}
     * may need to terminate. Without this, stopping a video mid-download
     * left the yt-dlp process running for up to 30 minutes (its waitFor
     * timeout) writing to the on-disk cache, holding native pipes, and
     * occasionally racing with a fresh playback session for the same
     * cache file. Membership is bounded by the number of in-flight
     * downloads, which is at most one per VideoPlayer instance.
     */
    private static final Set<Process> ACTIVE_DOWNLOAD_PROCESSES = ConcurrentHashMap.newKeySet();

    /**
     * Per-download cleanup hook keyed by the spawned yt-dlp process. Runs
     * AFTER the process is forcibly terminated to delete the partial cache
     * artifacts ({@code .part}, {@code .ytdl}, {@code .frag*}, the
     * not-yet-finalised {@code .mkv}/{@code .mp4}, etc.) the cancelled
     * download left on disk. Without this, hitting stop on a 2 GB video
     * mid-download left a 1.9 GB carcass in {@code collins-cache/} that
     * was only cleaned up the next time the same id was re-downloaded.
     */
    private static final java.util.Map<Process, Runnable> ACTIVE_DOWNLOAD_CLEANUPS = new ConcurrentHashMap<>();

    /**
     * Forcibly terminates every yt-dlp / ffmpeg subprocess that is
     * currently downloading or probing a stream and removes the partial
     * artifacts each killed download left behind. Idempotent and safe to
     * call from any thread; called by {@link VideoPlayer#stop()} so a
     * user-initiated stop releases native resources immediately rather
     * than waiting for the in-flight download to complete.
     */
    public static void cancelActiveDownloads() {
        // Snapshot first; do NOT clear() here - each process removes itself
        // from the registry in its own finally block, which avoids a race
        // where a freshly-started download is wiped from the set before its
        // owning thread can track it.
        for (Process p : ACTIVE_DOWNLOAD_PROCESSES.toArray(new Process[0])) {
            Runnable cleanup = ACTIVE_DOWNLOAD_CLEANUPS.remove(p);
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {
            }
            if (cleanup != null) {
                // Defer the on-disk cleanup until the OS has fully reaped
                // the child: on Windows yt-dlp / ffmpeg may still hold a
                // write handle to the partial file for a brief moment
                // after destroyForcibly() returns, and Files.delete will
                // throw AccessDeniedException in that window.
                p.onExit().thenRun(() -> {
                    try {
                        cleanup.run();
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }
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

    // RuTube videos use a 32-char lowercase hex id under several path prefixes.
    // Mirrors the canonical match in yt-dlp's RutubeIE._VALID_URL.
    private static final Pattern RUTUBE_PATTERN = Pattern.compile(
        "(?:https?://)?(?:www\\.)?rutube\\.ru/(?:(?:live/)?video(?:/private)?|(?:play/)?embed|shorts)/([0-9a-f]{32})"
    );

    // VK videos always reference a (owner_id)_(video_id) pair. Owner can be
    // negative (group/community). Hosts: vk.com, m.vk.com, vkvideo.ru, m.vkvideo.ru.
    // Mirrors the relevant subset of yt-dlp's VKVideoIE._VALID_URL.
    private static final Pattern VK_PATTERN = Pattern.compile(
        "(?:https?://)?(?:(?:www\\.|m\\.|new\\.)?vk\\.com|(?:www\\.|m\\.)?vkvideo\\.ru)/(?:video|clip|video_ext\\.php\\?[^#]*?\\boid=(-?\\d+)[^#]*?\\bid=(\\d+)|[^?#]*?\\bz=video)(-?\\d+_\\d+)?"
    );

    /**
     * Single-source-of-truth platform classifier for any URL we know how to
     * resolve via yt-dlp + ffmpeg. Each entry carries the on-disk cache
     * subdirectory (relative to {@code collins-cache/}).
     */
    private enum Platform {
        YOUTUBE("youtube"),
        TWITCH("twitch"),
        RUTUBE("rutube"),
        VK("vk");

        final String cacheSubdir;

        Platform(String cacheSubdir) {
            this.cacheSubdir = cacheSubdir;
        }
    }

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
    private static final Object TOOL_LOCK = new Object();

    // ----- Cross-platform tool resolution --------------------------------
    // Resolved at class-init from os.name / os.arch so we pick the right
    // yt-dlp / ffmpeg binary on Windows, Linux (x64 + arm64) and macOS.
    // Without this every Linux server crashed with `error=13, Permission
    // denied` because we shipped only `yt-dlp.exe` and never set the
    // executable bit even when the user supplied a Linux binary manually.
    private enum Os { WINDOWS, LINUX, MACOS, OTHER }
    private enum Arch { X64, ARM64, OTHER }

    private static final Os HOST_OS = detectOs();
    private static final Arch HOST_ARCH = detectArch();
    private static final boolean POSIX = HOST_OS == Os.LINUX || HOST_OS == Os.MACOS;

    private static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return Os.MACOS;
        if (name.contains("nix") || name.contains("nux") || name.contains("aix")) return Os.LINUX;
        return Os.OTHER;
    }

    private static Arch detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return Arch.ARM64;
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) return Arch.X64;
        return Arch.OTHER;
    }

    private static String ytdlpBinaryName() {
        return HOST_OS == Os.WINDOWS ? "yt-dlp.exe" : "yt-dlp";
    }

    private static String ffmpegBinaryName() {
        return HOST_OS == Os.WINDOWS ? "ffmpeg.exe" : "ffmpeg";
    }

    private static String ytdlpDownloadUrl() {
        String base = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
        return switch (HOST_OS) {
            case WINDOWS -> base + "yt-dlp.exe";
            case LINUX -> base + (HOST_ARCH == Arch.ARM64 ? "yt-dlp_linux_aarch64" : "yt-dlp_linux");
            case MACOS -> base + "yt-dlp_macos";
            case OTHER -> null;
        };
    }

    /**
     * BtbN ships builds for Windows and Linux (x64 + arm64) only - there is
     * no macOS build in that project at all, so the old osx64 URL returned
     * HTTP 404 on every Mac and users were forced to install ffmpeg by hand.
     * For macOS we use Martin Riedl's static builds, which cover both
     * Apple Silicon (arm64) and Intel (amd64) as single-binary zips.
     */
    private static String ffmpegDownloadUrl() {
        String base = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-";
        return switch (HOST_OS) {
            case WINDOWS -> base + "win64-gpl.zip";
            case LINUX -> base + (HOST_ARCH == Arch.ARM64 ? "linuxarm64-gpl.tar.xz" : "linux64-gpl.tar.xz");
            case MACOS -> "https://ffmpeg.martin-riedl.de/redirect/latest/macos/"
                + (HOST_ARCH == Arch.ARM64 ? "arm64" : "amd64") + "/release/ffmpeg.zip";
            case OTHER -> null;
        };
    }

    /**
     * Mark {@code path} as executable on POSIX systems. Without this the
     * downloaded yt-dlp / ffmpeg binary is created with the JVM's default
     * 644 permissions and {@link ProcessBuilder#start()} fails with
     * {@code IOException: Cannot run program ...: error=13, Permission
     * denied}.
     */
    private static void ensureExecutable(Path path) {
        if (!POSIX || path == null) return;
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception e) {
            try { path.toFile().setExecutable(true, false); } catch (Exception ignored) {}
        }
    }

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

    public static boolean isRuTubeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        return url.toLowerCase(Locale.ROOT).contains("rutube.ru");
    }

    public static boolean isVKUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("vk.com/video")
            || u.contains("vk.com/clip")
            || u.contains("vk.com/video_ext.php")
            || u.contains("vkvideo.ru");
    }

    public static boolean isSupportedPlatformUrl(String url) {
        return isYouTubeUrl(url) || isTwitchUrl(url) || isRuTubeUrl(url) || isVKUrl(url);
    }

    /**
     * Resolves a URL to its {@link Platform} or {@code null} if we don't
     * know how to handle it. Order matters - host checks are mutually
     * exclusive, so the order is irrelevant for correctness, but YouTube
     * is checked first because it's the hottest path.
     */
    private static Platform classifyPlatform(String url) {
        if (isYouTubeUrl(url)) return Platform.YOUTUBE;
        if (isTwitchUrl(url)) return Platform.TWITCH;
        if (isRuTubeUrl(url)) return Platform.RUTUBE;
        if (isVKUrl(url)) return Platform.VK;
        return null;
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

    /** RuTube id is a 32-char lowercase hex string. */
    private static String extractRuTubeId(String url) {
        if (url == null) return null;
        Matcher m = RUTUBE_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * VK videos are addressed by {@code <ownerId>_<videoId>}. The pattern
     * captures the pair either from the {@code /video<owner>_<id>}-style
     * path (group 3) or from the {@code video_ext.php?oid=...&id=...}
     * variant (groups 1 + 2). Returns the canonical
     * {@code <owner>_<id>} string, or {@code null} if the URL is malformed.
     */
    private static String extractVKId(String url) {
        if (url == null) return null;
        Matcher m = VK_PATTERN.matcher(url);
        if (!m.find()) return null;

        String ownerIdPair = m.group(3);
        if (ownerIdPair != null && !ownerIdPair.isBlank()) {
            return ownerIdPair;
        }
        String oid = m.group(1);
        String id = m.group(2);
        if (oid != null && id != null) {
            return oid + "_" + id;
        }
        return null;
    }

    /**
     * Returns the canonical source-id for the given URL on its platform, or
     * {@code null} if the URL doesn't belong to a known platform or the id
     * could not be extracted. Used as the cache-key suffix.
     */
    private static String extractPlatformId(Platform platform, String url) {
        if (platform == null || url == null) return null;
        return switch (platform) {
            case YOUTUBE -> extractVideoId(url);
            case TWITCH -> extractTwitchChannel(url);
            case RUTUBE -> extractRuTubeId(url);
            case VK -> extractVKId(url);
        };
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

        Platform platform = classifyPlatform(url);
        if (platform == null) {
            return new YouTubeResult(null, null, 0, "Not a supported platform URL", false, false);
        }

        boolean youtube = platform == Platform.YOUTUBE;
        boolean twitch = platform == Platform.TWITCH;

        int targetHeight = normalizeTargetHeight(preferredHeight);

        String sourceId = extractPlatformId(platform, url);
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

        // === VOD cache fast path (replays, no network) - works for any
        // VOD platform (YouTube / RuTube / VK). The cache key is just
        // sourceId@quality so the namespace is shared across platforms;
        // their id formats don't collide (YT=11 chars b64, RuTube=32 hex,
        // VK=<owner>_<id>). The disk cache is platform-aware: each
        // platform owns a dedicated subdirectory under collins-cache/. ===
        Path platformCacheDir = getPlatformCacheDir(platform);
        if (sourceId != null && !sourceId.isBlank() && !sourceId.equals(Integer.toHexString(url.hashCode()))) {
            String cacheKey = cacheKey(sourceId, targetHeight);
            ResolvedUrl cached = URL_CACHE.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                Path cachedPath = Path.of(cached.directUrl());
                if (Files.isRegularFile(cachedPath)) {
                    notifyCachedFileUsed(cachedPath, sink);
                    dbg("resolve: fast path memory-cached local file for " + platform + ":" + sourceId);
                    return new YouTubeResult(cached.directUrl, sourceId, cached.durationMs, null, false, false);
                }
            }
            Path cachedFile = findCachedFile(platformCacheDir, sourceId, targetHeight);
            if (cachedFile != null) {
                long durationMs = readCachedDuration(platformCacheDir, sourceId);
                URL_CACHE.put(cacheKey, new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                notifyCachedFileUsed(cachedFile, sink);
                dbg("resolve: fast path disk-cached file for " + platform + ":" + sourceId + " quality=" + targetHeight);
                return new YouTubeResult(cachedFile.toString(), sourceId, durationMs, null, false, false);
            }
        }

        // === YouTube live fast path: direct hlsManifestUrl, no yt-dlp required. ===
        // If the video is not live, this returns null and we fall through to the
        // yt-dlp VOD download flow below. Passing targetHeight pins FFmpeg to
        // a specific variant so we get consistent quality without ABR jumps.
        if (youtube) {
            String videoId = extractVideoId(url);
            if (videoId != null && !videoId.isBlank()) {
                String liveHls = YouTubeLiveClient.resolveLiveHlsUrl(videoId, targetHeight);
                if (liveHls != null) {
                    dbg("resolve: youtube direct live HLS for " + videoId);
                    return new YouTubeResult(liveHls, videoId, 0L, null, false, true);
                }
            }
        }

        // === yt-dlp VOD path: works for YouTube / RuTube / VK. ===
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

            // VOD branch - same flow for YouTube, RuTube, VK: re-check the
            // cache with the authoritative source id from metadata, then
            // fall through to the yt-dlp download pipeline.
            String cacheKey = cacheKey(sourceId, targetHeight);
            ResolvedUrl cached = URL_CACHE.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                Path cachedPath = Path.of(cached.directUrl());
                if (Files.isRegularFile(cachedPath)) {
                    notifyCachedFileUsed(cachedPath, sink);
                    dbg("resolve: using memory-cached local file for " + platform + ":" + sourceId);
                    return new YouTubeResult(cached.directUrl, sourceId, cached.durationMs, null, false, false);
                }
            }

            Path cachedFile = findCachedFile(platformCacheDir, sourceId, targetHeight);
            if (cachedFile != null) {
                long durationMs = readCachedDuration(platformCacheDir, sourceId);
                URL_CACHE.put(cacheKey, new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                notifyCachedFileUsed(cachedFile, sink);
                dbg("resolve: using disk-cached local file for " + platform + ":" + sourceId + " quality=" + targetHeight);
                return new YouTubeResult(cachedFile.toString(), sourceId, durationMs, null, false, false);
            }

            if (!ensureFfmpegAvailable()) {
                return new YouTubeResult(null, sourceId, 0, "ffmpeg not available", true, false);
            }

            // YouTube uses the canonical /watch?v=<id> URL (legacy behaviour
            // preserved); RuTube and VK pass their original URL through to
            // yt-dlp because their canonical form already contains the id.
            String downloadUrl = (platform == Platform.YOUTUBE)
                ? "https://www.youtube.com/watch?v=" + sourceId
                : url;
            return downloadVodToCache(platform, downloadUrl, sourceId, targetHeight, sink);
        } catch (Exception e) {
            dbg("resolve: metadata error " + e.getMessage());

            // Minimal fallback chain: for VOD platforms (YT/RuTube/VK) we
            // can still hit the disk cache (transient extractor failure
            // doesn't invalidate previously-downloaded files). Skip the
            // expensive yt-dlp binary refresh - it re-downloads ~20MB on
            // every failure.
            Path cachedFile = findCachedFile(platformCacheDir, sourceId, targetHeight);
            if (cachedFile != null) {
                long durationMs = readCachedDuration(platformCacheDir, sourceId);
                URL_CACHE.put(cacheKey(sourceId, targetHeight), new ResolvedUrl(cachedFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
                notifyCachedFileUsed(cachedFile, sink);
                dbg("resolve: metadata fallback to cached " + platform + " file for " + sourceId);
                return new YouTubeResult(cachedFile.toString(), sourceId, durationMs, null, false, false);
            }

            if (youtube) {
                String extractedVideoId = extractVideoId(url);

                // Guard: if the watch page says this video is LIVE, the VOD
                // download fallback below must be skipped entirely. Feeding
                // a live stream to the yt-dlp download pipeline makes it
                // record indefinitely and the user sees an endless
                // "preparing video" phase. Resolve a direct live URL instead.
                Boolean liveHint = (extractedVideoId != null && !extractedVideoId.isBlank())
                    ? YouTubeLiveClient.checkLiveStatus(extractedVideoId)
                    : null;
                if (Boolean.TRUE.equals(liveHint)) {
                    try {
                        String directUrl = resolveDirectStreamUrl(url, targetHeight, true);
                        dbg("resolve: metadata fallback to direct YouTube live url (live hint)");
                        return new YouTubeResult(directUrl,
                            extractedVideoId != null ? extractedVideoId : sourceId,
                            0L, null, false, true);
                    } catch (Exception directError) {
                        dbg("resolve: live-hinted direct url failed " + directError.getMessage());
                        return new YouTubeResult(null,
                            extractedVideoId != null ? extractedVideoId : sourceId,
                            0, "Live resolution failed: " + directError.getMessage(), false, true);
                    }
                }

                // If the watch page did not give a confident live signal,
                // still give yt-dlp's direct resolver one short chance before
                // starting a disk download. On some YouTube live broadcasts
                // the public page/Innertube clients are rejected, but `-g`
                // still returns a signed live HLS manifest. Downloading that
                // through the VOD cache path records forever and leaves the
                // HUD stuck in "preparing".
                try {
                    String directUrl = resolveDirectStreamUrl(url, targetHeight, true);
                    if (looksLikeYouTubeLiveStreamUrl(directUrl)) {
                        dbg("resolve: metadata fallback to direct YouTube live url (manifest heuristic)");
                        return new YouTubeResult(directUrl,
                            extractedVideoId != null ? extractedVideoId : sourceId,
                            0L, null, false, true);
                    }
                    dbg("resolve: direct YouTube url does not look live; continuing to VOD cache");
                } catch (Exception directError) {
                    dbg("resolve: pre-download direct YouTube live probe failed " + directError.getMessage());
                }

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

                // Last-resort: try a direct live URL grab. Helps when the
                // stream is live and metadata fetch failed only due to a
                // transient extractor issue.
                try {
                    String directUrl = resolveDirectStreamUrl(url, targetHeight, true);
                    dbg("resolve: metadata fallback to direct YouTube live url");
                    return new YouTubeResult(directUrl, sourceId, 0L, null, false, true);
                } catch (Exception directError) {
                    dbg("resolve: fallback direct YouTube url failed " + directError.getMessage());
                    e = directError;
                }
            } else {
                // RuTube / VK: try a direct URL grab as the last resort. If
                // the platform happens to be live, this returns the live
                // playlist; for VODs it returns a direct CDN URL we can
                // play without going through the disk cache.
                try {
                    String directUrl = resolveDirectStreamUrl(url, targetHeight, false);
                    dbg("resolve: metadata fallback to direct " + platform + " url");
                    return new YouTubeResult(directUrl, sourceId, 0L, null, false, true);
                } catch (Exception directError) {
                    dbg("resolve: fallback direct " + platform + " url failed " + directError.getMessage());
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
        Platform platform = classifyPlatform(url);
        if (platform == null || platform == Platform.TWITCH) {
            // Twitch is live-only - no on-disk cache makes sense.
            return null;
        }
        String sourceId = extractPlatformId(platform, url);
        if (sourceId == null || sourceId.isBlank()) return null;

        int targetHeight = normalizeTargetHeight(preferredHeight);
        String cacheKey = cacheKey(sourceId, targetHeight);

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

        return findCachedFile(getPlatformCacheDir(platform), sourceId, targetHeight);
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
                String url = ytdlpDownloadUrl();
                if (url == null) {
                    dbg("ensureYtdlpAvailable: no yt-dlp build for " + HOST_OS + "/" + HOST_ARCH);
                    return false;
                }
                Files.createDirectories(getToolsDir());
                downloadFile(url, ytdlp, true);
                ytdlpAvailable = Files.isRegularFile(ytdlp);
                if (ytdlpAvailable) ensureExecutable(ytdlp);
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
                String url = ytdlpDownloadUrl();
                if (url == null) {
                    dbg("tryRefreshYtdlpBinary: no yt-dlp build for " + HOST_OS + "/" + HOST_ARCH);
                    return false;
                }
                dbg("tryRefreshYtdlpBinary: refreshing yt-dlp after resolution failure");
                Files.createDirectories(getToolsDir());
                downloadFile(url, getYtdlpPath(), false);
                Path bin = getYtdlpPath();
                ytdlpAvailable = Files.isRegularFile(bin);
                if (ytdlpAvailable) ensureExecutable(bin);
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
                String url = ffmpegDownloadUrl();
                if (url == null) {
                    dbg("ensureFfmpegAvailable: no ffmpeg build for " + HOST_OS + "/" + HOST_ARCH
                        + "; install ffmpeg manually and put it on PATH");
                    return false;
                }
                Files.createDirectories(getToolsDir());
                boolean isZip = url.endsWith(".zip");
                Path archiveTmp = getToolsDir().resolve(isZip ? "ffmpeg.zip.tmp" : "ffmpeg.tar.xz.tmp");
                downloadFile(url, archiveTmp, false);

                ffmpegAvailable = isZip
                    ? extractFfmpegFromZip(archiveTmp, ffmpeg)
                    : extractFfmpegFromTarXz(archiveTmp, ffmpeg);

                Files.deleteIfExists(archiveTmp);
                if (ffmpegAvailable) ensureExecutable(ffmpeg);
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
        return downloadVodToCache(Platform.YOUTUBE,
                "https://www.youtube.com/watch?v=" + videoId,
                videoId,
                preferredHeight,
                sink);
    }

    /**
     * Generic VOD download pipeline. Runs {@code yt-dlp} against
     * {@code sourceUrl} and saves the resulting file under the platform's
     * cache directory using {@code sourceId} as the filename prefix.
     *
     * <p>The body is structurally identical to the original
     * {@code downloadYoutubeToCache} - it just substitutes the cache dir
     * and gates the YouTube-specific {@code --cookies}/{@code --extractor-args}
     * injection on {@code platform == YOUTUBE}. RuTube and VK go through
     * exactly this code path with their original URL and extracted source
     * id.</p>
     */
    private static YouTubeResult downloadVodToCache(Platform platform,
                                                    String sourceUrl,
                                                    String sourceId,
                                                    int preferredHeight,
                                                    VideoPlayer.FrameSink sink) throws Exception {
        int targetHeight = normalizeTargetHeight(preferredHeight);
        Path cacheDir = getPlatformCacheDir(platform);
        Files.createDirectories(cacheDir);
        String cachePrefix = buildCachePrefix(sourceId, targetHeight);
        deleteTempArtifacts(cacheDir, cachePrefix);

        Path outputBase = cacheDir.resolve(cachePrefix + ".%(ext)s");

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
        // Hard refusal to download live streams through the VOD pipeline:
        // yt-dlp would otherwise record the live feed indefinitely. With
        // the filter it skips the entry and exits quickly, which surfaces
        // as a normal "Downloaded file not found" error to the caller.
        command.add("--match-filters");
        command.add("!is_live");
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
        // Six progress fields: percent, downloaded_bytes, total_bytes,
        // total_bytes_estimate, fragment_index, fragment_count. The last
        // two are critical for HLS / DASH (RuTube live, RuTube HLS,
        // some VK formats) where total_bytes is often NA throughout the
        // entire download - fragments give us a monotonic, reliable
        // progress signal even when byte estimates aren't available.
        command.add("--progress-template");
        command.add("download:CollinsYTProgress:%(progress.percent)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s:%(progress.fragment_index)s:%(progress.fragment_count)s");
        command.add("--progress-template");
        command.add("download:fragment:CollinsYTProgress:%(progress.percent)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s:%(progress.fragment_index)s:%(progress.fragment_count)s");
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
        if (platform == Platform.YOUTUBE) {
            addOptionalYouTubeAuthArgs(command);
        }
        command.add(sourceUrl);

        dbg("downloadVodToCache: platform=" + platform + " selector=" + selector);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (sink != null) {
            // Emit a platform-specific phase string so the HUD can render
            // the correct label (YouTube / RuTube / VK) instead of always
            // showing "YouTube: downloading...".
            String downloadingKey = switch (platform) {
                case YOUTUBE -> "collins.video.youtube_downloading";
                case RUTUBE -> "collins.video.rutube_downloading";
                case VK -> "collins.video.vk_downloading";
                // Twitch goes through direct HLS and shouldn't normally hit
                // downloadVodToCache, but if it ever does, fall back to a
                // generic label rather than mislabelling the stream as YouTube.
                case TWITCH -> "collins.video.downloading";
            };
            sink.onDownloadStart(downloadingKey);
        }

        Process p = pb.start();
        ACTIVE_DOWNLOAD_PROCESSES.add(p);
        // Register the partial-artifact cleanup BEFORE any blocking IO so
        // a near-instant cancelActiveDownloads() (e.g. user stops video
        // milliseconds after starting it) still finds the hook.
        ACTIVE_DOWNLOAD_CLEANUPS.put(p, () -> deleteTempArtifacts(cacheDir, cachePrefix));
        StringBuilder output = new StringBuilder();
        String finalPath = null;
        long durationMs = 0L;
        AtomicLong totalEstimateBytes = new AtomicLong(Math.max(0L, estimatedTotalBytes));
        AtomicBoolean monitorRunning = new AtomicBoolean(sink != null);
        Thread progressMonitor = null;
        if (sink != null) {
            progressMonitor = new Thread(() -> monitorDownloadProgress(cacheDir, cachePrefix, totalEstimateBytes, monitorRunning, sink),
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
                    // Surface the total size to the HUD the moment yt-dlp
                    // announces it (pre-transfer), so the progress line
                    // flips from "preparing..." to "X: 0% (0 MB / Y MB)"
                    // without waiting for the first progress tick. The
                    // Math.max merge in VideoScreen.onDownloadProgress
                    // protects against later "audio-only" announcements
                    // shrinking the displayed total.
                    if (sink != null) {
                        long announcedBytes = totalEstimateBytes.get();
                        if (announcedBytes > 0) {
                            sink.onDownloadProgress(0, 0L, bytesToMb(announcedBytes));
                        }
                    }
                }
                if (sink != null) {
                    updateDownloadProgressFromYtdlp(trimmed, sink, totalEstimateBytes);
                }
                if (trimmed.startsWith("after_move:")) {
                    finalPath = trimmed.substring("after_move:".length()).trim();
                } else if (durationMs == 0L) {
                    durationMs = parseDuration(trimmed);
                }
            }
        }

        boolean finished;
        try {
            finished = p.waitFor(30, TimeUnit.MINUTES);
        } finally {
            // Always evict from the active-process registry so that a later
            // cancelActiveDownloads() doesn't try to destroy an already-dead
            // pid (typically harmless but creates noisy logs on some JDKs).
            ACTIVE_DOWNLOAD_PROCESSES.remove(p);
            // Drop the cleanup hook on the normal-exit path. Leaving it in
            // would cause a later, unrelated cancel to nuke a perfectly
            // good cached file just because it shares the same prefix.
            ACTIVE_DOWNLOAD_CLEANUPS.remove(p);
        }
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
            return new YouTubeResult(null, sourceId, 0, "yt-dlp timeout while downloading video", false, false);
        }

        if (p.exitValue() != 0) {
            String err = output.toString().trim();
            if (err.length() > 300) err = err.substring(0, 300) + "...";
            return new YouTubeResult(null, sourceId, 0, "yt-dlp download error: " + err, false, false);
        }

        Path finalFile = null;
        if (finalPath != null && !finalPath.isBlank()) {
            Path candidate = Path.of(finalPath);
            if (Files.isRegularFile(candidate)) {
                finalFile = candidate;
            }
        }
        if (finalFile == null) {
            finalFile = findCachedFile(cacheDir, sourceId, targetHeight);
        }
        if (finalFile == null) {
            return new YouTubeResult(null, sourceId, 0, "Downloaded file not found", false, false);
        }

        writeCachedDuration(cacheDir, sourceId, durationMs);
        URL_CACHE.put(cacheKey(sourceId, targetHeight), new ResolvedUrl(finalFile.toString(), sourceId, System.currentTimeMillis(), durationMs));
        notifyCachedFileUsed(finalFile, sink);
        dbg("downloadVodToCache: platform=" + platform + " cached file=" + finalFile);
        return new YouTubeResult(finalFile.toString(), sourceId, durationMs, null, false, false);
    }

    private static void notifyCachedFileUsed(Path file, VideoPlayer.FrameSink sink) {
        if (sink == null || file == null) return;
        try {
            sink.onCachedFileUsed(file.toString(), Files.size(file));
        } catch (Exception ignored) {
        }
    }

    private static void updateDownloadProgressFromYtdlp(String line, VideoPlayer.FrameSink sink, AtomicLong totalEstimateAtomic) {
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
                long fragmentIndex = parts.length > 4 ? parseLongSafe(parts[4]) : 0L;
                long fragmentCount = parts.length > 5 ? parseLongSafe(parts[5]) : 0L;

                long totalBytesFinal = totalBytes > 0 ? totalBytes : totalEstimateBytes;

                // HLS / DASH fallback: yt-dlp leaves total_bytes and
                // total_bytes_estimate as NA for some fragment-based
                // formats (RuTube HLS, certain VK clips). Scale the
                // already-downloaded bytes by the fragment ratio to get
                // a usable total estimate. Refines on every tick as more
                // fragments arrive; VideoScreen.onDownloadProgress takes
                // a Math.max so the displayed total never shrinks.
                if (totalBytesFinal <= 0 && fragmentCount > 0 && fragmentIndex > 0 && downloadedBytes > 0) {
                    totalBytesFinal = Math.round((double) downloadedBytes * fragmentCount / fragmentIndex);
                }

                // Keep the disk-poll monitor in sync. Without this the
                // monitor thread would compute percent against a stale
                // 0 estimate and emit (0%, dlMb, 0) on every tick,
                // forcing the HUD into the "X MB..." (no percent) branch.
                final long observedTotalBytes = totalBytesFinal;
                if (observedTotalBytes > 0 && totalEstimateAtomic != null) {
                    totalEstimateAtomic.updateAndGet(prev -> Math.max(prev, observedTotalBytes));
                }

                long downloadedMb = bytesToMb(downloadedBytes);
                long totalMb = bytesToMb(totalBytesFinal);
                // Preference order:
                // 1. yt-dlp's own percent (most accurate when available)
                // 2. fragment ratio (monotonic, ideal for HLS)
                // 3. byte ratio (fine for non-fragment formats)
                int percent;
                if (percentRaw > 0.0) {
                    percent = (int) Math.round(percentRaw);
                } else if (fragmentCount > 0 && fragmentIndex > 0) {
                    percent = (int) Math.min(100L, Math.round((fragmentIndex * 100.0) / fragmentCount));
                } else if (totalBytesFinal > 0 && downloadedBytes > 0) {
                    percent = (int) Math.round((downloadedBytes * 100.0) / totalBytesFinal);
                } else {
                    percent = 0;
                }
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
        } else {
            // Default client mix that minimises YouTube's "Sign in to
            // confirm you're not a bot" gate. `default` keeps yt-dlp's own
            // client order; tv_simply and android_vr are appended because
            // neither currently requires a PO token or login and they are
            // the clients least affected by datacenter-IP bot checks. Users
            // can fully override this via youtube.extractor_args.txt.
            command.add("--extractor-args");
            command.add("youtube:player_client=default,tv_simply,android_vr");
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

    private record ProcessResult(int exitCode, boolean timedOut, String output) {
    }

    private static ProcessResult runYtdlpCommand(List<String> command, long timeout, TimeUnit unit, String threadName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ACTIVE_DOWNLOAD_PROCESSES.add(process);

        StringBuilder output = new StringBuilder();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        }, threadName);
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout, unit);
        } finally {
            ACTIVE_DOWNLOAD_PROCESSES.remove(process);
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }

        try {
            readerThread.join(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new ProcessResult(finished ? process.exitValue() : -1, !finished, output.toString());
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

        ProcessResult result = runYtdlpCommand(command, 15, TimeUnit.SECONDS, "Collins-YTDLP-Meta");
        String output = result.output();
        StreamMeta meta = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(META_PREFIX)) {
                meta = parsePrintedStreamMeta(trimmed);
            }
        }

        if (result.timedOut()) {
            throw new Exception("yt-dlp metadata timeout");
        }
        if (result.exitCode() != 0) {
            String err = output.trim();
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

        ProcessResult result = runYtdlpCommand(command, 15, TimeUnit.SECONDS, "Collins-YTDLP-Direct");
        String directUrl = null;
        String output = result.output();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (directUrl == null && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                directUrl = trimmed;
            }
        }

        if (result.timedOut()) {
            throw new Exception("yt-dlp direct URL timeout");
        }
        if (result.exitCode() != 0 || directUrl == null || directUrl.isBlank()) {
            String err = output.trim();
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

    private static boolean looksLikeYouTubeLiveStreamUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("yt_live_broadcast")
            || lower.contains("/live/1/")
            || lower.contains("source/yt_live")
            || (lower.contains("manifest.googlevideo.com") && lower.contains("live/1"))
            || (lower.contains(".m3u8") && lower.contains("yt_live"));
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
        return findCachedFile(getYoutubeCacheDir(), videoId, preferredHeight);
    }

    /**
     * Generic version of {@link #findCachedYoutubeFile} that works against
     * any platform's cache directory. Looks for a file with the exact
     * {@code <id>.<height>p.<ext>} prefix first, then falls back (only for
     * the default quality) to the legacy {@code <id>.} prefix.
     */
    private static Path findCachedFile(Path cacheDir, String sourceId, int preferredHeight) {
        try {
            if (cacheDir == null || !Files.isDirectory(cacheDir)) return null;
            String exactPrefix = buildCachePrefix(sourceId, preferredHeight) + ".";
            try (var stream = Files.list(cacheDir)) {
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

            String legacyPrefix = sourceId + ".";
            try (var stream = Files.list(cacheDir)) {
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
        return readCachedDuration(getYoutubeCacheDir(), videoId);
    }

    private static long readCachedDuration(Path cacheDir, String sourceId) {
        try {
            Path path = cacheDir.resolve(sourceId + ".duration.txt");
            if (!Files.isRegularFile(path)) return 0L;
            return Long.parseLong(Files.readString(path).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void writeCachedDuration(String videoId, long durationMs) {
        writeCachedDuration(getYoutubeCacheDir(), videoId, durationMs);
    }

    private static void writeCachedDuration(Path cacheDir, String sourceId, long durationMs) {
        if (durationMs <= 0) return;
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(sourceId + ".duration.txt"), Long.toString(durationMs));
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
        deleteTempArtifacts(getYoutubeCacheDir(), cachePrefix);
    }

    private static void deleteTempArtifacts(Path cacheDir, String cachePrefix) {
        try {
            if (cacheDir == null || !Files.isDirectory(cacheDir)) return;
            try (var stream = Files.list(cacheDir)) {
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
        monitorDownloadProgress(getYoutubeCacheDir(), cachePrefix, totalEstimateBytes, running, sink);
    }

    private static void monitorDownloadProgress(Path cacheDir, String cachePrefix,
                                                AtomicLong totalEstimateBytes,
                                                AtomicBoolean running, VideoPlayer.FrameSink sink) {
        long lastBytes = -1L;
        while (running.get()) {
            try {
                long downloadedBytes = measureDownloadBytes(cacheDir, cachePrefix);
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

            ProcessResult result = runYtdlpCommand(command, 20, TimeUnit.SECONDS, "Collins-YTDLP-Size");
            long totalBytes = 0L;
            if (!result.timedOut() && result.exitCode() == 0) {
                for (String line : result.output().split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("CollinsYTMeta:")) {
                        continue;
                    }
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
            return totalBytes;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long measureYoutubeDownloadBytes(String cachePrefix) {
        return measureDownloadBytes(getYoutubeCacheDir(), cachePrefix);
    }

    private static long measureDownloadBytes(Path cacheDir, String cachePrefix) {
        long total = 0L;
        try {
            if (cacheDir == null || !Files.isDirectory(cacheDir)) return 0L;
            try (var stream = Files.list(cacheDir)) {
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
        String name = ytdlpBinaryName();
        try {
            return getToolsDir().resolve(name);
        } catch (Exception e) {
            return Path.of("collins-tools", name);
        }
    }

    private static Path getFfmpegPath() {
        String name = ffmpegBinaryName();
        try {
            return getToolsDir().resolve(name);
        } catch (Exception e) {
            return Path.of("collins-tools", name);
        }
    }

    private static boolean extractFfmpegFromZip(Path zipFile, Path ffmpegTarget) throws Exception {
        String wanted = ffmpegBinaryName();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                if (name.endsWith("/" + wanted) || name.endsWith("\\" + wanted) || name.equals(wanted)) {
                    Files.copy(zis, ffmpegTarget, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
                zis.closeEntry();
            }
        }
        return false;
    }

    /**
     * Extract {@code ffmpeg} from a {@code .tar.xz} archive by shelling
     * out to the system {@code tar}. Every Linux / macOS host has a
     * {@code tar} that understands {@code -J} (xz), so this avoids
     * pulling in Apache Commons Compress just for the bootstrap path.
     */
    private static boolean extractFfmpegFromTarXz(Path archive, Path ffmpegTarget) {
        Path stage = null;
        try {
            stage = Files.createTempDirectory(getToolsDir(), "ffmpeg-stage-");
            ProcessBuilder pb = new ProcessBuilder("tar", "-xJf", archive.toAbsolutePath().toString(),
                "-C", stage.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[8 * 1024];
                while (in.read(buf) >= 0) { /* drain */ }
            }
            int rc = p.waitFor();
            if (rc != 0) {
                dbg("tar exited with code " + rc);
                return false;
            }
            String wanted = ffmpegBinaryName();
            Path[] found = new Path[] { null };
            try (var stream = Files.walk(stage)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    if (found[0] == null && path.getFileName().toString().equals(wanted)) {
                        found[0] = path;
                    }
                });
            }
            if (found[0] == null) return false;
            Files.copy(found[0], ffmpegTarget, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            dbg("tar extract error: " + e.getMessage());
            return false;
        } finally {
            if (stage != null) {
                try (var stream = Files.walk(stage)) {
                    stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            }
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
        return getPlatformCacheDir(Platform.YOUTUBE);
    }

    /**
     * Returns the on-disk cache directory for a given platform's downloaded
     * VOD files. All platforms share the same {@code collins-cache/} root;
     * each gets its own subdirectory named after {@link Platform#cacheSubdir}
     * so files don't collide and we can delete one platform's cache
     * independently.
     */
    private static Path getPlatformCacheDir(Platform platform) {
        String sub = platform != null ? platform.cacheSubdir : "youtube";
        try {
            return FabricLoader.getInstance().getGameDir().resolve("collins-cache").resolve(sub);
        } catch (Exception e) {
            return Path.of("collins-cache", sub);
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
            ProcessResult result = runYtdlpCommand(List.of(ytdlp.toString(), "--version"), 5, TimeUnit.SECONDS, "Collins-YTDLP-Version");
            if (result.timedOut()) return "timeout";
            String version = result.output().lines().findFirst().orElse(null);
            return version != null ? version.trim() : "unknown";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    public static boolean updateYtdlp() {
        if (!ytdlpAvailable) return false;

        try {
            Path ytdlp = getYtdlpPath();
            ProcessResult result = runYtdlpCommand(List.of(ytdlp.toString(), "-U"), 120, TimeUnit.SECONDS, "Collins-YTDLP-Update");
            return !result.timedOut() && result.exitCode() == 0;
        } catch (Exception e) {
            dbg("updateYtdlp: error " + e.getMessage());
            return false;
        }
    }
}
