package org.sawiq.collins.fabric.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.client.video.YouTubeQuality;
import org.sawiq.collins.fabric.net.CollinsMainC2SPayload;
import org.sawiq.collins.fabric.net.CollinsMainS2CPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CollinsNet {

    private CollinsNet() {}

    private static final boolean DEBUG = false;

    public static final int MAX_PACKET_BYTES = 5_000_000;

    // Protocol constants (mirrored from server CollinsProtocol)
    private static final int PROTOCOL_VERSION = 4;
    private static final byte MSG_SYNC = 1;
    private static final byte MSG_VIDEO_ENDED = 2;
    private static final byte MSG_HELLO = 4;

    public static final Map<String, ScreenState> SCREENS = new ConcurrentHashMap<>();
    public static final Set<UUID> MODDED_PLAYERS = ConcurrentHashMap.newKeySet();
    public static final Set<UUID> OUTDATED_MODDED_PLAYERS = ConcurrentHashMap.newKeySet();

    public static volatile float GLOBAL_VOLUME = 1.0f;
    public static volatile int HEAR_RADIUS = 100;
    public static volatile long SERVER_NOW_MS = 0;
    public static volatile long CLIENT_RECV_MS = 0;

    private static final long HELLO_RESEND_INTERVAL_MS = 10_000L;
    private static volatile long lastHelloSentAtMs = 0L;

    public static void initClientReceiver() {
        if (DEBUG) System.out.println("[Collins] Client init: registering receiver collins:main");

        ClientPlayNetworking.registerGlobalReceiver(CollinsMainS2CPayload.TYPE, (payload, context) -> {
            byte[] bytes = payload.data();

            context.client().execute(() -> {
                try {
                    sendHelloIfNeeded();
                    parseWrapped(bytes);
                } catch (Exception e) {
                    if (DEBUG) System.out.println("[Collins] Failed to parse packet: " + e.getMessage());
                }
            });
        });
    }

    private static void sendHelloIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastHelloSentAtMs < HELLO_RESEND_INTERVAL_MS) {
            return;
        }

        try {
            ClientPlayNetworking.send(new CollinsMainC2SPayload(buildHelloMessage()));
            lastHelloSentAtMs = now;
        } catch (Exception e) {
            if (DEBUG) System.out.println("[Collins] Failed to send hello: " + e.getMessage());
        }
    }

    private static byte[] buildHelloMessage() throws Exception {
        var innerBout = new ByteArrayOutputStream();
        var innerOut = new DataOutputStream(innerBout);
        innerOut.writeByte(MSG_HELLO);
        innerOut.writeInt(PROTOCOL_VERSION);
        innerOut.writeUTF(getClientModVersion());
        innerOut.flush();
        byte[] inner = innerBout.toByteArray();

        var bout = new ByteArrayOutputStream();
        var out = new DataOutputStream(bout);
        out.write("COLL".getBytes(StandardCharsets.US_ASCII));
        out.writeInt(inner.length);
        out.write(inner);
        out.flush();
        return bout.toByteArray();
    }

    private static void parseWrapped(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length < 8) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[4];
            in.readFully(magic);

            String m = new String(magic, StandardCharsets.US_ASCII);
            if (!m.equals("COLL")) {
                return;
            }

            int len = in.readInt();
            if (len < 0 || len > MAX_PACKET_BYTES) {
                if (DEBUG) System.out.println("[Collins] Bad len=" + len);
                return;
            }

            int available = in.available();
            if (available < len) {
                if (DEBUG) System.out.println("[Collins] Not enough bytes. need=" + len + " avail=" + available);
                return;
            }

            byte[] inner = new byte[len];
            in.readFully(inner);

            parseInner(inner);
        }
    }

    private static void parseInner(byte[] inner) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(inner))) {
            byte msg = in.readByte();
            int version = in.readInt();

            if (msg != 1) {
                if (DEBUG) System.out.println("[Collins] Unsupported msg=" + msg + " ver=" + version);
                return;
            }

            if (version == 1) {
                int count = in.readInt();
                if (count < 0 || count > 10_000) {
                    if (DEBUG) System.out.println("[Collins] Bad screen count=" + count);
                    return;
                }

                GLOBAL_VOLUME = 1.0f;
                HEAR_RADIUS = 100;
                SERVER_NOW_MS = 0;
                CLIENT_RECV_MS = 0;
                MODDED_PLAYERS.clear();
                OUTDATED_MODDED_PLAYERS.clear();

                SCREENS.clear();
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF();
                    String world = in.readUTF();

                    int x1 = in.readInt(), y1 = in.readInt(), z1 = in.readInt();
                    int x2 = in.readInt(), y2 = in.readInt(), z2 = in.readInt();

                    byte axis = in.readByte();
                    String url = in.readUTF();

                    boolean playing = in.readBoolean();
                    boolean loop = in.readBoolean();
                    float volume = in.readFloat();

                    SCREENS.put(name.toLowerCase(), new ScreenState(
                            name, world,
                            x1, y1, z1,
                            x2, y2, z2,
                            axis,
                            url,
                            playing,
                            loop,
                            volume,
                            YouTubeQuality.DEFAULT,
                            0L,
                            0L
                    ));
                }

                org.sawiq.collins.fabric.client.video.VideoScreenManager.applySync(SCREENS);
                if (DEBUG) System.out.println("[Collins] SYNC v1 received: " + count + " screens");
                return;
            }

            if (version != 2 && version != 3 && version != 4) {
                if (DEBUG) System.out.println("[Collins] Unsupported msg=" + msg + " ver=" + version);
                return;
            }

            GLOBAL_VOLUME = in.readFloat();
            HEAR_RADIUS = in.readInt();
            SERVER_NOW_MS = in.readLong();
            CLIENT_RECV_MS = System.currentTimeMillis();
            MODDED_PLAYERS.clear();
            OUTDATED_MODDED_PLAYERS.clear();

            if (version >= 3) {
                int moddedCount = in.readInt();
                if (moddedCount < 0 || moddedCount > 10_000) {
                    if (DEBUG) System.out.println("[Collins] Bad modded player count=" + moddedCount);
                    return;
                }
                for (int i = 0; i < moddedCount; i++) {
                    MODDED_PLAYERS.add(new UUID(in.readLong(), in.readLong()));
                }
            }

            int count = in.readInt();
            if (count < 0 || count > 10_000) {
                if (DEBUG) System.out.println("[Collins] Bad screen count=" + count);
                return;
            }

            SCREENS.clear();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                String world = in.readUTF();

                int x1 = in.readInt(), y1 = in.readInt(), z1 = in.readInt();
                int x2 = in.readInt(), y2 = in.readInt(), z2 = in.readInt();

                byte axis = in.readByte();
                String url = in.readUTF();

                boolean playing = in.readBoolean();
                boolean loop = in.readBoolean();
                float volume = in.readFloat();
                int youtubeQuality = version >= 4 ? in.readInt() : YouTubeQuality.DEFAULT;

                long startEpochMs = in.readLong();
                long basePosMs = in.readLong();

                SCREENS.put(name.toLowerCase(), new ScreenState(
                        name, world,
                        x1, y1, z1,
                        x2, y2, z2,
                        axis,
                        url,
                        playing,
                        loop,
                        volume,
                        youtubeQuality,
                        startEpochMs,
                        basePosMs
                ));
            }

            if (in.available() >= Integer.BYTES) {
                int outdatedCount = in.readInt();
                if (outdatedCount < 0 || outdatedCount > 10_000) {
                    if (DEBUG) System.out.println("[Collins] Bad outdated modded player count=" + outdatedCount);
                    return;
                }
                for (int i = 0; i < outdatedCount; i++) {
                    if (in.available() < Long.BYTES * 2) {
                        return;
                    }
                    OUTDATED_MODDED_PLAYERS.add(new UUID(in.readLong(), in.readLong()));
                }
            }

            org.sawiq.collins.fabric.client.video.VideoScreenManager.applySync(SCREENS);
            if (DEBUG) System.out.println("[Collins] SYNC v" + version + " received: " + count + " screens");
        }
    }

    public static boolean hasCollinsMod(UUID uuid) {
        return MODDED_PLAYERS.contains(uuid);
    }

    public static boolean hasOutdatedCollinsMod(UUID uuid) {
        return OUTDATED_MODDED_PLAYERS.contains(uuid);
    }

    private static String getClientModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("collins-fabric")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .filter(version -> version != null && !version.isBlank() && version.length() <= 64)
                .orElse("unknown");
    }
}
