package org.sawiq.collins.fabric.client.video;

import java.util.List;

public final class YouTubeQuality {

    public static final int AUTO = 0;
    public static final int DEFAULT = 720;
    private static final List<Integer> ALLOWED = List.of(AUTO, 360, 480, 720, 1080, 1440, 2160);

    private YouTubeQuality() {
    }

    public static int sanitize(int value, int fallback) {
        if (ALLOWED.contains(value)) {
            return value;
        }
        return ALLOWED.contains(fallback) ? fallback : DEFAULT;
    }

    public static Integer parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim().toLowerCase();
        if (normalized.equals("auto")) {
            return AUTO;
        }
        if (normalized.endsWith("p")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        try {
            int value = Integer.parseInt(normalized);
            return ALLOWED.contains(value) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String display(int value) {
        int sanitized = sanitize(value, DEFAULT);
        return sanitized == AUTO ? "auto" : sanitized + "p";
    }

    public static List<String> options() {
        return List.of("auto", "360", "480", "720", "1080", "1440", "2160");
    }

    public static int resolveEffectiveHeight(int blocksH, int serverQuality, int clientQuality) {
        int autoHeight = resolveAutoHeight(blocksH);
        int effective = autoHeight;

        int sanitizedServer = sanitize(serverQuality, DEFAULT);
        if (sanitizedServer > AUTO) {
            effective = Math.min(effective, sanitizedServer);
        }

        int sanitizedClient = sanitize(clientQuality, AUTO);
        if (sanitizedClient > AUTO) {
            effective = Math.min(effective, sanitizedClient);
        }

        return Math.max(360, Math.min(2160, effective));
    }

    private static int resolveAutoHeight(int blocksH) {
        int estimatedHeight = Math.max(1, blocksH) * VideoConfig.PX_PER_BLOCK;
        return Math.max(DEFAULT, Math.min(2160, estimatedHeight));
    }
}
