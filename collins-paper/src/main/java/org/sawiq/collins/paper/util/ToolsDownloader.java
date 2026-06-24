package org.sawiq.collins.paper.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Cross-platform downloader for the {@code yt-dlp} / {@code ffprobe}
 * sidecars Collins relies on. Resolves the correct binary name and
 * download URL for the host OS / arch, drops the result into the
 * {@code tools/} directory, and on POSIX systems sets the executable
 * bit so {@link ProcessBuilder} won't fail with {@code error=13
 * Permission denied}.
 */
public final class ToolsDownloader {

    private enum Os { WINDOWS, LINUX, MACOS, OTHER }

    private enum Arch { X64, ARM64, OTHER }

    private static final Os HOST_OS = detectOs();
    private static final Arch HOST_ARCH = detectArch();
    private static final boolean POSIX = HOST_OS == Os.LINUX || HOST_OS == Os.MACOS;

    private static Logger logger;
    private static Path toolsDir;
    private static volatile boolean downloading = false;

    public static void init(Logger log, Path pluginDataFolder) {
        logger = log;
        toolsDir = pluginDataFolder.resolve("tools");
    }

    public static String getYtdlpPath() {
        Path p = toolsDir.resolve(ytdlpBinaryName());
        if (Files.isRegularFile(p)) {
            ensureExecutable(p);
            return p.toAbsolutePath().toString();
        }
        return "yt-dlp";
    }

