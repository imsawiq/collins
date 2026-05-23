package org.sawiq.collins.paper.selection;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SelectionVisualizer {
    private SelectionVisualizer() {}

    private static final Map<UUID, ScheduledTask> TASKS = new ConcurrentHashMap<>();

    private static final double OUT = 0.65;
    private static final double UP = 0.15;
    private static final double STEP = 0.35;

    public static void stop(Player player) {
        ScheduledTask old = TASKS.remove(player.getUniqueId());
        if (old != null) old.cancel(); // отмена повторяющейся задачи (Folia-safe)
    }

    /**
     * Cancel every active selection-frame task. Called from the plugin's
     * {@code onDisable} so the JVM is not left holding ScheduledTasks
     * across a plugin reload (which would NPE on the next tick when they
     * try to deref the now-disabled plugin instance).
     */
    public static void stopAll() {
        for (var e : TASKS.entrySet()) {
            try {
                e.getValue().cancel();
            } catch (Exception ignored) {
            }
        }
        TASKS.clear();
    }

    public static void showFrame(JavaPlugin plugin, Player player,
                                 int x1, int y1, int z1,
                                 int x2, int y2, int z2,
                                 long durationTicks) {

        stop(player); // важное: отменяем прошлую рамку перед запуском новой

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        World w = player.getWorld();

        boolean isXY = (minZ == maxZ);
        boolean isXZ = (minY == maxY);
        boolean isYZ = (minX == maxX);

        final long[] left = { durationTicks };

        // Folia-safe: use the entity scheduler so the particle task runs on
        // the player's region every time the player moves. On Paper this
        // falls back to the main-thread scheduler transparently.
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, t -> {
            if (!player.isOnline() || left[0]-- <= 0) {
                stop(player);
                return;
            }

            if (isXY) drawFrameXY(player, w, minX, minY, maxX, maxY, minZ);
            else if (isXZ) drawFrameXZ(player, w, minX, minZ, maxX, maxZ, minY);
            else if (isYZ) drawFrameYZ(player, w, minY, minZ, maxY, maxZ, minX);
            else drawBoxFallback(player, w, minX, minY, minZ, maxX, maxY, maxZ);
        }, () -> TASKS.remove(player.getUniqueId()), 1L, 10L); // initial delay must be >= 1 on Folia

        if (task != null) {
            TASKS.put(player.getUniqueId(), task);
        }
    }

    private static void drawFrameXY(Player p, World w,
                                    int minX, int minY, int maxX, int maxY, int z) {
        double zOut = z + 0.5 + normalOut(p, 0, 0, 1) * OUT;

        for (double x = minX; x <= maxX + 1e-6; x += STEP) {
            spawn(p, w, x + 0.5, minY + 0.5 + UP, zOut);
            spawn(p, w, x + 0.5, maxY + 0.5 + UP, zOut);
        }
        for (double y = minY; y <= maxY + 1e-6; y += STEP) {
            spawn(p, w, minX + 0.5, y + 0.5 + UP, zOut);
            spawn(p, w, maxX + 0.5, y + 0.5 + UP, zOut);
        }
    }

    private static void drawFrameXZ(Player p, World w,
                                    int minX, int minZ, int maxX, int maxZ, int y) {
        double yOut = y + 0.5 + normalOut(p, 0, 1, 0) * OUT + UP;

        for (double x = minX; x <= maxX + 1e-6; x += STEP) {
            spawn(p, w, x + 0.5, yOut, minZ + 0.5);
            spawn(p, w, x + 0.5, yOut, maxZ + 0.5);
        }
        for (double z = minZ; z <= maxZ + 1e-6; z += STEP) {
            spawn(p, w, minX + 0.5, yOut, z + 0.5);
            spawn(p, w, maxX + 0.5, yOut, z + 0.5);
        }
    }

    private static void drawFrameYZ(Player p, World w,
                                    int minY, int minZ, int maxY, int maxZ, int x) {
        double xOut = x + 0.5 + normalOut(p, 1, 0, 0) * OUT;

        for (double y = minY; y <= maxY + 1e-6; y += STEP) {
            spawn(p, w, xOut, y + 0.5 + UP, minZ + 0.5);
            spawn(p, w, xOut, y + 0.5 + UP, maxZ + 0.5);
        }
        for (double z = minZ; z <= maxZ + 1e-6; z += STEP) {
            spawn(p, w, xOut, minY + 0.5 + UP, z + 0.5);
            spawn(p, w, xOut, maxY + 0.5 + UP, z + 0.5);
        }
    }

    private static void drawBoxFallback(Player p, World w,
                                        int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ) {
        for (double x = minX; x <= maxX + 1e-6; x += STEP) {
            spawn(p, w, x + 0.5, minY + 0.5 + UP, minZ + 0.5);
            spawn(p, w, x + 0.5, minY + 0.5 + UP, maxZ + 0.5);
            spawn(p, w, x + 0.5, maxY + 0.5 + UP, minZ + 0.5);
            spawn(p, w, x + 0.5, maxY + 0.5 + UP, maxZ + 0.5);
        }
    }

    private static double normalOut(Player p, int nx, int ny, int nz) {
        Location eye = p.getEyeLocation();
        double dot = eye.getDirection().getX() * nx + eye.getDirection().getY() * ny + eye.getDirection().getZ() * nz;
        return (dot > 0) ? -1.0 : 1.0;
    }

    private static void spawn(Player p, World w, double x, double y, double z) {
        p.spawnParticle(Particle.END_ROD, new Location(w, x, y, z), 1, 0, 0, 0, 0);
    }
}
