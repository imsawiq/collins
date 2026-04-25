package org.sawiq.collins.paper.net;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class CollinsClientMessageListener implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final Set<UUID> moddedPlayers;
    private final Map<UUID, String> moddedPlayerVersions;
    private final Set<UUID> outdatedModdedPlayers;
    private final CollinsMessenger messenger;
    private final Predicate<String> outdatedVersionPredicate;

    public CollinsClientMessageListener(JavaPlugin plugin, Set<UUID> moddedPlayers,
                                        Map<UUID, String> moddedPlayerVersions,
                                        Set<UUID> outdatedModdedPlayers,
                                        CollinsMessenger messenger,
                                        Predicate<String> outdatedVersionPredicate) {
        this.plugin = plugin;
        this.moddedPlayers = moddedPlayers;
        this.moddedPlayerVersions = moddedPlayerVersions;
        this.outdatedModdedPlayers = outdatedModdedPlayers;
        this.messenger = messenger;
        this.outdatedVersionPredicate = outdatedVersionPredicate;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals("collins:main")) return;
        if (message.length < 8) return;

        try {
            parseMessage(player, message);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse client message from " + player.getName() + ": " + e.getMessage());
        }
    }

    private void parseMessage(Player player, byte[] bytes) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            String m = new String(magic, StandardCharsets.US_ASCII);
            if (!m.equals("COLL")) {
                return;
            }

            int len = in.readInt();
            if (len < 0 || len > 1024) {
                return;
            }

            if (in.available() < len) {
                return;
            }

            byte[] inner = new byte[len];
            in.readFully(inner);

            parseInner(player, inner);
        }
    }

    private void parseInner(Player player, byte[] inner) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(inner))) {
            byte msgType = in.readByte();
            int version = in.readInt();

            if (version != CollinsProtocol.PROTOCOL_VERSION) {
                return;
            }

            switch (msgType) {
                case CollinsProtocol.MSG_HELLO -> handleHello(player, in);
                // MSG_VIDEO_ENDED ignored - сервер сам определяет окончание по времени
                default -> {
                    // Неизвестный тип сообщения — игнорируем
                }
            }
        }
    }

    private void handleHello(Player player, DataInputStream in) throws Exception {
        // Клиент с модом Collins-Fabric подключился
        UUID uuid = player.getUniqueId();
        String clientVersion = in.available() > 0 ? sanitizeVersion(in.readUTF()) : "unknown";

        boolean added = moddedPlayers.add(uuid);
        String previousVersion = moddedPlayerVersions.put(uuid, clientVersion);
        boolean outdated = outdatedVersionPredicate.test(clientVersion);
        boolean outdatedChanged = outdated ? outdatedModdedPlayers.add(uuid) : outdatedModdedPlayers.remove(uuid);

        if (added || outdatedChanged || !clientVersion.equals(previousVersion)) {
            if (added) {
                plugin.getLogger().info("Player " + player.getName()
                        + " has Collins-Fabric mod " + clientVersion);
            }
            messenger.requestBroadcastSync();
        }
    }

    private static String sanitizeVersion(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.trim();
        if (trimmed.length() > 64) {
            return "unknown";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == '+')) {
                return "unknown";
            }
        }
        return trimmed;
    }
}
