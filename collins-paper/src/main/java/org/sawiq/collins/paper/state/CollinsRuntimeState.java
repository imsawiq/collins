package org.sawiq.collins.paper.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CollinsRuntimeState {
    private static final long END_GRACE_MS = 250L;

    public volatile float globalVolume = 1.0f;
    public volatile int hearRadius = 100;

    public static final class Playback {
        public volatile long startEpochMs = 0; // когда "пошло"
        public volatile long basePosMs = 0;    // накопленная позиция (для resume)
        public volatile long durationMs = 0;   // длительность видео
    }

    private final Map<String, Playback> playback = new ConcurrentHashMap<>();

    public Playback get(String screenName) {
        return playback.computeIfAbsent(screenName.toLowerCase(), k -> new Playback());
    }

    public void resetPlayback(String screenName) {
        stopPlayback(screenName, false);
    }

    public void stopPlayback(String screenName) {
        stopPlayback(screenName, true);
    }

    public void stopPlayback(String screenName, boolean keepDuration) {
        Playback p = get(screenName);
        p.startEpochMs = 0;
        p.basePosMs = 0;
        if (!keepDuration) {
            p.durationMs = 0;
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
            p.durationMs = 0;
        }
    }

    public void setDurationFromServer(String screenName, long durationMs) {
        if (durationMs > 0) {
            get(screenName).durationMs = durationMs;
        }
    }

    /** Возвращает текущую позицию видео в миллисекундах */
    public long getCurrentPosMs(String screenName) {
        Playback p = get(screenName);
        if (p.startEpochMs <= 0) return p.basePosMs;
        return p.basePosMs + (System.currentTimeMillis() - p.startEpochMs);
    }

    public boolean isVideoEnded(String screenName) {
        Playback p = get(screenName);
        if (p.durationMs <= 0) return false;
        if (p.startEpochMs <= 0) return false;
        long currentPos = getCurrentPosMs(screenName);
        return currentPos >= Math.max(0L, p.durationMs - END_GRACE_MS);
    }

    public long getDurationMs(String screenName) {
        return get(screenName).durationMs;
    }
}
