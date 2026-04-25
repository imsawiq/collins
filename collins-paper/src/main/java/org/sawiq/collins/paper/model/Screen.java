package org.sawiq.collins.paper.model;

public record Screen(
        String name,
        String world,
        int x1, int y1, int z1,
        int x2, int y2, int z2,
        byte axis,          // 0=XY,1=XZ,2=YZ
        String mp4Url,
        boolean playing,
        boolean loop,
        float volume,
        int youtubeQuality
) {
    /** Создаёт копию с изменённым флагом playing */
    public Screen withPlaying(boolean playing) {
        return new Screen(name, world, x1, y1, z1, x2, y2, z2, axis, mp4Url, playing, loop, volume, youtubeQuality);
    }

    public Screen withLoop(boolean loop) {
        return new Screen(name, world, x1, y1, z1, x2, y2, z2, axis, mp4Url, playing, loop, volume, youtubeQuality);
    }

    public Screen withYoutubeQuality(int youtubeQuality) {
        return new Screen(name, world, x1, y1, z1, x2, y2, z2, axis, mp4Url, playing, loop, volume, youtubeQuality);
    }
}
