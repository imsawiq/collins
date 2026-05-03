package org.sawiq.collins.paper.util;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Автозагрузка ffprobe/yt-dlp в plugins/collins-paper/tools/.
 *
 * <p>Cross-platform: detects OS/arch and pulls the correct yt-dlp binary.
 * On *nix the executable bit is applied after the file lands. On Windows
 * we additionally extract ffprobe.exe from the BtbN FFmpeg build; on Linux
 * and macOS we expect ffprobe from the system package manager (apt/brew)
 * and only fall back to it for VK-style edge cases — yt-dlp -j alone
 * resolves duration for 95% of URLs.
 */
public final class ToolsDownloader {

    private enum Os { WINDOWS, LINUX, MACOS, UNKNOWN }
    private enum Arch { X64, ARM64, UNKNOWN }

    private static final Os OS = detectOs();
    private static final Arch ARCH = detectArch();

    private static final String YTDLP_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String FFMPEG_WIN_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    private static Logger logger;
    private static Path toolsDir;
    private static volatile boolean downloading = false;

    private static Os detectOs() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return Os.WINDOWS;
        if (n.contains("mac") || n.contains("darwin")) return Os.MACOS;
        if (n.contains("nux") || n.contains("nix") || n.contains("aix")) return Os.LINUX;
        return Os.UNKNOWN;
    }

    private static Arch detectArch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (a.contains("aarch64") || a.contains("arm64")) return Arch.ARM64;
        if (a.contains("amd64") || a.contains("x86_64") || a.equals("x64")) return Arch.X64;
        return Arch.UNKNOWN;
    }

    private static String ytdlpFileName() {
        return OS == Os.WINDOWS ? "yt-dlp.exe" : "yt-dlp";
    }

    /**
     * Returns the upstream asset name from yt-dlp releases that matches the
     * current OS/arch. Linux x64 uses the plain {@code yt-dlp} static build
     * (PyInstaller, no Python required).
     */
    private static String ytdlpAssetName() {
        return switch (OS) {
            case WINDOWS -> "yt-dlp.exe";
            case MACOS -> "yt-dlp_macos";
            case LINUX -> ARCH == Arch.ARM64 ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
            default -> "yt-dlp";
        };
    }

    private static String ffprobeFileName() {
        return OS == Os.WINDOWS ? "ffprobe.exe" : "ffprobe";
    }

    public static void init(Logger log, Path pluginDataFolder) {
        logger = log;
        toolsDir = pluginDataFolder.resolve("tools");
    }

    public static String getYtdlpPath() {
        Path p = toolsDir.resolve(ytdlpFileName());
        if (Files.isRegularFile(p)) {
            return p.toAbsolutePath().toString();
        }
        return "yt-dlp";
    }

    public static String getFfprobePath() {
        Path p = toolsDir.resolve(ffprobeFileName());
        if (Files.isRegularFile(p)) {
            return p.toAbsolutePath().toString();
        }
        return "ffprobe";
    }

    public static void ensureToolsAsync() {
        if (downloading) return;

        Thread t = new Thread(() -> {
            downloading = true;
            try {
                ensureTools();
            } finally {
                downloading = false;
            }
        }, "Collins-ToolsDownloader");
        t.setDaemon(true);
        t.start();
    }

    public static void ensureTools() {
        try {
            Files.createDirectories(toolsDir);
        } catch (Exception e) {
            log("Failed to create tools directory: " + e.getMessage());
            return;
        }

        log("Detected platform: " + OS + "/" + ARCH);

        Path ytdlp = toolsDir.resolve(ytdlpFileName());
        if (!Files.isRegularFile(ytdlp)) {
            String asset = ytdlpAssetName();
            log("yt-dlp not found, downloading " + asset + " ...");
            if (downloadFile(YTDLP_BASE + asset, ytdlp)) {
                makeExecutable(ytdlp);
            }
        } else {
            // Pre-existing binary may have been copied without +x bit
            // (common when extracting plugin archives on Linux as root).
            makeExecutable(ytdlp);
            log("yt-dlp found: " + ytdlp);
        }

        Path ffprobe = toolsDir.resolve(ffprobeFileName());
        if (!Files.isRegularFile(ffprobe)) {
            if (OS == Os.WINDOWS) {
                log("ffprobe not found, downloading FFmpeg...");
                downloadFfmpeg(ffprobe);
            } else {
                log("ffprobe not bundled on " + OS + "; install via system package manager if VK probing is needed (apt install ffmpeg / brew install ffmpeg). yt-dlp -j alone handles YouTube/RuTube duration probing.");
            }
        } else {
            makeExecutable(ffprobe);
            log("ffprobe found: " + ffprobe);
        }
    }

    private static void makeExecutable(Path p) {
        if (OS == Os.WINDOWS) return;
        try {
            File f = p.toFile();
            // Set +x for owner, group and others so server processes running
            // under different users (root vs. dedicated 'minecraft' user) can
            // execute the freshly downloaded binary without manual chmod.
            if (!f.canExecute()) {
                boolean ok = f.setExecutable(true, false);
                if (!ok) log("Failed to set +x on " + p);
            }
        } catch (Exception e) {
            log("setExecutable failed for " + p + ": " + e.getMessage());
        }
    }

    private static boolean downloadFile(String urlStr, Path target) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Collins-Paper-Plugin");

            int code = conn.getResponseCode();
            if (code != 200) {
                log("Download failed, HTTP " + code + ": " + urlStr);
                conn.disconnect();
                return false;
            }

            long contentLength = conn.getContentLengthLong();
            log("Downloading " + (contentLength > 0 ? (contentLength / 1024 / 1024) + " MB" : "..."));

            try (InputStream in = conn.getInputStream();
                 var out = Files.newOutputStream(tmp)) {
                
                byte[] buf = new byte[64 * 1024];
                long written = 0;
                int r;
                int lastPercent = 0;
                while ((r = in.read(buf)) >= 0) {
                    out.write(buf, 0, r);
                    written += r;
                    if (contentLength > 0) {
                        int percent = (int) (written * 100 / contentLength);
                        if (percent >= lastPercent + 10) {
                            log("Download progress: " + percent + "%");
                            lastPercent = percent;
                        }
                    }
                }
            }

            conn.disconnect();
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log("Downloaded: " + target.getFileName());
            return true;

        } catch (Exception e) {
            log("Download error: " + e.getMessage());
            return false;
        }
    }

    private static boolean downloadFfmpeg(Path ffprobeTarget) {
        try {
            Path zipTmp = toolsDir.resolve("ffmpeg.zip.tmp");
            Files.deleteIfExists(zipTmp);

            URL url = new URL(FFMPEG_WIN_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000); // 5 минут для большого файла
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Collins-Paper-Plugin");

            int code = conn.getResponseCode();
            if (code != 200) {
                log("FFmpeg download failed, HTTP " + code);
                conn.disconnect();
                return false;
            }

            long contentLength = conn.getContentLengthLong();
            log("Downloading FFmpeg " + (contentLength > 0 ? (contentLength / 1024 / 1024) + " MB" : "..."));

            try (InputStream in = conn.getInputStream();
                 var out = Files.newOutputStream(zipTmp)) {
                
                byte[] buf = new byte[64 * 1024];
                long written = 0;
                int r;
                int lastPercent = 0;
                while ((r = in.read(buf)) >= 0) {
                    out.write(buf, 0, r);
                    written += r;
                    if (contentLength > 0) {
                        int percent = (int) (written * 100 / contentLength);
                        if (percent >= lastPercent + 10) {
                            log("FFmpeg download: " + percent + "%");
                            lastPercent = percent;
                        }
                    }
                }
            }
            conn.disconnect();

            log("Extracting ffprobe.exe...");
            boolean extracted = false;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipTmp))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.endsWith("ffprobe.exe") && !entry.isDirectory()) {
                        log("Found: " + name);
                        Files.copy(zis, ffprobeTarget, StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                    }
                    if (name.endsWith("ffmpeg.exe") && !entry.isDirectory()) {
                        Path ffmpeg = toolsDir.resolve("ffmpeg.exe");
                        if (!Files.exists(ffmpeg)) {
                            Files.copy(zis, ffmpeg, StandardCopyOption.REPLACE_EXISTING);
                            log("Also extracted: ffmpeg.exe");
                        }
                    }
                    zis.closeEntry();
                }
            }

            // Windows .exe archives don't need +x but call it anyway in case
            // the plugin is ever run under WSL or a Linux-flavoured JVM that
            // mounts the path differently.
            if (extracted) makeExecutable(ffprobeTarget);

            Files.deleteIfExists(zipTmp);

            if (extracted) {
                log("ffprobe.exe extracted successfully");
                return true;
            } else {
                log("ffprobe.exe not found in archive!");
                return false;
            }

        } catch (Exception e) {
            log("FFmpeg download/extract error: " + e.getMessage());
            return false;
        }
    }

    private static void log(String msg) {
        if (logger != null) {
            logger.info("[ToolsDownloader] " + msg);
        } else {
            System.out.println("[Collins-ToolsDownloader] " + msg);
        }
    }
}
