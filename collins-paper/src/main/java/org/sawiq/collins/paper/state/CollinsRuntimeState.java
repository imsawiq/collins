package org.sawiq.collins.paper.state;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class CollinsRuntimeState {
    private static final long END_GRACE_MS = 250L;

    public volatile float globalVolume = 1.0f;
    public volatile int hearRadius = 100;

    public static final class Playback {
        public volatile long startEpochMs = 0; // когда "пошло"
        public volatile long basePosMs = 0;    // накопленная позиция (для resume)
        public volatile long durationMs = 0;   // длительность видео
        public volatile String durationUrl = ""; // URL, for which durationMs was probed
    }

    private final Map<String, Playback> playback = new ConcurrentHashMap<>();

    public Playback get(String screenName) {
        return playback.computeIfAbsent(screenName.toLowerCase(), k -> new Playback());
    }

    public void resetPlayback(String screenName) {
        stopPlayback(screenName, false);
    }

    /**
     * Drops the playback bookkeeping for a deleted screen so the
     * internal map doesn't accumulate one entry per screen name that
     * has ever existed during the server's lifetime. Called from
     * {@code /collins remove}.
     */
    public void remove(String screenName) {
        if (screenName == null) return;
        playback.remove(screenName.toLowerCase());
    }

    public void stopPlayback(String screenName) {
        stopPlayback(screenName, true);
    }

    public void stopPlayback(String screenName, boolean keepDuration) {
        Playback p = get(screenName);
        p.startEpochMs = 0;
        p.basePosMs = 0;
        if (!keepDuration) {
            clearDuration(p);
        }
    }

    public void restartPlayback(String screenName) {
        restartPlayback(screenName, true);
    }

    public void restartPlayback(String screenName, boolean keepDuration) {
        Playback p = get(screenName);
        p.startEpochMs = System.currentTimeMillis();
        p.basePosMs = 0;
        if (!keepDuration) {
            clearDuration(p);
        }
    }

    public void setDurationFromServer(String screenName, String url, long durationMs) {
        if (durationMs <= 0) return;
        Playback p = get(screenName);
        p.durationMs = durationMs;
        p.durationUrl = normalizeUrl(url);
    }

    /** Возвращает текущую позицию видео в миллисекундах */
    public long getCurrentPosMs(String screenName) {
        Playback p = get(screenName);
        if (p.startEpochMs <= 0) return p.basePosMs;
        return p.basePosMs + (System.currentTimeMillis() - p.startEpochMs);
    }

    public boolean isVideoEnded(String screenName, String url) {
        Playback p = get(screenName);
        if (!durationMatches(p, url)) return false;
        if (p.startEpochMs <= 0) return false;
        long currentPos = getCurrentPosMs(screenName);
        return currentPos >= Math.max(0L, p.durationMs - END_GRACE_MS);
    }

    public long getDurationMs(String screenName, String url) {
        Playback p = get(screenName);
        return durationMatches(p, url) ? p.durationMs : 0L;
    }

    public void clearDuration(String screenName) {
        clearDuration(get(screenName));
    }

    private static void clearDuration(Playback p) {
        p.durationMs = 0;
        p.durationUrl = "";
    }

    private static boolean durationMatches(Playback p, String url) {
        if (p.durationMs <= 0) return false;
        String durationUrl = normalizeUrl(p.durationUrl);
        if (durationUrl.isEmpty()) return false;
        return Objects.equals(durationUrl, normalizeUrl(url));
    }

    private static String normalizeUrl(String url) {
        return url == null ? "" : url.trim();
    }
}
