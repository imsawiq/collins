package org.sawiq.collins.fabric.client.video;

import java.util.Locale;

/**
 * Аппаратно-ускоренные декодеры, поддерживаемые FFmpeg.
 * <p>
 * Цель — снять декодирование H.264 / HEVC / VP9 / AV1 с CPU и перенести его
 * на выделенный видео-блок видеокарты (NVDEC у NVIDIA, VCE у AMD, QuickSync
 * у Intel, VideoToolbox на macOS).
 * <p>
 * При сбое аппаратного декодера (кодек не поддерживается данным GPU, нет
 * драйвера, ошибка инициализации) автоматически откатываемся на софт.
 */
public enum HwAccelBackend {

    /** Apple-платформы. Обрабатывает h264, hevc, vp9, prores. */
    VIDEOTOOLBOX("videotoolbox", "videotoolbox_vld"),

    /** Windows Direct3D 11 Video Acceleration. */
    D3D11VA("d3d11va", "d3d11"),

    /** Linux Video Acceleration API — работает на AMD / Intel. */
    VAAPI("vaapi", "vaapi"),

    /** NVIDIA CUDA / NVDEC — самый быстрый на картах NVIDIA. */
    CUDA("cuda", "cuda"),

    /** Только программный декод. */
    NONE(null, null);

    private final String ffmpegName;
    private final String hwOutputFormat;

    HwAccelBackend(String ffmpegName, String hwOutputFormat) {
        this.ffmpegName = ffmpegName;
        this.hwOutputFormat = hwOutputFormat;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public String hwOutputFormat() {
        return hwOutputFormat;
    }

    public boolean isHardware() {
        return ffmpegName != null;
    }

    /**
     * Выбирает бэкенд по умолчанию для текущей ОС. Выбираем наиболее
     * широкосовместимый вариант, а не самый быстрый: поток, который не
     * декодируется, хуже, чем поток, который декодируется чуть медленнее.
     */
    public static HwAccelBackend detectDefault() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return VIDEOTOOLBOX;
        if (os.contains("win")) return D3D11VA;
        if (os.contains("nux") || os.contains("nix")) return VAAPI;
        return NONE;
    }

    /**
     * Парсит строковое имя бэкенда из конфига, с откатом на detectDefault().
     */
    public static HwAccelBackend fromString(String name) {
        if (name == null || name.isBlank()) return detectDefault();
        try {
            return HwAccelBackend.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return detectDefault();
        }
    }

    // Маркеры в stderr FFmpeg, указывающие на сбой аппаратного декодера.
    private static final String[] HWACCEL_FAIL_MARKERS = {
        "hwaccel",
        "videotoolbox",
        "d3d11va",
        "vaapi",
        "cuda",
        "cuvid",
        "nvdec",
        "hardware acceleration",
        "failed setup for format",
        "no device available",
        "device creation failed",
        "no usable hwaccel",
        "decoder does not support",
        "scale_vt",
        "no such filter",
    };

    /**
     * Возвращает true, если stderr FFmpeg выглядит как ошибка инициализации
     * аппаратного декодера. Используется для автоматического отката на софт.
     */
    public static boolean looksLikeHwAccelFailure(String stderr) {
        if (stderr == null || stderr.isEmpty()) return false;
        String s = stderr.toLowerCase(Locale.ROOT);
        for (String marker : HWACCEL_FAIL_MARKERS) {
            if (s.contains(marker)) return true;
        }
        return false;
    }
}
