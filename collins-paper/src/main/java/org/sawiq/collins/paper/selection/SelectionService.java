package org.sawiq.collins.paper.selection;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.sawiq.collins.paper.model.Selection;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SelectionService {
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public Selection get(Player p) {
        return selections.getOrDefault(p.getUniqueId(), new Selection(null, null));
    }

    public void setPos1(Player p, Block b) {
        Selection s = get(p);
        selections.put(p.getUniqueId(), new Selection(b, s.pos2()));
    }

    public void setPos2(Player p, Block b) {
        Selection s = get(p);
        selections.put(p.getUniqueId(), new Selection(s.pos1(), b));
    }

    /**
     * Drop the selection state for a player who has just left the
     * server. Without this, {@link #selections} would keep one entry
     * forever for every player who ever ran {@code /collins pos1} or
     * {@code /collins pos2}. Called from
     * {@code CollinsPaperPlugin#onQuit}.
     */
    public void forget(UUID uuid) {
        if (uuid != null) selections.remove(uuid);
    }

    /**
     * Defensive periodic sweep: drop any selection whose owner UUID is
     * no longer in the supplied online set. Backstop in case
     * {@code PlayerQuitEvent} was missed.
     */
    public void forgetMissingPlayers(java.util.Set<UUID> online) {
        if (online == null) return;
        selections.keySet().removeIf(uuid -> !online.contains(uuid));
    }
}
