package org.sawiq.collins.fabric.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;
import org.sawiq.collins.fabric.client.video.VideoScreen;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;

/**
 * In-game HUD overlay rendered just above the hotbar that surfaces the
 * progress / position / state of the screen the player is currently
 * standing in front of.
 *
 * <p>26.1 ported. Fabric removed {@code HudRenderCallback} in 26.1 in
 * favour of {@code HudElementRegistry} together with a deferred
 * {@code HudElement#extractRenderState(GuiGraphicsExtractor, DeltaTracker)}
 * model. We attach our element right before the vanilla {@code CHAT}
 * layer so it draws above status bars / experience but below the chat
 * window — matching the position the old {@code HudRenderCallback}
 * implementation used (y = screenHeight - 59, just above the hotbar).</p>
 *
 * <p>1.21.6+ also switched text colours from RGB to ARGB. All colour
 * literals here are explicitly opaque ({@code 0xFF......}) so they
 * render correctly with no transparency.</p>
 */
public final class VideoHudOverlay {

    private static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath("collins", "video_overlay");

    // ARGB colour constants. The high byte is the opacity — without it
    // the renderer treats the text as fully transparent in 1.21.6+.
    private static final int COLOR_DOWNLOADING = 0xFFFFFF00; // yellow
    private static final int COLOR_ENDED = 0xFF55FF55;       // light green
    private static final int COLOR_HINT = 0xFF888888;        // grey hint
    private static final int COLOR_TIMELINE = 0xFF00FF00;    // bright green

    private VideoHudOverlay() {
    }

    public static void init() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                ELEMENT_ID,
                VideoHudOverlay::extract);
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.font == null) return;
        // Hide the overlay while the chat is open so it does not clash
        // with the chat input. The previous 1.21.x impl used the same
        // gate against ChatScreen.
        if (client.screen instanceof ChatScreen) return;

        // Avoid drawing the timeline twice. VideoScreenManager already
        // pushes timeline / download progress to the actionbar via
        // sendOverlayMessage when actionbarTimeline is enabled, and the
        // vanilla actionbar renders right above the hotbar at the same
        // y as our HUD layer. Only draw here when the user has turned
        // the actionbar timeline off.
        if (CollinsClientConfig.get().actionbarTimeline) return;

        VideoScreen screen = VideoScreenManager.findNearestPlayingOrEnded(client.player.position());
        if (screen == null) return;

        int sw = client.getWindow().getGuiScaledWidth();
        int sh = client.getWindow().getGuiScaledHeight();
        int y = sh - 59;
        int centerX = sw / 2;

        if (screen.isDownloading()) {
            int pct = Math.max(0, screen.getDownloadPercent());
            long dlMb = Math.max(0L, screen.getDownloadedMb());
            long totalMb = Math.max(0L, screen.getDownloadTotalMb());
            String platform = screen.getPlatformLabel();

            Component text;
            if (screen.isDownloadingYtdlp()) {
                text = Component.translatable("text.collins.youtube.installing_progress", pct);
            } else if (screen.isDownloadingPlatformVideo() && totalMb > 0) {
                text = Component.translatable("text.collins.platform.download.progress_size", platform, pct, dlMb, totalMb);
            } else if (screen.isDownloadingPlatformVideo() && pct > 0) {
                text = Component.translatable("text.collins.platform.download.progress", platform, pct);
            } else if (screen.isDownloadingPlatformVideo() && dlMb > 0) {
                text = Component.translatable("text.collins.platform.download.size", platform, dlMb);
            } else if (screen.isDownloadingPlatformVideo()) {
                if (screen.hasDownloadProgressReceived()) {
                    text = Component.translatable("text.collins.platform.download.progress", platform, 0);
                } else {
                    text = Component.translatable("text.collins.platform.preparing", platform);
                }
            } else if (screen.isResolvingPlatformVideo()) {
                text = Component.translatable("text.collins.platform.preparing", platform);
            } else if (totalMb > 0) {
                text = Component.translatable("text.collins.video.download.progress_size", pct, dlMb, totalMb);
            } else if (pct > 0) {
                text = Component.translatable("text.collins.video.download.progress", pct);
            } else if (dlMb > 0) {
                text = Component.translatable("text.collins.video.download.size", dlMb);
            } else {
                text = Component.translatable("text.collins.video.preparing");
            }

            graphics.centeredText(client.font, text, centerX, y, COLOR_DOWNLOADING);
            return;
        }

        if (screen.isEnded()) {
            Component text = screen.hasCachedFile()
                    ? Component.translatable("text.collins.video.ended.cached", screen.getCachedFileSizeMb())
                    : Component.translatable("text.collins.video.ended");
            graphics.centeredText(client.font, text, centerX, y, COLOR_ENDED);
            if (screen.hasCachedFile()) {
                Component hint = Component.translatable("text.collins.video.delete_hint");
                graphics.centeredText(client.font, hint, centerX, y + 12, COLOR_HINT);
            }
            return;
        }

        if (screen.hasEnded()) {
            return;
        }

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMsForDisplay(serverNowMs);
        long durMs = screen.durationMs();

        Component text = (durMs > 0)
                ? Component.translatable("text.collins.timeline.short",
                        TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs))
                : Component.literal(TimeFormatUtil.formatMs(posMs)).withStyle(ChatFormatting.GREEN);

        graphics.centeredText(client.font, text, centerX, y, COLOR_TIMELINE);
    }
}
