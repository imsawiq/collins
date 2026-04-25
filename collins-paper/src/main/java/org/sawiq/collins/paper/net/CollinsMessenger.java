package org.sawiq.collins.paper.net;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.ScreenStore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public final class CollinsMessenger {

    private final JavaPlugin plugin;
    private final ScreenStore store;
    private final CollinsRuntimeState runtime;
    private final Set<UUID> moddedPlayers;
    private final Set<UUID> outdatedModdedPlayers;

    private volatile boolean broadcastScheduled;

    public CollinsMessenger(JavaPlugin plugin, ScreenStore store, CollinsRuntimeState runtime,
                            Set<UUID> moddedPlayers, Set<UUID> outdatedModdedPlayers) {
        this.plugin = plugin;
        this.store = store;
        this.runtime = runtime;
        this.moddedPlayers = moddedPlayers;
        this.outdatedModdedPlayers = outdatedModdedPlayers;
    }

    public void sendSync(Player player) {
        try {
            byte[] payload = buildWrappedSyncBytes();
            player.sendPluginMessage(plugin, "collins:main", payload);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send SYNC: " + e.getMessage());
        }
    }

    /**
     * Broadcasts the current sync state to every online player. Builds the
     * payload <b>once</b> and reuses the same byte array for every send -
     * the previous implementation rebuilt the full payload (~80 B per
     * screen + 16 B per modded player) for every recipient, so a 100-player
     * server with 50 screens was doing ~100 redundant ~5 KB encodes plus
     * a fresh stream/filter/toList over the whole player list each tick.
     */
    public void broadcastSync() {
        var players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }
        byte[] payload;
        try {
            payload = buildWrappedSyncBytes();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to build SYNC: " + e.getMessage());
            return;
        }
        for (Player p : players) {
            try {
                p.sendPluginMessage(plugin, "collins:main", payload);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send SYNC to "
                        + p.getName() + ": " + e.getMessage());
            }
        }
    }

    public void requestBroadcastSync() {
        if (broadcastScheduled) return;
        broadcastScheduled = true;

        Bukkit.getScheduler().runTask(plugin, () -> {
            broadcastScheduled = false;
            broadcastSync();
        });
    }

    private byte[] buildWrappedSyncBytes() throws Exception {
        byte[] inner = buildSyncInnerBytes();

        var bout = new ByteArrayOutputStream();
        var out = new DataOutputStream(bout);

        out.write("COLL".getBytes(StandardCharsets.US_ASCII));
        out.writeInt(inner.length);
        out.write(inner);
        out.flush();

        return bout.toByteArray();
    }

    private byte[] buildSyncInnerBytes() throws Exception {
        long now = System.currentTimeMillis();

        var bout = new ByteArrayOutputStream();
        var out = new DataOutputStream(bout);

        out.writeByte(CollinsProtocol.MSG_SYNC);
        out.writeInt(CollinsProtocol.PROTOCOL_VERSION);

        out.writeFloat(runtime.globalVolume);
        out.writeInt(runtime.hearRadius);
        out.writeLong(now);

        var onlineModded = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .filter(moddedPlayers::contains)
                .toList();
        out.writeInt(onlineModded.size());
        for (UUID uuid : onlineModded) {
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        }

        var all = store.all();
        out.writeInt(all.size());

        for (Screen s : all) {
            out.writeUTF(s.name());
            out.writeUTF(s.world());

            out.writeInt(s.x1());
            out.writeInt(s.y1());
            out.writeInt(s.z1());
            out.writeInt(s.x2());
            out.writeInt(s.y2());
            out.writeInt(s.z2());

            out.writeByte(s.axis());

            out.writeUTF(s.mp4Url() == null ? "" : s.mp4Url());
            out.writeBoolean(s.playing());
            out.writeBoolean(s.loop());
            out.writeFloat(s.volume());
            out.writeInt(s.youtubeQuality());

            CollinsRuntimeState.Playback pb = runtime.get(s.name());
            out.writeLong(pb.startEpochMs);
            out.writeLong(pb.basePosMs);
        }

        var onlineOutdated = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .filter(outdatedModdedPlayers::contains)
                .toList();
        out.writeInt(onlineOutdated.size());
        for (UUID uuid : onlineOutdated) {
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        }

        out.flush();
        return bout.toByteArray();
    }
}
