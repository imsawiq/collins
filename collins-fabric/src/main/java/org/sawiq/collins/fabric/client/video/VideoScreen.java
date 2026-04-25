package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.MemoryUtil;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.mixin.NativeImageAccessor;

import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VideoScreen implements VideoPlayer.FrameSink {

    private static final boolean DEBUG = false;

    private static final long OUT_OF_RADIUS_GRACE_MS = 15_000L;
    private static final long RADIUS_AUDIO_HYSTERESIS_MS = 250L;
    private static final long DRIFT_RESYNC_THRESHOLD_MS = 1_500L;
    private static final long DRIFT_RESYNC_COOLDOWN_MS = 4_000L;
    private static final long LIVE_VIDEO_STALL_RESTART_MS = 5_000L;
    private static final long LIVE_RESTART_COOLDOWN_MS = 6_000L;

    private ScreenState state;

    private Identifier texId;
    private NativeImageBackedTexture texture;

    private VideoPlayer player;

    private int texW, texH;

    private long nativePtr = 0;
    private IntBuffer nativeDst = null;

    private volatile boolean started = false;
    private String startedUrl = "";
    private float lastGain = -1f;

    private volatile long durationMs = 0;
    private volatile boolean liveStream = false;

    private volatile boolean ended = false;
    private volatile String endedUrl = "";
    private volatile long endedAtMs = 0; // Р’СЂРµРјСЏ РѕРєРѕРЅС‡Р°РЅРёСЏ РґР»СЏ Р°РІС‚РѕСЃРєСЂС‹С‚РёСЏ action bar

    private volatile long lastInRadiusAtMs = 0;
    private volatile boolean pausedByRadius = false;
    private volatile boolean mutedByRadius = false;
    private volatile long outOfRadiusSinceMs = 0;

    private volatile boolean displayFrozen = false;
    private volatile long displayFrozenPosMs = 0;
    private volatile long displayStartPosMs = 0;
    private volatile long displayWallStartNs = 0;
    private volatile long lastHardResyncAtMs = 0;

    // ===== РћС‡РµСЂРµРґСЊ РєР°РґСЂРѕРІ РґР»СЏ Р±СѓС„РµСЂРёР·Р°С†РёРё =====
    private record InitReq(int videoW, int videoH, int targetW, int targetH, double fps) {}
    private record FrameData(int[] abgr, int w, int h, long timestampUs) {}
    // РѕР¶РёРґР°РµРј ABGR (СЃРј. VideoPlayer), timestampUs = РїРѕР·РёС†РёСЏ РєР°РґСЂР° РІ РјРёРєСЂРѕСЃРµРєСѓРЅРґР°С…

    private final AtomicReference<InitReq> pendingInit = new AtomicReference<>(null);
    private final ConcurrentLinkedQueue<FrameData> frameQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger frameQueueSize = new AtomicInteger(0);
    private final AtomicBoolean pendingStop = new AtomicBoolean(false);
    
    // РїСѓР» СЃРІРѕР±РѕРґРЅС‹С… Р±СѓС„РµСЂРѕРІ - Р±СѓС„РµСЂС‹ РІРѕР·РІСЂР°С‰Р°СЋС‚СЃСЏ РїРѕСЃР»Рµ РїРѕРєР°Р·Р° РєР°РґСЂР°
    private final ConcurrentLinkedQueue<int[]> freeBuffers = new ConcurrentLinkedQueue<>();
    private static final int BUFFER_POOL_SIZE = 60; // РґРѕР»Р¶РµРЅ Р±С‹С‚СЊ > MAX_BUFFER_FRAMES
    
    // Р±СѓС„РµСЂРёР·Р°С†РёСЏ: Р¶РґС‘Рј РїРѕРєР° РЅР°РєРѕРїРёС‚СЃСЏ РјРёРЅРёРјСѓРј РєР°РґСЂРѕРІ РїРµСЂРµРґ РїРѕРєР°Р·РѕРј
    private static final int MIN_BUFFER_FRAMES_VOD = 15; // ~0.5 СЃРµРє РїСЂРё 30fps
    private static final int MIN_BUFFER_FRAMES_LIVE = 3;
    private static final int MAX_BUFFER_FRAMES = 45; // ~1.5 СЃРµРє РјР°РєСЃРёРјСѓРј
    private volatile boolean buffering = true; // С‚СЂСѓ РїРѕРєР° Р±СѓС„РµСЂРёР·СѓРµРј
    // ====================================================================

    // РџРµР№СЃРёРЅРі РЅР° render thread
    private double videoFps = 30.0;
    private volatile long playbackStartNs = 0; // РІСЂРµРјСЏ РЅР°С‡Р°Р»Р° РІРѕСЃРїСЂРѕРёР·РІРµРґРµРЅРёСЏ (РёР· РґРµРєРѕРґРµСЂР° РёР»Рё Р»РѕРєР°Р»СЊРЅРѕРµ)
    private long framesShown = 0;
    
    // Р”РёР°РіРЅРѕСЃС‚РёРєР°
    private long lastUploadLogNs = 0;
    private static final long UPLOAD_LOG_INTERVAL_NS = 2_000_000_000L;

    // Р”РёР°РіРЅРѕСЃС‚РёРєР° tick() - РёС‰РµРј РёСЃС‚РѕС‡РЅРёРє С„СЂРёР·РѕРІ
    private long lastTickNs = 0;
    private long maxTickGapUs = 0;
    private long maxTickDurationUs = 0;
    private long lastTickLogNs = 0;
    private static final long TICK_LOG_INTERVAL_NS = 2_000_000_000L;

    // РЎРѕСЃС‚РѕСЏРЅРёРµ СЃРєР°С‡РёРІР°РЅРёСЏ
    private volatile boolean downloading = false;
    private volatile int downloadPercent = 0;
    private volatile long downloadedMb = 0;
    private volatile long downloadTotalMb = 0;
    private volatile long downloadStartWallMs = 0;
    private volatile boolean resolvingYouTube = false;
    private volatile boolean downloadingYtdlp = false;
    private volatile boolean downloadingYoutubeVideo = false;
    private volatile boolean downloadProgressReceived = false;
    private volatile String downloadPhase = "";
    private volatile long lastVideoFrameAtMs = 0L;
    private volatile long playbackStartedAtMs = 0L;
    private volatile long lastLiveRestartAtMs = 0L;

    public VideoScreen(ScreenState state) {
        this.state = state;
    }

    public ScreenState state() { return state; }

    public void updateState(ScreenState newState) {
        ScreenState old = this.state;
        this.state = newState;

        if (old == null || newState == null) return;

        String ou = old.url();
        String nu = newState.url();
        
        if (!old.playing() && newState.playing()) {
            resetForNewVideo();
            return;
        }
        
        if (ou != null && nu != null && !ou.equals(nu) && newState.playing()) {
            resetForNewVideo();
            return;
        }

        if (old.youtubeQuality() != newState.youtubeQuality() && newState.playing()) {
            resetForNewVideo();
            return;
        }

        if (!started) return;
        if (!old.playing() || !newState.playing()) return;

        long db = Math.abs(newState.basePosMs() - old.basePosMs());
        long ds = Math.abs(newState.startEpochMs() - old.startEpochMs());

        if (db > 250L || ds > 250L) {
            if (ended && endedUrl.equals(newState.url()) && !newState.loop()) {
                return;
            }
            ended = false;
            endedUrl = "";
            endedAtMs = 0;
            if (player != null) {
                player.stop();
            }
            started = false;
            startedUrl = "";
        }
    }
    
    private void resetForNewVideo() {
        ended = false;
        endedUrl = "";
        endedAtMs = 0;
        durationMs = 0;
        liveStream = false;
        lastHardResyncAtMs = 0;
        clearCachedFileInfo();
        resetDownloadState();
        lastVideoFrameAtMs = 0L;
        playbackStartedAtMs = 0L;
        if (player != null) {
            player.stop();
        }
        started = false;
        startedUrl = "";
    }

    public boolean hasTexture() { return texture != null && texId != null; }

    public Identifier textureId() { return texId; }

    public void tickPlayback(Vec3d playerPos, int radiusBlocks, float globalVolume, long serverNowMs) {
        long tickStart = System.nanoTime();
        
        // РґРёР°РіРЅРѕСЃС‚РёРєР°: РІСЂРµРјСЏ РјРµР¶РґСѓ tick() Рё РІСЂРµРјСЏ РІРЅСѓС‚СЂРё tick()
        if (lastTickNs > 0) {
            long gapUs = (tickStart - lastTickNs) / 1000L;
            if (gapUs > maxTickGapUs) maxTickGapUs = gapUs;
        }
        
        // 1) РїСЂРёРјРµРЅСЏРµРј РІСЃС‘, С‡С‚Рѕ РїСЂРёС€Р»Рѕ РёР· РґРµРєРѕРґРµСЂР° (РўРћР›Р¬РљРћ С‚СѓС‚)
        applyPendingStop();
        applyPendingInit();

        CollinsClientConfig cfg = CollinsClientConfig.get();

        // 1.1) РµСЃР»Рё РІ РєРѕРЅС„РёРіРµ РІС‹РєР»СЋС‡РµРЅРѕ вЂ” РїРѕР»РЅРѕСЃС‚СЊСЋ РѕСЃС‚Р°РЅР°РІР»РёРІР°РµРј (Рё РІРёРґРµРѕ, Рё Р·РІСѓРє)
        if (!cfg.renderVideo) {
            stop();
            return;
        }

        // 2) СѓРїСЂР°РІР»РµРЅРёРµ РІРѕСЃРїСЂРѕРёР·РІРµРґРµРЅРёРµРј
        if (state.url() == null || state.url().isEmpty() || !state.playing()) {
            stop();
            return;
        }

        // 2.1) hear radius: РІРЅРµ СЂР°РґРёСѓСЃР° РїРѕР»РЅРѕСЃС‚СЊСЋ РѕС‚РєР»СЋС‡Р°РµРј (Рё РІРёРґРµРѕ, Рё Р·РІСѓРє)
        boolean inRadius = isInHearRadius(playerPos, radiusBlocks);
        long nowMs = System.currentTimeMillis();

        if (inRadius) {
            lastInRadiusAtMs = nowMs;
            pausedByRadius = false;
            outOfRadiusSinceMs = 0;

            if (mutedByRadius) {
                mutedByRadius = false;
                lastGain = -1f;
            }
        } else {
            pausedByRadius = true;

            if (outOfRadiusSinceMs == 0) outOfRadiusSinceMs = nowMs;

            if (player != null) {
                if (!mutedByRadius && (nowMs - outOfRadiusSinceMs) >= RADIUS_AUDIO_HYSTERESIS_MS) {
                    player.setGain(0f);
                    mutedByRadius = true;
                }
            }

            if (started && (nowMs - lastInRadiusAtMs) <= OUT_OF_RADIUS_GRACE_MS) {
                displayFrozen = true;
                displayFrozenPosMs = clampToDuration(currentVideoPosMs(serverNowMs));
                return;
            }

            stop();
            return;
        }

        long posMs = currentVideoPosMs(serverNowMs);
        if (!liveStream && !state.loop() && durationMs > 0 && posMs >= durationMs) {
            ended = true;
            endedUrl = state.url();
            if (endedAtMs == 0) endedAtMs = System.currentTimeMillis();
        }
        float gain = Math.max(0f, globalVolume) * Math.max(0f, state.volume()) * cfg.localVolumeMultiplier();

        if (player == null) player = new VideoPlayer(this);

        if (ended && endedUrl.equals(state.url())) {
            if (player != null && player.isRunning()) {
                player.stop();
            }
            if (player != null) {
                player.setGain(0f);
            }
            started = false;
            startedUrl = "";
            displayFrozen = false;
            displayFrozenPosMs = durationMs > 0 ? durationMs : clampToDuration(posMs);
            displayWallStartNs = 0;
            clearTexture();
            return;
        }

        if (shouldHardResync(serverNowMs)) {
            hardResync(posMs, gain);
            return;
        }

        if (shouldRestartLiveStream(nowMs)) {
            restartLiveStream(gain, nowMs);
            return;
        }

        if (!started || !startedUrl.equals(state.url())) {
            started = true;
            startedUrl = state.url();
            playbackStartedAtMs = nowMs;
            lastVideoFrameAtMs = 0L;
            ended = false;
            endedUrl = "";
            endedAtMs = 0;
            lastGain = gain;
            displayFrozen = true;
            displayFrozenPosMs = posMs;
            displayStartPosMs = posMs;
            displayWallStartNs = 0;
            player.start(state.url(), state.blocksW(), state.blocksH(), state.loop(), posMs, gain, effectiveYoutubeHeight());
            return;
        }

        if (Math.abs(gain - lastGain) > 0.001f) {
            lastGain = gain;
            player.setGain(gain);
        }

        // РґРёР°РіРЅРѕСЃС‚РёРєР°: Р»РѕРі РїРёРєРѕРІС‹С… Р·РЅР°С‡РµРЅРёР№ tick
        long tickEnd = System.nanoTime();
        long durationUs = (tickEnd - tickStart) / 1000L;
        if (durationUs > maxTickDurationUs) maxTickDurationUs = durationUs;
        lastTickNs = tickEnd;

        if (tickEnd - lastTickLogNs >= TICK_LOG_INTERVAL_NS) {
            lastTickLogNs = tickEnd;
            if (DEBUG) System.out.println("[Collins] tick peak: gap=" + maxTickGapUs + "us duration=" + maxTickDurationUs + "us");
            maxTickGapUs = 0;
            maxTickDurationUs = 0;
        }
    }

    public void renderPlayback() {
        if (!started) return;
        if (!CollinsClientConfig.get().renderVideo) return;
        if (pausedByRadius) return;
        uploadPendingFrameFast();
    }

    private boolean isInHearRadius(Vec3d playerPos, int radiusBlocks) {
        if (playerPos == null) return false;
        if (radiusBlocks <= 0) return true;

        double cx = (state.minX() + state.maxX() + 1) * 0.5;
        double cy = (state.minY() + state.maxY() + 1) * 0.5;
        double cz = (state.minZ() + state.maxZ() + 1) * 0.5;

        double dx = playerPos.x - cx;
        double dy = playerPos.y - cy;
        double dz = playerPos.z - cz;

        double r = (double) radiusBlocks;
        return (dx * dx + dy * dy + dz * dz) <= (r * r);
    }

    private void applyPendingStop() {
        if (!pendingStop.getAndSet(false)) return;

        boolean preserveEnded = ended && endedUrl.equals(state.url()) && !state.loop();

        started = false;
        startedUrl = "";
        lastGain = -1f;
        lastHardResyncAtMs = 0;
        liveStream = false;
        lastVideoFrameAtMs = 0L;
        playbackStartedAtMs = 0L;

        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;
        playbackStartNs = 0;
        framesShown = 0;
        lastUploadLogNs = 0;
        resetDownloadState();

        if (!preserveEnded) {
            displayFrozen = false;
            displayFrozenPosMs = 0;
            displayStartPosMs = 0;
            displayWallStartNs = 0;

            ended = false;
            endedUrl = "";
            endedAtMs = 0;
        }

        lastInRadiusAtMs = 0;
        pausedByRadius = false;
        mutedByRadius = false;
        outOfRadiusSinceMs = 0;
    }

    private void applyPendingInit() {
        InitReq req = pendingInit.getAndSet(null);
        if (req == null) return;

        int prevTexW = this.texW;
        int prevTexH = this.texH;
        this.texW = req.targetW();
        this.texH = req.targetH();
        this.videoFps = req.fps();

        if (texId == null) {
            texId = Identifier.of("collins", "screen/" + state.name().toLowerCase());
        }

        boolean sameTextureSize = texture != null && prevTexW == req.targetW() && prevTexH == req.targetH();
        if (!sameTextureSize && texture != null) {
            texture.close();
            texture = null;
        }

        if (texture == null) {
            texture = new NativeImageBackedTexture("collins:" + texId, texW, texH, true);
            MinecraftClient.getInstance().getTextureManager().registerTexture(texId, texture);
        }

        NativeImage imgForPtr = texture.getImage();
        if (imgForPtr != null) {
            nativePtr = ((NativeImageAccessor) (Object) imgForPtr).collins$getPointer();
            nativeDst = MemoryUtil.memIntBuffer(nativePtr, texW * texH);
        } else {
            nativePtr = 0;
            nativeDst = null;
        }

        if (!sameTextureSize) {
            NativeImage img = texture.getImage();
            if (img != null) {
                img.fillRect(0, 0, texW, texH, 0xFF000000);
            }
            texture.upload();
        }

        // РѕС‡РµСЂРµРґСЊ РєР°РґСЂРѕРІ Рё СЃР±СЂР°СЃС‹РІР°РµРј РїРµР№СЃРёРЅРі
        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;
        playbackStartNs = 0;
        framesShown = 0;
        lastUploadLogNs = 0;
        
        // РїСѓР» Р±СѓС„РµСЂРѕРІ
        freeBuffers.clear();
        int pixels = texW * texH;
        for (int i = 0; i < BUFFER_POOL_SIZE; i++) {
            freeBuffers.offer(new int[pixels]);
        }

        if (DEBUG) System.out.println("[Collins] initVideo " + texW + "x" + texH + " fps=" + videoFps + " pool=" + BUFFER_POOL_SIZE + " buffering...");
    }

    /**
     * Р‘РµСЂС‘Рј РєР°РґСЂ РёР· РѕС‡РµСЂРµРґРё СЃ РїРµР№СЃРёРЅРіРѕРј РїРѕ fps РІРёРґРµРѕ.
     * Р‘СѓС„РµСЂРёР·Р°С†РёСЏ: Р¶РґС‘Рј РїРѕРєР° РЅР°РєРѕРїРёС‚СЃСЏ РјРёРЅРёРјСѓРј РєР°РґСЂРѕРІ РїРµСЂРµРґ РїРѕРєР°Р·РѕРј.
     */
    private void uploadPendingFrameFast() {
        if (texture == null) return;

        int queueSize = frameQueueSize.get();
        
        // Р‘СѓС„РµСЂРёР·Р°С†РёСЏ:
        if (buffering) {
            long now = System.nanoTime();
            if (now - lastUploadLogNs >= UPLOAD_LOG_INTERVAL_NS) {
                lastUploadLogNs = now;
                if (DEBUG) System.out.println("[Collins] buffering... " + queueSize + "/" + requiredBufferFrames() + " frames");
            }
            if (queueSize < requiredBufferFrames()) {
                return; // РµС‰С‘ Р±СѓС„РµСЂРёР·СѓРµРј
            }
            buffering = false;
            if (playbackStartNs == 0) playbackStartNs = System.nanoTime();
            if (displayWallStartNs == 0) {
                displayWallStartNs = playbackStartNs;
                displayStartPosMs = displayFrozenPosMs;
                displayFrozen = false;
            }
            framesShown = 0;
            if (DEBUG) System.out.println("[Collins] buffering done, queue=" + queueSize + " frames, fps=" + videoFps);
        }

        if (playbackStartNs == 0) playbackStartNs = System.nanoTime();

        long now = System.nanoTime();
        long elapsedUs = (now - playbackStartNs) / 1000L;

        FrameData frame = frameQueue.peek();
        if (frame == null) return;

        FrameData chosen = null;
        while (true) {
            FrameData next = frameQueue.peek();
            if (next == null) break;
            if (next.timestampUs() > elapsedUs) break;

            chosen = frameQueue.poll();
            if (chosen == null) break;
            frameQueueSize.decrementAndGet();

            FrameData peekAfter = frameQueue.peek();
            if (peekAfter != null && peekAfter.timestampUs() <= elapsedUs) {
                freeBuffers.offer(chosen.abgr());
                chosen = null;
            }
        }

        if (chosen == null) return;
        frame = chosen;
        framesShown++;

        int w = frame.w();
        int h = frame.h();
        int[] abgr = frame.abgr();
        
        if (w != texW || h != texH) {
            // Р Р°Р·РјРµСЂ РЅРµ СЃРѕРІРїР°РґР°РµС‚ - РІРѕР·РІСЂР°С‰Р°РµРј Р±СѓС„РµСЂ РІ РїСѓР» Рё РїСЂРѕРїСѓСЃРєР°РµРј
            freeBuffers.offer(abgr);
            return;
        }

        IntBuffer dst = nativeDst;
        if (dst == null) {
            freeBuffers.offer(abgr);
            return;
        }
        int pixels = texW * texH;

        long copyStart = System.nanoTime();
        dst.position(0);
        dst.put(abgr, 0, pixels);

        long uploadStart = System.nanoTime();
        texture.upload();
        long end = System.nanoTime();
        
        // Р’РђР–РќРћ: РІРѕР·РІСЂР°С‰Р°РµРј Р±СѓС„РµСЂ РІ РїСѓР» РїРѕСЃР»Рµ РёСЃРїРѕР»СЊР·РѕРІР°РЅРёСЏ
        freeBuffers.offer(abgr);

        if (end - lastUploadLogNs >= UPLOAD_LOG_INTERVAL_NS) {
            lastUploadLogNs = end;
            long lagUs = elapsedUs - frame.timestampUs();
            if (DEBUG) System.out.println("[Collins] frame " + framesShown + " ts=" + (frame.timestampUs()/1000) + "ms lag=" + (lagUs/1000) + "ms queue=" + queueSize);
        }
    }

    private long currentVideoPosMs(long serverNowMs) {
        long base = Math.max(0L, state.basePosMs());
        if (liveStream) return 0L;
        if (serverNowMs <= 0 || state.startEpochMs() <= 0) return base;
        long pos = base + Math.max(0L, serverNowMs - state.startEpochMs());
        return clampToDuration(pos);
    }

    public long currentPosMs(long serverNowMs) {
        return currentVideoPosMs(serverNowMs);
    }

    public long currentPosMsForDisplay(long serverNowMs) {
        // Р’Рѕ РІСЂРµРјСЏ СЃРєР°С‡РёРІР°РЅРёСЏ РїРѕРєР°Р·С‹РІР°РµРј СЃРµСЂРІРµСЂРЅРѕРµ РІСЂРµРјСЏ (С‚Р°Р№РјР»Р°Р№РЅ РїСЂРѕРґРѕР»Р¶Р°РµС‚ РёРґС‚Рё)
        if (downloading) {
            return currentVideoPosMs(serverNowMs);
        }
        if (started && !ended && displayWallStartNs <= 0) {
            return currentVideoPosMs(serverNowMs);
        }
        if (displayFrozen) {
            return clampToDuration(Math.max(0L, displayFrozenPosMs));
        }
        long ws = displayWallStartNs;
        if (ws > 0) {
            long elapsedMs = Math.max(0L, (System.nanoTime() - ws) / 1_000_000L);
            return clampToDuration(Math.max(0L, displayStartPosMs + elapsedMs));
        }
        return currentVideoPosMs(serverNowMs);
    }

    private boolean shouldHardResync(long serverNowMs) {
        if (liveStream) return false;
        if (!started || ended || downloading || displayFrozen) return false;
        if (player == null || !player.isRunning()) return false;
        if (displayWallStartNs <= 0 || serverNowMs <= 0 || state.startEpochMs() <= 0) return false;

        long now = System.currentTimeMillis();
        if (now - lastHardResyncAtMs < DRIFT_RESYNC_COOLDOWN_MS) return false;

        long serverPosMs = currentVideoPosMs(serverNowMs);
        long localPosMs = currentPosMsForDisplay(serverNowMs);
        long driftMs = Math.abs(serverPosMs - localPosMs);
        return driftMs >= DRIFT_RESYNC_THRESHOLD_MS;
    }

    private int requiredBufferFrames() {
        return liveStream ? MIN_BUFFER_FRAMES_LIVE : MIN_BUFFER_FRAMES_VOD;
    }

    private boolean shouldRestartLiveStream(long nowMs) {
        if (!liveStream || !started || downloading || ended) return false;
        if (player == null || !player.isRunning()) return false;
        if ((nowMs - playbackStartedAtMs) < LIVE_VIDEO_STALL_RESTART_MS) return false;
        if ((nowMs - lastLiveRestartAtMs) < LIVE_RESTART_COOLDOWN_MS) return false;
        if (lastVideoFrameAtMs <= 0L) return true;
        return (nowMs - lastVideoFrameAtMs) >= LIVE_VIDEO_STALL_RESTART_MS;
    }

    private void restartLiveStream(float gain, long nowMs) {
        lastLiveRestartAtMs = nowMs;
        if (player != null) {
            player.stop();
        }
        started = false;
        startedUrl = "";
        displayFrozen = true;
        displayFrozenPosMs = 0L;
        displayStartPosMs = 0L;
        displayWallStartNs = 0L;
        lastGain = gain;
        lastVideoFrameAtMs = 0L;
        playbackStartedAtMs = nowMs;
    }

    private void hardResync(long posMs, float gain) {
        lastHardResyncAtMs = System.currentTimeMillis();
        if (player != null) {
            player.stop();
        }
        started = false;
        startedUrl = "";
        ended = false;
        endedUrl = "";
        endedAtMs = 0;
        displayFrozen = true;
        displayFrozenPosMs = clampToDuration(posMs);
        displayStartPosMs = displayFrozenPosMs;
        displayWallStartNs = 0;
        lastGain = gain;
    }

    private int effectiveYoutubeHeight() {
        CollinsClientConfig cfg = CollinsClientConfig.get();
        return YouTubeQuality.resolveEffectiveHeight(state.blocksH(), state.youtubeQuality(), cfg.youtubeMaxQuality);
    }

    public long durationMs() {
        return durationMs;
    }

    public boolean isLiveStream() {
        return liveStream;
    }

    public void stop() {
        if (player != null) player.stop();

        started = false;
        startedUrl = "";
        lastGain = -1f;

        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;

        playbackStartNs = 0;
        framesShown = 0;
        lastUploadLogNs = 0;

        displayFrozen = false;
        displayFrozenPosMs = 0;
        displayStartPosMs = 0;
        displayWallStartNs = 0;
        liveStream = false;
        lastVideoFrameAtMs = 0L;
        playbackStartedAtMs = 0L;
        resetDownloadState();

        mutedByRadius = false;
        outOfRadiusSinceMs = 0;
        
        clearTexture();
    }
    
    private void clearTexture() {
        if (texture != null) {
            try {
                texture.close();
            } catch (Exception ignored) {}
            texture = null;
        }
    }

    // ===== FrameSink: СЌС‚Рё РјРµС‚РѕРґС‹ РјРѕРіСѓС‚ РІС‹Р·С‹РІР°С‚СЊСЃСЏ РР— Р”Р•РљРћР”Р•Р -РџРћРўРћРљРђ =====

    @Override
    public void initVideo(int videoW, int videoH, int targetW, int targetH, double fps) {
        pendingInit.set(new InitReq(videoW, videoH, targetW, targetH, fps));
    }

    @Override
    public void onDuration(long durationMs) {
        long d = Math.max(0L, durationMs);
        // Р·Р°С‰РёС‚Р° РѕС‚ "РјСѓСЃРѕСЂРЅРѕР№" РґР»РёС‚РµР»СЊРЅРѕСЃС‚Рё (РёРЅРѕРіРґР° FFmpeg РѕС‚РґР°С‘С‚ Р°Р±СЃСѓСЂРґРЅС‹Рµ Р·РЅР°С‡РµРЅРёСЏ)
        long max = 12L * 60L * 60L * 1000L;
        if (d > max) d = 0L;
        this.durationMs = d;
    }

    @Override
    public void onLiveStatus(boolean live) {
        this.liveStream = live;
        if (live) {
            this.durationMs = 0L;
            this.ended = false;
            this.endedUrl = "";
            this.endedAtMs = 0L;
            this.lastVideoFrameAtMs = 0L;
        }
    }

    @Override
    public void onEnded(long durationMs) {
        long d = durationMs > 0 ? durationMs : this.durationMs;
        if (d > 0) this.durationMs = d;

        this.ended = true;
        this.endedUrl = startedUrl;
        this.endedAtMs = System.currentTimeMillis();

        this.displayFrozen = true;
        this.displayFrozenPosMs = this.durationMs;
        this.displayWallStartNs = 0;
        resetDownloadState();

        // РЎРµСЂРІРµСЂ СЃР°Рј РѕРїСЂРµРґРµР»СЏРµС‚ РѕРєРѕРЅС‡Р°РЅРёРµ РІРёРґРµРѕ РїРѕ РІСЂРµРјРµРЅРё (Р±РµР·РѕРїР°СЃРЅРµРµ С‡РµРј РєР»РёРµРЅС‚СЃРєРѕРµ СЃРѕРѕР±С‰РµРЅРёРµ)
    }

    @Override
    public void onFrame(int[] abgr, int w, int h, long timestampUs) {
        if (abgr == null) return;
        this.lastVideoFrameAtMs = System.currentTimeMillis();

        if (!CollinsClientConfig.get().renderVideo) {
            freeBuffers.offer(abgr);
            return;
        }
        
        // РћРіСЂР°РЅРёС‡РёРІР°РµРј СЂР°Р·РјРµСЂ РѕС‡РµСЂРµРґРё С‡С‚РѕР±С‹ РЅРµ СЃСЉРµСЃС‚СЊ РІСЃСЋ РїР°РјСЏС‚СЊ
        if (frameQueueSize.get() >= MAX_BUFFER_FRAMES) {
            // РћС‡РµСЂРµРґСЊ РїРѕР»РЅР° - РґРµРєРѕРґРµСЂ РґРѕР»Р¶РµРЅ Р¶РґР°С‚СЊ
            freeBuffers.offer(abgr);
            return;
        }
        
        frameQueue.offer(new FrameData(abgr, w, h, timestampUs));
        frameQueueSize.incrementAndGet();
    }

    @Override
    public void onStop() {
        pendingStop.set(true);
    }

    private long clampToDuration(long posMs) {
        long d = durationMs;
        if (d > 0) {
            return Math.min(Math.max(0L, posMs), d);
        }
        return Math.max(0L, posMs);
    }

    @Override
    public void onPlaybackClockStart(long wallStartNs) {
        this.playbackStartNs = wallStartNs;
        this.displayWallStartNs = wallStartNs;
        this.displayFrozen = false;

        // Р•СЃР»Рё Р±С‹Р»Рѕ СЃРєР°С‡РёРІР°РЅРёРµ, СѓС‡РёС‚С‹РІР°РµРј РІСЂРµРјСЏ РєРѕС‚РѕСЂРѕРµ РїСЂРѕС€Р»Рѕ
        if (downloadStartWallMs > 0) {
            long downloadDurationMs = System.currentTimeMillis() - downloadStartWallMs;
            this.displayStartPosMs = this.displayFrozenPosMs + downloadDurationMs;
            downloadStartWallMs = 0;
        } else {
            this.displayStartPosMs = this.displayFrozenPosMs;
        }

        resetDownloadState();
    }

    @Override
    public boolean canAcceptFrame() {
        return frameQueueSize.get() < MAX_BUFFER_FRAMES;
    }

    @Override
    public int[] borrowBuffer() {
        return freeBuffers.poll();
    }

    @Override
    public void returnBuffer(int[] buf) {
        if (buf != null) {
            freeBuffers.offer(buf);
        }
    }

    @Override
    public boolean isBufferReady() {
        // Р‘СѓС„РµСЂ РіРѕС‚РѕРІ РєРѕРіРґР° Р±СѓС„РµСЂРёР·Р°С†РёСЏ Р·Р°РєРѕРЅС‡РµРЅР°
        if (!CollinsClientConfig.get().renderVideo) return true;
        return !buffering;
    }

    @Override
    public void onDownloadStart(String message) {
        String phase = normalizeDownloadPhase(message);
        boolean samePhase = downloading && phase.equals(downloadPhase);

        this.downloading = true;
        if (!samePhase) {
            this.downloadPercent = 0;
            this.downloadedMb = 0;
            this.downloadTotalMb = 0;
            this.downloadProgressReceived = false;

            // Р—Р°РїРѕРјРёРЅР°РµРј РІСЂРµРјСЏ РЅР°С‡Р°Р»Р° СЃРєР°С‡РёРІР°РЅРёСЏ РґР»СЏ РєРѕСЂСЂРµРєС‚РЅРѕР№ СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёРё С‚Р°Р№РјР»Р°Р№РЅР°
            this.downloadStartWallMs = System.currentTimeMillis();
        }
        
        // Track YouTube-specific states
        if (message != null) {
            this.resolvingYouTube = message.contains("youtube");
            this.downloadingYtdlp = message.contains("ytdlp");
            this.downloadingYoutubeVideo = message.contains("youtube_downloading");
        } else {
            this.resolvingYouTube = false;
            this.downloadingYtdlp = false;
            this.downloadingYoutubeVideo = false;
        }
        this.downloadPhase = phase;
    }

    @Override
    public void onDownloadProgress(int percent, long downloadedMb, long totalMb) {
        this.downloadPercent = Math.max(this.downloadPercent, Math.max(0, percent));
        this.downloadedMb = Math.max(this.downloadedMb, Math.max(0L, downloadedMb));
        this.downloadTotalMb = Math.max(this.downloadTotalMb, Math.max(0L, totalMb));
        this.downloadProgressReceived = true;
    }

    @Override
    public void onDownloadComplete() {
        // Keep the last download status until playback clock starts.
    }

    // Р“РµС‚С‚РµСЂС‹ РґР»СЏ СЃРѕСЃС‚РѕСЏРЅРёСЏ СЃРєР°С‡РёРІР°РЅРёСЏ (РґР»СЏ РѕС‚РѕР±СЂР°Р¶РµРЅРёСЏ РІ HUD)
    public boolean isDownloading() { return downloading; }
    public int getDownloadPercent() { return downloadPercent; }
    public long getDownloadedMb() { return downloadedMb; }
    public long getDownloadTotalMb() { return downloadTotalMb; }
    public boolean isResolvingYouTube() { return resolvingYouTube; }
    public boolean isDownloadingYtdlp() { return downloadingYtdlp; }
    public boolean isDownloadingYoutubeVideo() { return downloadingYoutubeVideo; }
    public boolean hasDownloadProgressReceived() { return downloadProgressReceived; }

    // РРЅС„РѕСЂРјР°С†РёСЏ Рѕ РєСЌС€РёСЂРѕРІР°РЅРЅРѕРј С„Р°Р№Р»Рµ (РґР»СЏ РїСЂРµРґР»РѕР¶РµРЅРёСЏ СѓРґР°Р»РµРЅРёСЏ)
    private volatile String cachedFilePath = null;
    private volatile long cachedFileSizeBytes = 0;

    @Override
    public void onCachedFileUsed(String cachedFilePath, long fileSizeBytes) {
        this.cachedFilePath = cachedFilePath;
        this.cachedFileSizeBytes = fileSizeBytes;
        if (DEBUG) System.out.println("[CollinsScreen] onCachedFileUsed: path=" + cachedFilePath + " size=" + (fileSizeBytes / (1024L * 1024L)) + "MB");
    }

    public String getCachedFilePath() { return cachedFilePath; }
    public long getCachedFileSizeMb() { return cachedFileSizeBytes / (1024L * 1024L); }
    public boolean hasCachedFile() { return cachedFilePath != null && !cachedFilePath.isEmpty(); }

    private void clearCachedFileInfo() {
        this.cachedFilePath = null;
        this.cachedFileSizeBytes = 0;
    }

    private void resetDownloadState() {
        this.downloading = false;
        this.downloadPercent = 0;
        this.downloadedMb = 0;
        this.downloadTotalMb = 0;
        this.downloadStartWallMs = 0;
        this.resolvingYouTube = false;
        this.downloadingYtdlp = false;
        this.downloadingYoutubeVideo = false;
        this.downloadProgressReceived = false;
        this.downloadPhase = "";
    }

    private static String normalizeDownloadPhase(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        if (message.contains("youtube_downloading")) {
            return "youtube_download";
        }
        if (message.contains("ytdlp")) {
            return "youtube_ytdlp";
        }
        if (message.contains("youtube")) {
            return "youtube_prepare";
        }
        return "generic";
    }

    // Р“РµС‚С‚РµСЂ РґР»СЏ РїСЂРѕРІРµСЂРєРё РѕРєРѕРЅС‡Р°РЅРёСЏ РІРёРґРµРѕ (РїРѕРєР°Р·С‹РІР°С‚СЊ "РЎРµР°РЅСЃ РѕРєРѕРЅС‡РµРЅ" РІ С‚РµС‡РµРЅРёРµ 5 СЃРµРєСѓРЅРґ)
    private static final long ENDED_DISPLAY_DURATION_MS = 5000L;

    public boolean isEnded() {
        if (!ended) return false;
        // РџРѕРєР°Р·С‹РІР°РµРј "РЎРµР°РЅСЃ РѕРєРѕРЅС‡РµРЅ" С‚РѕР»СЊРєРѕ 5 СЃРµРєСѓРЅРґ
        if (endedAtMs > 0 && System.currentTimeMillis() - endedAtMs > ENDED_DISPLAY_DURATION_MS) {
            return false;
        }
        return true;
    }

    // Р’РѕР·РІСЂР°С‰Р°РµС‚ true РµСЃР»Рё РІРёРґРµРѕ Р·Р°РєРѕРЅС‡РёР»РѕСЃСЊ (Р±РµР· РѕРіСЂР°РЅРёС‡РµРЅРёСЏ РїРѕ РІСЂРµРјРµРЅРё)
    public boolean hasEnded() { return ended; }

    public int texW() { return texW; }
    public int texH() { return texH; }
}
