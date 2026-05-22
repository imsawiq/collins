package org.sawiq.collins.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.sawiq.collins.fabric.client.command.CollinsClientCommands;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.hud.VideoHudOverlay;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.sawiq.collins.fabric.client.update.ModrinthVersionChecker;
import org.sawiq.collins.fabric.client.update.UpdateAvailableScreen;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;
import org.sawiq.collins.fabric.client.video.VideoScreenRenderer;
import org.sawiq.collins.fabric.client.video.YouTubeResolver;

public final class CollinsFabricClient implements ClientModInitializer {

    private final ModrinthVersionChecker versionChecker = new ModrinthVersionChecker();
    private volatile ModrinthVersionChecker.Result pendingUpdate;
    private boolean updateScreenShown;

    @Override
    public void onInitializeClient() {
        CollinsClientConfig.get();
        CollinsNet.initClientReceiver();
        VideoScreenRenderer.init();
        VideoHudOverlay.init();
        CollinsClientCommands.init();

        // Prefetch yt-dlp + ffmpeg in the background so the first YouTube/Twitch URL
        // does not stall the game while binaries are downloading.
        if (!YouTubeResolver.isYtdlpAvailable() && !YouTubeResolver.isDownloading()) {
            YouTubeResolver.downloadYtdlpAsync();
        }
        YouTubeResolver.downloadFfmpegAsync();

        ClientTickEvents.END_CLIENT_TICK.register(VideoScreenManager::tick);

        // Show "newer version on Modrinth" notification once when the player
        // is sitting on the title screen. Async fetch on init, surfaces the
        // result the next tick the title screen is open.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.pendingUpdate != null
                    && !this.updateScreenShown
                    && client.screen instanceof TitleScreen titleScreen) {
                this.updateScreenShown = true;
                client.setScreen(new UpdateAvailableScreen(
                        titleScreen,
                        this.pendingUpdate.version(),
                        this.pendingUpdate.url()));
                this.pendingUpdate = null;
            }
        });

        this.versionChecker.checkAsync().thenAccept(result -> {
            if (result != null && Minecraft.getInstance() != null) {
                Minecraft.getInstance().execute(() -> this.pendingUpdate = result);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            VideoScreenManager.stopAll();
            CollinsNet.MODDED_PLAYERS.clear();
        });

        // Belt-and-suspenders: also wipe state on JOIN. Velocity/BungeeCord
        // proxy server switches sometimes don't fire DISCONNECT cleanly,
        // and the new server's plugin sends a fresh SYNC anyway, so it is
        // safe (and necessary) to drop any leftover screens/audio from the
        // previous connection here.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            VideoScreenManager.stopAll();
            CollinsNet.MODDED_PLAYERS.clear();
        });
    }
}
