package org.sawiq.collins.paper.util;

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
}
