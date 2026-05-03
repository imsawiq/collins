package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VideoScreenManager {

    private static final boolean DEBUG = false;

    private VideoScreenManager() {}

    private static final Map<String, VideoScreen> SCREENS = new ConcurrentHashMap<>();
    private static final Set<String> SHOWN_DELETE_PROMPT = ConcurrentHashMap.newKeySet();
    private static volatile String pendingDeletePath = null;

    private static final int GREEN = 0x00FF00;
    private static final int GRAY = 0xAAAAAA;
    private static final int YELLOW = 0xFFFF55;
    private static final int RED = 0xFF5555;
    private static final Text PREFIX = Text.translatable("text.collins.prefix").setStyle(Style.EMPTY.withColor(GREEN));

    private static volatile long lastActionbarUpdateMs = 0;
    private static volatile String lastClientWorldKey = "";

    static String currentWorldKey(MinecraftClient client) {
        if (client == null) return "";
        try {
            if (client.world != null && client.world.getRegistryKey() != null) {
                String k = client.world.getRegistryKey().getValue().toString();
                return (k == null) ? "" : k;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String defaultBukkitWorldNameForDim(String dimKey) {
        if (dimKey == null || dimKey.isBlank()) return null;
        String k = dimKey.toLowerCase(Locale.ROOT);
        if (k.equals("minecraft:overworld")) return "world";
        if (k.equals("minecraft:the_nether")) return "world_nether";
        if (k.equals("minecraft:the_end")) return "world_the_end";
        return null;
    }

    private static boolean isDefaultBukkitWorldName(String w) {
        if (w == null) return false;
        String s = w.toLowerCase(Locale.ROOT);
        return s.equals("world") || s.equals("world_nether") || s.equals("world_the_end");
    }

    static boolean isCompatibleWithCurrentWorld(ScreenState st, MinecraftClient client) {
        if (st == null || client == null) return true;
        String sw = st.world();
        if (sw == null || sw.isBlank()) return true;

        String dimKey = currentWorldKey(client);
        if (sw.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            return sw.equalsIgnoreCase(dimKey);
        }

        if (isDefaultBukkitWorldName(sw)) {
            String expected = defaultBukkitWorldNameForDim(dimKey);
            return expected != null && sw.equalsIgnoreCase(expected);
        }

        return true;
    }

    public static Collection<VideoScreen> all() {
        return SCREENS.values();
    }

    public static VideoScreen getByName(String name) {
        if (name == null) return null;
        return SCREENS.get(name.toLowerCase(Locale.ROOT));
    }

    public static VideoScreen findNearestPlaying(Vec3d playerPos) {
        return findNearestPlayingInternal(playerPos, false);
    }

    public static VideoScreen findNearestPlayingOrEnded(Vec3d playerPos) {
        return findNearestPlayingInternal(playerPos, true);
    }

    private static VideoScreen findNearestPlayingInternal(Vec3d playerPos, boolean includeEnded) {
        if (playerPos == null) return null;

        MinecraftClient client = MinecraftClient.getInstance();
        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            boolean shouldInclude = st.playing() || (includeEnded && s.hasEnded());
            if (!shouldInclude) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    private static VideoScreen findNearestPlayingInRadius(Vec3d playerPos, int radiusBlocks) {
        if (playerPos == null) return null;
        if (radiusBlocks <= 0) return findNearestPlaying(playerPos);

        MinecraftClient client = MinecraftClient.getInstance();
        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;
        double r = (double) radiusBlocks;
        double r2 = r * r;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            if (!st.playing() && !s.isEnded()) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;

            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    public static void applySync(Map<String, ScreenState> incoming) {
        Set<String> keep = new HashSet<>(incoming.keySet());

        for (String key : new ArrayList<>(SCREENS.keySet())) {
            if (!keep.contains(key)) {
                VideoScreen vs = SCREENS.remove(key);
                if (vs != null) {
                    if (DEBUG) System.out.println("[Collins] STOP by remove: key=" + key);
                    vs.stop();
                    // Drop any "delete cached file?" prompts that were
                    // associated with this screen, otherwise SHOWN_DELETE_PROMPT
                    // accumulates {name + "_" + url} forever - one entry per
                    // distinct URL ever played on the screen.
                    String namePrefix = vs.state() != null ? vs.state().name() + "_" : null;
                    if (namePrefix != null) {
                        SHOWN_DELETE_PROMPT.removeIf(k -> k.startsWith(namePrefix));
                    }
                }
            }
        }

        for (var e : incoming.entrySet()) {
            String key = e.getKey();
            ScreenState st = e.getValue();

            VideoScreen vs = SCREENS.get(key);
            if (vs == null) {
                vs = new VideoScreen(st);
                SCREENS.put(key, vs);
                if (DEBUG) System.out.println("[Collins] screen created: key=" + key + " name=" + st.name());
            } else {
                vs.updateState(st);
            }

            if (!st.playing() || st.url() == null || st.url().isEmpty()) {
                if (DEBUG) {
                    System.out.println("[Collins] STOP by sync: name=" + st.name() + " playing=" + st.playing() + " url=" + st.url());
                }
                vs.stop();
            }
        }
    }

    private static MutableText buildActionBarText(VideoScreen nearest, long serverNowMs) {
        int pct = Math.max(0, nearest.getDownloadPercent());
        long dlMb = Math.max(0L, nearest.getDownloadedMb());
        long totalMb = Math.max(0L, nearest.getDownloadTotalMb());

        if (nearest.isDownloadingYtdlp()) {
            return Text.translatable("text.collins.youtube.installing_progress", pct).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        String platform = nearest.getPlatformLabel();
        boolean platformHasRealProgress = totalMb > 0 || pct > 0 || dlMb > 0;
        if (nearest.isDownloadingPlatformVideo() && totalMb > 0) {
            return Text.translatable("text.collins.platform.download.progress_size", platform, pct, dlMb, totalMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloadingPlatformVideo() && pct > 0) {
            return Text.translatable("text.collins.platform.download.progress", platform, pct).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloadingPlatformVideo() && dlMb > 0) {
            return Text.translatable("text.collins.platform.download.size", platform, dlMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloadingPlatformVideo() && !platformHasRealProgress) {
            if (nearest.hasDownloadProgressReceived()) {
                // Progress events flowing but values still 0 - show 0%
                return Text.translatable("text.collins.platform.download.progress", platform, 0).setStyle(Style.EMPTY.withColor(YELLOW));
            }
            return Text.translatable("text.collins.platform.preparing", platform).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isResolvingPlatformVideo()) {
            return Text.translatable("text.collins.platform.preparing", platform).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading() && totalMb > 0) {
            return Text.translatable("text.collins.video.download.progress_size", pct, dlMb, totalMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading() && pct > 0) {
            return Text.translatable("text.collins.video.download.progress", pct).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading() && dlMb > 0) {
            return Text.translatable("text.collins.video.download.size", dlMb).setStyle(Style.EMPTY.withColor(YELLOW));
        }
        if (nearest.isDownloading()) {
            return Text.translatable("text.collins.video.preparing").setStyle(Style.EMPTY.withColor(YELLOW));
        }

        if (nearest.isLiveStream()) {
            return Text.translatable("text.collins.video.live", nearest.state().name()).setStyle(Style.EMPTY.withColor(RED));
        }

        long posMs = nearest.currentPosMsForDisplay(serverNowMs);
        long durMs = nearest.durationMs();
        if (durMs > 0) {
            return Text.translatable("text.collins.timeline.full", nearest.state().name(), TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs))
                .setStyle(Style.EMPTY.withColor(GREEN));
        }
        return Text.translatable("text.collins.timeline.single", nearest.state().name(), TimeFormatUtil.formatMs(posMs))
            .setStyle(Style.EMPTY.withColor(GREEN));
    }

    private static void sendEndedPrompt(PlayerEntity player, VideoScreen nearest) {
        String screenKey = nearest.state().name() + "_" + nearest.state().url();
        if (!nearest.hasCachedFile() || SHOWN_DELETE_PROMPT.contains(screenKey)) return;

        SHOWN_DELETE_PROMPT.add(screenKey);
        pendingDeletePath = nearest.getCachedFilePath();
        long sizeMb = nearest.getCachedFileSizeMb();

        player.sendMessage(PREFIX.copy()
            .append(Text.translatable("text.collins.video.session_finished_cache", sizeMb).setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("\n"))
            .append(Text.literal("  /collins-cache delete").setStyle(Style.EMPTY.withColor(RED)))
            .append(Text.translatable("text.collins.video.delete_command_desc").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("\n"))
            .append(Text.literal("  /collins-cache open").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Text.translatable("text.collins.video.open_command_desc").setStyle(Style.EMPTY.withColor(GRAY))), false);
    }

    public static void tick(MinecraftClient client) {
        PlayerEntity p = client.player;

        // Independent watchdog. We cannot rely solely on
        // ClientPlayConnectionEvents.DISCONNECT because some mods
        // (notably Replay Mod, which records server packets and intercepts
        // network teardown) delay or swallow that event entirely - the user
        // reported audio bleeding through for the full duration of the
        // recording finalization. Whenever the client is no longer in any
        // world (title screen, disconnect, server-switch transition,
        // singleplayer save-and-quit), there is no legitimate reason for
        // any Collins screen to keep playing, so we drop everything.
        if (p == null || client.world == null) {
            if (!SCREENS.isEmpty()) {
                stopAll();
                lastClientWorldKey = "";
            } else {
                // Even with no screens we may still have a stale audio
                // line from a player that was just removed from SCREENS
                // by a previous tick or disconnect hook.
                VideoAudioPlayer.shutdownAll();
            }
            return;
        }

        String worldKey = currentWorldKey(client);
        if (!worldKey.equals(lastClientWorldKey)) {
            lastClientWorldKey = worldKey;
            stopAllPlayback();
        }

        Vec3d pos = p.getPos();
        int radius = CollinsNet.HEAR_RADIUS;
        float globalVolume = CollinsNet.GLOBAL_VOLUME;
        long serverNowMs = estimateServerNowMs();

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st != null && !isCompatibleWithCurrentWorld(st, client)) {
                if (st.playing()) s.stop();
                continue;
            }
            s.tickPlayback(pos, radius, globalVolume, serverNowMs);
        }

        CollinsClientConfig cfg = CollinsClientConfig.get();
        if (cfg.renderVideo && cfg.actionbarTimeline && !(client.currentScreen instanceof ChatScreen)) {
            long now = System.currentTimeMillis();
            if (now - lastActionbarUpdateMs >= 500L) {
                lastActionbarUpdateMs = now;

                VideoScreen nearest = findNearestPlayingInRadius(pos, radius);
                if (nearest != null) {
                    if (nearest.isEnded()) {
                        sendEndedPrompt(p, nearest);
                        p.sendMessage(Text.literal(""), true);
                    } else if (nearest.hasEnded()) {
                        p.sendMessage(Text.literal(""), true);
                    } else {
                        p.sendMessage(PREFIX.copy().append(buildActionBarText(nearest, serverNowMs)), true);
                    }
                }
            }
        }
    }

    public static long estimateServerNowMs() {
        long sn = CollinsNet.SERVER_NOW_MS;
        long cr = CollinsNet.CLIENT_RECV_MS;
        if (sn <= 0 || cr <= 0) return 0;
        return sn + (System.currentTimeMillis() - cr);
    }

    public static void stopAll() {
        if (DEBUG) System.out.println("[Collins] stopAll()");
        // Belt-and-suspenders: globally close every audio line that any
        // VideoPlayer ever opened BEFORE we walk the SCREENS map. Per-screen
        // stop() also tries to silence audio, but it routes through that
        // screen's currentAudio reference, which has a narrow race window
        // around playOnce()'s try-with-resources where currentAudio can be
        // either null or already-discarded. Closing all lines up-front
        // makes the user-perceived silence instant and unconditional.
        VideoAudioPlayer.shutdownAll();
        for (VideoScreen s : SCREENS.values()) s.stop();
        SCREENS.clear();
    }

    public static void stopAllPlayback() {
        if (DEBUG) System.out.println("[Collins] stopAllPlayback()");
        VideoAudioPlayer.shutdownAll();
        for (VideoScreen s : SCREENS.values()) s.stop();
    }

    public static String getPendingDeletePath() {
        return pendingDeletePath;
    }

    public static boolean deletePendingFile() {
        String path = pendingDeletePath;
        if (path != null && !path.isEmpty()) {
            boolean deleted = VideoPlayer.deleteCachedFile(path);
            if (deleted) {
                pendingDeletePath = null;
            }
            return deleted;
        }
        return false;
    }

    public static void clearDeletePromptHistory() {
        SHOWN_DELETE_PROMPT.clear();
    }

    public static void clearPendingDeletePath() {
        pendingDeletePath = null;
    }
}

