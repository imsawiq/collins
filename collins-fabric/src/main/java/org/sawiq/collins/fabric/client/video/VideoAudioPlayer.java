package org.sawiq.collins.fabric.client.video;

import javax.sound.sampled.*;
import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

public final class VideoAudioPlayer implements AutoCloseable {

    /**
     * Every {@link VideoAudioPlayer} that has successfully opened its
     * native audio line is tracked here so that {@link #shutdownAll()}
     * can guarantee silence on disconnect even if some {@code VideoPlayer}
     * lost track of its {@code currentAudio} reference due to a race
     * between {@code playOnce()} opening a fresh line and {@code stop()}
     * being called from the network/disconnect thread.
     */
    private static final Set<VideoAudioPlayer> ACTIVE = ConcurrentHashMap.newKeySet();

    /**
     * Closes <b>every</b> audio line that any {@link VideoAudioPlayer}
     * has open. Idempotent; safe to call from any thread. Used by the
     * client disconnect/join hooks so users never hear stale audio
     * after leaving a server.
     */
    public static void shutdownAll() {
        for (VideoAudioPlayer p : ACTIVE) {
            try {
                p.shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    private final int sampleRate;
    private final int channels;
    private final SourceDataLine line;

    private volatile boolean started;

    /**
     * Set as soon as {@link #shutdownNow()} is called. We check it from
     * {@link #writeSamples} and {@link #prebufferSamples} as an extra
     * safety net: even on JDK builds where {@code line.write()} on a
     * closed {@link SourceDataLine} silently returns 0 (busy-looping the
     * grab thread instead of throwing), we will refuse to push any more
     * samples to native code once shutdown has been requested.
     */
    private volatile boolean silenced = false;

    private volatile float gain = 1.0f;

    private byte[] pcmBuf = new byte[0];

    private final int prebufferMaxBytes;
    private byte[] prebuffer = new byte[0];
    private int prebufferLen = 0;

    public VideoAudioPlayer(int sampleRate, int channels) throws LineUnavailableException {
        this.sampleRate = sampleRate;
        this.channels = channels;

        AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false); // PCM 16-bit LE
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        int bytesPerSecond = sampleRate * channels * 2;
        int lineBufferBytes = Math.max(65_536, bytesPerSecond);

        this.line = (SourceDataLine) AudioSystem.getLine(info);
        this.line.open(fmt, lineBufferBytes);
        this.started = false;

        this.prebufferMaxBytes = Math.max(65536, bytesPerSecond * 4);

        ACTIVE.add(this);
    }

    public void startPlayback() {
        if (started) return;
        line.start();
        started = true;
    }

    public boolean isStarted() {
        return started;
    }

    public void setGain(float gain) {
        this.gain = Math.max(0f, gain);
    }

    public void shutdownNow() {
        // Mark silenced FIRST so any concurrent writeSamples() running on
        // the playback thread bails out before touching the (now closing)
        // native line. This is what actually guarantees instant silence:
        // even if line.stop()/close() were no-ops on this JDK build, our
        // own write path no longer feeds samples to native code.
        silenced = true;
        try { line.stop(); } catch (Exception ignored) {}
        try { line.flush(); } catch (Exception ignored) {}
        try { line.close(); } catch (Exception ignored) {}
        started = false;
        prebufferLen = 0;
        ACTIVE.remove(this);
    }

    public boolean hasPrebuffer() {
        return prebufferLen > 0;
    }

    public void prebufferSamples(Buffer[] samples, int channelsWanted) {
        if (silenced) return;
        if (samples == null || samples.length == 0) return;
        if (!(samples[0] instanceof ShortBuffer)) return;

        int pcmLen = toPcm16le(samples, channelsWanted);
        if (pcmLen <= 0) return;

        if (prebufferLen + pcmLen > prebufferMaxBytes) return;
        ensurePrebufferCapacity(prebufferLen + pcmLen);
        System.arraycopy(pcmBuf, 0, prebuffer, prebufferLen, pcmLen);
        prebufferLen += pcmLen;
    }

    public void flushPrebuffer() {
        if (silenced) { prebufferLen = 0; return; }
        if (prebufferLen <= 0) return;
        writePcmNonBlocking(prebuffer, prebufferLen);
        prebufferLen = 0;
    }

    public double timeSeconds() {
        return line.getMicrosecondPosition() / 1_000_000.0;
    }

    public long timeUs() {
        return line.getMicrosecondPosition();
    }

    public void writeSamples(Buffer[] samples, int channelsWanted) {
        if (silenced) return;
        if (samples == null || samples.length == 0) return;

        // чаще всего JavaCV даёт ShortBuffer
        if (samples[0] instanceof ShortBuffer) {
            int pcmLen = toPcm16le(samples, channelsWanted);
            if (pcmLen > 0) {
                writePcmNonBlocking(pcmBuf, pcmLen);
            }
        }
    }

    private int toPcm16le(Buffer[] samples, int channelsWanted) {
        float g = this.gain;

        if (channelsWanted <= 1) {
            ShortBuffer sb = ((ShortBuffer) samples[0]).duplicate();
            int len = sb.remaining() * 2;
            ensureCapacity(len);

            int o = 0;
            while (sb.hasRemaining()) {
                short s = scaleClamp(sb.get(), g);
                pcmBuf[o++] = (byte) (s & 0xFF);
                pcmBuf[o++] = (byte) ((s >>> 8) & 0xFF);
            }
            return o;
        }

        // stereo: либо planar (L,R), либо interleaved
        if (samples.length >= 2 && samples[0] instanceof ShortBuffer && samples[1] instanceof ShortBuffer) {
            ShortBuffer l = ((ShortBuffer) samples[0]).duplicate();
            ShortBuffer r = ((ShortBuffer) samples[1]).duplicate();

            int n = Math.min(l.remaining(), r.remaining());
            int len = n * 2 * 2;
            ensureCapacity(len);

            int o = 0;
            for (int i = 0; i < n; i++) {
                short sl = scaleClamp(l.get(), g);
                pcmBuf[o++] = (byte) (sl & 0xFF);
                pcmBuf[o++] = (byte) ((sl >>> 8) & 0xFF);

                short sr = scaleClamp(r.get(), g);
                pcmBuf[o++] = (byte) (sr & 0xFF);
                pcmBuf[o++] = (byte) ((sr >>> 8) & 0xFF);
            }
            return o;
        }

        // interleaved
        ShortBuffer sb = ((ShortBuffer) samples[0]).duplicate();
        int len = sb.remaining() * 2;
        ensureCapacity(len);

        int o = 0;
        while (sb.hasRemaining()) {
            short s = scaleClamp(sb.get(), g);
            pcmBuf[o++] = (byte) (s & 0xFF);
            pcmBuf[o++] = (byte) ((s >>> 8) & 0xFF);
        }
        return o;
    }

    private void ensureCapacity(int len) {
        if (pcmBuf.length >= len) return;
        pcmBuf = new byte[len];
    }

    private void ensurePrebufferCapacity(int len) {
        if (prebuffer.length >= len) return;
        prebuffer = new byte[len];
    }

    private void writePcmNonBlocking(byte[] pcm, int len) {
        int off = 0;
        while (off < len) {
            // Two independent ways out: either we were interrupted, OR
            // shutdownNow() flipped the silenced flag. The second check
            // is what saves us when the FFmpeg grab thread is still alive
            // but the line has already been closed by another thread.
            if (silenced || Thread.interrupted()) return;

            int avail;
            try {
                avail = line.available();
            } catch (Exception ignored) {
                return;
            }
            if (avail <= 0) {
                LockSupport.parkNanos(1_000_000L);
                continue;
            }

            int n = Math.min(avail, len - off);
            try {
                line.write(pcm, off, n);
            } catch (Exception ignored) {
                return;
            }
            off += n;
        }
    }

    private static short scaleClamp(short s, float g) {
        if (g == 1.0f) return s;

        int v = Math.round(s * g);
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
        return (short) v;
    }

    @Override
    public void close() {
        shutdownNow();
    }
}