    public static String getFfprobePath() {
        Path p = toolsDir.resolve(ffprobeBinaryName());
        if (Files.isRegularFile(p)) {
            ensureExecutable(p);
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

        if (HOST_OS == Os.OTHER) {
            log("Unsupported OS for auto-download (" + System.getProperty("os.name")
                + "). Place yt-dlp and ffprobe on PATH manually.");
            return;
        }

        Path ytdlp = toolsDir.resolve(ytdlpBinaryName());
        if (!Files.isRegularFile(ytdlp)) {
            String url = ytdlpUrl();
            if (url == null) {
                log("No yt-dlp build available for " + HOST_OS + "/" + HOST_ARCH
                    + "; install yt-dlp manually and put it on PATH.");
            } else {
                log("yt-dlp not found, downloading for " + HOST_OS + "/" + HOST_ARCH + "...");
                if (downloadFile(url, ytdlp)) {
                    ensureExecutable(ytdlp);
                }
            }
        } else {
            log("yt-dlp found: " + ytdlp);
            ensureExecutable(ytdlp);
        }

        Path ffprobe = toolsDir.resolve(ffprobeBinaryName());
        if (!Files.isRegularFile(ffprobe)) {
            log("ffprobe not found, downloading FFmpeg...");
            downloadFfmpeg(ffprobe);
        } else {
            log("ffprobe found: " + ffprobe);
            ensureExecutable(ffprobe);
        }
    }

    private static boolean downloadFile(String urlStr, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        HttpURLConnection conn = null;
        try {
            Files.deleteIfExists(tmp);

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Collins-Paper-Plugin");

            int code = conn.getResponseCode();
            if (code != 200) {
                log("Download failed, HTTP " + code + ": " + urlStr);
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

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log("Downloaded: " + target.getFileName());
            return true;

        } catch (Exception e) {
            log("Download error: " + e.getMessage());
            // Drop the half-written .tmp so a retry starts clean.
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean downloadFfmpeg(Path ffprobeTarget) {
        if (HOST_OS == Os.MACOS) {
            // BtbN ships no macOS builds, so the generic archive path below
            // would 404. Martin Riedl's static builds cover both Apple
            // Silicon (arm64) and Intel (amd64) as single-binary zips, one
            // per tool, so we fetch ffprobe and ffmpeg separately.
            boolean ok = downloadSingleBinaryZip(macosToolUrl("ffprobe"), ffprobeTarget, "ffprobe");
            Path ffmpeg = toolsDir.resolve(ffmpegBinaryName());
            if (!Files.isRegularFile(ffmpeg)) {
                downloadSingleBinaryZip(macosToolUrl("ffmpeg"), ffmpeg, "ffmpeg");
            } else {
                ensureExecutable(ffmpeg);
            }
            if (ok) {
                log("ffprobe extracted successfully");
            } else {
                log("ffprobe download failed; install ffmpeg via Homebrew (brew install ffmpeg) and put it on PATH.");
            }
            return ok;
        }

        String archiveUrl = ffmpegArchiveUrl();
        if (archiveUrl == null) {
            log("No prebuilt ffmpeg available for " + HOST_OS + "/" + HOST_ARCH
                + "; install ffmpeg/ffprobe manually and put them on PATH.");
            return false;
        }

        boolean isZip = archiveUrl.endsWith(".zip");
        String suffix = isZip ? ".zip.tmp" : ".tar.xz.tmp";
        Path archiveTmp = toolsDir.resolve("ffmpeg" + suffix);

        HttpURLConnection conn = null;
        try {
            Files.deleteIfExists(archiveTmp);

            URL url = new URL(archiveUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Collins-Paper-Plugin");

            int code = conn.getResponseCode();
            if (code != 200) {
                log("FFmpeg download failed, HTTP " + code);
                return false;
            }

            long contentLength = conn.getContentLengthLong();
            log("Downloading FFmpeg " + (contentLength > 0 ? (contentLength / 1024 / 1024) + " MB" : "..."));

            try (InputStream in = conn.getInputStream();
                 var out = Files.newOutputStream(archiveTmp)) {

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
            // Disconnect now so the socket is released BEFORE the
            // potentially-slow archive extraction; keeping the
            // connection alive while we shell out to `tar` does
            // nothing useful and pins a system file descriptor.
            try { conn.disconnect(); } catch (Exception ignored) {}
            conn = null;

            log("Extracting ffprobe...");
            boolean extracted = isZip
                ? extractFromZip(archiveTmp, ffprobeTarget)
                : extractFromTarXz(archiveTmp, ffprobeTarget);

            Files.deleteIfExists(archiveTmp);

            if (extracted) {
                log("ffprobe extracted successfully");
                ensureExecutable(ffprobeTarget);
                Path ffmpeg = toolsDir.resolve(ffmpegBinaryName());
                if (Files.isRegularFile(ffmpeg)) {
                    ensureExecutable(ffmpeg);
                }
                return true;
            } else {
                log("ffprobe not found in archive!");
                return false;
            }

        } catch (Exception e) {
            log("FFmpeg download/extract error: " + e.getMessage());
            try { Files.deleteIfExists(archiveTmp); } catch (Exception ignored) {}
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean extractFromZip(Path zipFile, Path ffprobeTarget) throws IOException {
        boolean extracted = false;
        String ffprobeName = ffprobeBinaryName();
        String ffmpegName = ffmpegBinaryName();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                if (name.endsWith("/" + ffprobeName) || name.endsWith("\\" + ffprobeName) || name.equals(ffprobeName)) {
                    log("Found: " + name);
                    Files.copy(zis, ffprobeTarget, StandardCopyOption.REPLACE_EXISTING);
                    extracted = true;
                } else if (name.endsWith("/" + ffmpegName) || name.endsWith("\\" + ffmpegName) || name.equals(ffmpegName)) {
                    Path ffmpeg = toolsDir.resolve(ffmpegName);
                    if (!Files.exists(ffmpeg)) {
                        Files.copy(zis, ffmpeg, StandardCopyOption.REPLACE_EXISTING);
                        log("Also extracted: " + ffmpegName);
                    }
                }
                zis.closeEntry();
            }
        }
        return extracted;
    }

    /**
     * Extract {@code ffprobe} (and {@code ffmpeg} if present) from a
     * {@code .tar.xz} archive by shelling out to the system {@code tar}.
     * Every Linux / macOS box has a {@code tar} that understands
     * {@code -J} (xz), so this avoids pulling in Apache Commons Compress
     * just for the bootstrap path.
     */
    private static boolean extractFromTarXz(Path archive, Path ffprobeTarget) {
        Path stage = null;
        try {
            stage = Files.createTempDirectory(toolsDir, "ffmpeg-stage-");
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
                log("tar exited with code " + rc + " while extracting " + archive.getFileName());
                return false;
            }

            String ffprobeName = ffprobeBinaryName();
            String ffmpegName = ffmpegBinaryName();
            Path[] found = new Path[] { null, null };
            try (var stream = Files.walk(stage)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String fileName = path.getFileName().toString();
                    if (fileName.equals(ffprobeName) && found[0] == null) found[0] = path;
                    else if (fileName.equals(ffmpegName) && found[1] == null) found[1] = path;
                });
            }

            if (found[0] == null) return false;
            Files.copy(found[0], ffprobeTarget, StandardCopyOption.REPLACE_EXISTING);
            log("Found: " + found[0]);
            if (found[1] != null) {
                Path ffmpeg = toolsDir.resolve(ffmpegName);
                if (!Files.exists(ffmpeg)) {
                    Files.copy(found[1], ffmpeg, StandardCopyOption.REPLACE_EXISTING);
                    log("Also extracted: " + ffmpegName);
                }
            }
            return true;
        } catch (Exception e) {
            log("tar extract error: " + e.getMessage());
            return false;
        } finally {
            if (stage != null) deleteRecursive(stage);
        }
    }

    private static void deleteRecursive(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /**
     * Mark {@code path} as executable on POSIX systems. Without this, the
     * downloaded yt-dlp / ffprobe binary is created with the JVM's default
     * 644 permissions and {@link ProcessBuilder#start()} fails with
     * {@code IOException: Cannot run program ...: error=13, Permission
     * denied} - the exact symptom users hit on Linux servers.
     */
    private static void ensureExecutable(Path path) {
        if (!POSIX) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception e) {
            // Fall back to the boolean API so we at least try to set the
            // owner-execute bit on filesystems where POSIX views aren't
            // available (e.g. some FUSE mounts).
            try {
                path.toFile().setExecutable(true, false);
            } catch (Exception ignored) {}
        }
    }

    private static String ytdlpBinaryName() {
        return HOST_OS == Os.WINDOWS ? "yt-dlp.exe" : "yt-dlp";
    }

    private static String ffprobeBinaryName() {
        return HOST_OS == Os.WINDOWS ? "ffprobe.exe" : "ffprobe";
    }

    private static String ffmpegBinaryName() {
        return HOST_OS == Os.WINDOWS ? "ffmpeg.exe" : "ffmpeg";
    }

    private static String ytdlpUrl() {
        String base = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
        return switch (HOST_OS) {
            case WINDOWS -> base + "yt-dlp.exe";
            case LINUX -> base + (HOST_ARCH == Arch.ARM64 ? "yt-dlp_linux_aarch64" : "yt-dlp_linux");
            case MACOS -> base + "yt-dlp_macos";
            case OTHER -> null;
        };
    }

    /**
     * BtbN ships builds for Windows and Linux (x64 + arm64) only. macOS is
     * handled separately in {@link #downloadFfmpeg} via Martin Riedl's
     * static single-binary builds (arm64 + amd64), so this method is never
     * called for macOS.
     */
    private static String ffmpegArchiveUrl() {
        String base = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-";
        return switch (HOST_OS) {
            case WINDOWS -> base + "win64-gpl.zip";
            case LINUX -> base + (HOST_ARCH == Arch.ARM64 ? "linuxarm64-gpl.tar.xz" : "linux64-gpl.tar.xz");
            case MACOS, OTHER -> null;
        };
    }

    /** Single-binary zip download URL for macOS (Martin Riedl static builds). */
    private static String macosToolUrl(String tool) {
        return "https://ffmpeg.martin-riedl.de/redirect/latest/macos/"
            + (HOST_ARCH == Arch.ARM64 ? "arm64" : "amd64") + "/release/" + tool + ".zip";
    }

    /**
     * Downloads a zip that contains exactly one binary (Riedl macOS builds)
     * and extracts it to {@code target}. Returns true on success.
     */
    private static boolean downloadSingleBinaryZip(String url, Path target, String binaryName) {
        Path zipTmp = toolsDir.resolve(binaryName + ".zip.tmp");
        try {
            if (!downloadFile(url, zipTmp)) {
                return false;
            }
            boolean extracted = false;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipTmp))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) { zis.closeEntry(); continue; }
                    String name = entry.getName();
                    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                    String base = slash >= 0 ? name.substring(slash + 1) : name;
                    if (base.equals(binaryName)) {
                        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                        break;
                    }
                    zis.closeEntry();
                }
            }
            if (extracted) {
                ensureExecutable(target);
                log("Downloaded and extracted: " + target.getFileName());
            } else {
                log(binaryName + " not found inside " + url);
            }
            return extracted;
        } catch (Exception e) {
            log("Download/extract error for " + binaryName + ": " + e.getMessage());
            return false;
        } finally {
            try { Files.deleteIfExists(zipTmp); } catch (Exception ignored) {}
        }
    }

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

    private static void log(String msg) {
        if (logger != null) {
            logger.info("[ToolsDownloader] " + msg);
        } else {
            System.out.println("[Collins-ToolsDownloader] " + msg);
        }
    }
}
