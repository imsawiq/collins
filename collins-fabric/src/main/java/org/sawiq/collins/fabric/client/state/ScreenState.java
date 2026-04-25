package org.sawiq.collins.fabric.client.state;

public record ScreenState(
        String name,
        String world,
        int x1, int y1, int z1,
        int x2, int y2, int z2,
        byte axis,      // 0=XY,1=XZ,2=YZ
        String url,
        boolean playing,
        boolean loop,
        float volume,
        int youtubeQuality,
        long startEpochMs,
        long basePosMs

) {
    public int minX() { return Math.min(x1, x2); }
    public int minY() { return Math.min(y1, y2); }
    public int minZ() { return Math.min(z1, z2); }
    public int maxX() { return Math.max(x1, x2); }
    public int maxY() { return Math.max(y1, y2); }
    public int maxZ() { return Math.max(z1, z2); }

    // “Размер в блоках” (по твоей логике плоскости)
    public int blocksW() {
        return switch (axis) {
            case 0 -> (maxX() - minX() + 1); // XY: ширина по X
            case 1 -> (maxX() - minX() + 1); // XZ: ширина по X
            case 2 -> (maxZ() - minZ() + 1); // YZ: ширина по Z
            default -> 1;
        };
    }

    public int blocksH() {
        return switch (axis) {
            case 0 -> (maxY() - minY() + 1); // XY: высота по Y
            case 1 -> (maxZ() - minZ() + 1); // XZ: высота по Z
            case 2 -> (maxY() - minY() + 1); // YZ: высота по Y
            default -> 1;
        };
    }
}
