package org.sawiq.collins.fabric.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.sawiq.collins.fabric.client.video.HwAccelBackend;
import org.sawiq.collins.fabric.client.video.YouTubeQuality;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CollinsClientConfig {

    public int localVolumePercent = 100;
    public boolean renderVideo = true;
    public boolean actionbarTimeline = true;
    public int youtubeMaxQuality = YouTubeQuality.DEFAULT;
    public boolean hardwareDecoding = true;
    public String hwAccelBackend = HwAccelBackend.detectDefault().name();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "collins.json";

    private static volatile CollinsClientConfig INSTANCE;

    private CollinsClientConfig() {
    }

    public static CollinsClientConfig get() {
        CollinsClientConfig cfg = INSTANCE;
        if (cfg == null) {
            cfg = load();
            INSTANCE = cfg;
        }
        return cfg;
    }

    public static CollinsClientConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            if (Files.exists(path)) {
                try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    CollinsClientConfig cfg = GSON.fromJson(r, CollinsClientConfig.class);
                    if (cfg != null) {
                        sanitize(cfg);
                        return cfg;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        CollinsClientConfig cfg = new CollinsClientConfig();
        sanitize(cfg);
        save(cfg);
        return cfg;
    }

    public static void save(CollinsClientConfig cfg) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        CollinsClientConfig cfg = get();
        sanitize(cfg);
        save(cfg);
    }

    public static void reload() {
        INSTANCE = load();
    }

    public float localVolumeMultiplier() {
        return Math.max(0f, Math.min(1f, localVolumePercent / 100f));
    }

    public HwAccelBackend resolvedHwAccelBackend() {
        if (!hardwareDecoding) return HwAccelBackend.NONE;
        return HwAccelBackend.fromString(hwAccelBackend);
    }

    private static void sanitize(CollinsClientConfig cfg) {
        if (cfg.localVolumePercent < 0) cfg.localVolumePercent = 0;
        if (cfg.localVolumePercent > 100) cfg.localVolumePercent = 100;
        cfg.youtubeMaxQuality = YouTubeQuality.sanitize(cfg.youtubeMaxQuality, YouTubeQuality.DEFAULT);
        if (cfg.hwAccelBackend == null || cfg.hwAccelBackend.isBlank()) {
            cfg.hwAccelBackend = HwAccelBackend.detectDefault().name();
        }
    }
}
