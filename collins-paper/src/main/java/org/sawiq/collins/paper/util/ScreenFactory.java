package org.sawiq.collins.paper.util;

import org.bukkit.block.Block;
import org.sawiq.collins.paper.model.Axis;
import org.sawiq.collins.paper.model.Screen;

public final class ScreenFactory {
    private ScreenFactory() {}

    public static Screen create(String name, Block a, Block b, int defaultYoutubeQuality) {
        int youtubeQuality = YouTubeQuality.sanitize(defaultYoutubeQuality, YouTubeQuality.DEFAULT);
        String world = a.getWorld().getName();

        int x1 = Math.min(a.getX(), b.getX());
        int y1 = Math.min(a.getY(), b.getY());
        int z1 = Math.min(a.getZ(), b.getZ());
        int x2 = Math.max(a.getX(), b.getX());
        int y2 = Math.max(a.getY(), b.getY());
        int z2 = Math.max(a.getZ(), b.getZ());

        int dx = x2 - x1;
        int dy = y2 - y1;
        int dz = z2 - z1;

        Axis axis;
        if (dz == 0 && dx > 0 && dy > 0) axis = Axis.XY;
        else if (dy == 0 && dx > 0 && dz > 0) axis = Axis.XZ;
        else if (dx == 0 && dy > 0 && dz > 0) axis = Axis.YZ;
        else return null;

        return new Screen(
                name,
                world,
                x1, y1, z1,
                x2, y2, z2,
                axis.id,
                "",
                false,
                false,
                1.0f,
                youtubeQuality
        );
    }
}
