package org.sawiq.collins.fabric.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;
import org.sawiq.collins.fabric.client.video.VideoScreen;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;

public final class VideoHudOverlay {

    private VideoHudOverlay() {
    }

    public static void init() {
        HudRenderCallback.EVENT.register(VideoHudOverlay::render);
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.textRenderer == null) return;
        if (client.currentScreen instanceof ChatScreen) return;

        VideoScreen screen = VideoScreenManager.findNearestPlayingOrEnded(client.player.getPos());
        if (screen == null) return;

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int y = sh - 59;

        if (screen.isDownloading()) {
            int pct = Math.max(0, screen.getDownloadPercent());
            long dlMb = Math.max(0L, screen.getDownloadedMb());
            long totalMb = Math.max(0L, screen.getDownloadTotalMb());
            Text text;
            if (screen.isDownloadingYtdlp()) {
                text = Text.translatable("text.collins.youtube.installing_progress", pct);
            } else if (screen.isDownloadingYoutubeVideo() && totalMb > 0) {
                text = Text.translatable("text.collins.youtube.download.progress_size", pct, dlMb, totalMb);
            } else if (screen.isDownloadingYoutubeVideo() && pct > 0) {
                text = Text.translatable("text.collins.youtube.download.progress", pct);
            } else if (screen.isDownloadingYoutubeVideo() && dlMb > 0) {
                text = Text.translatable("text.collins.youtube.download.size", dlMb);
            } else if (screen.isDownloadingYoutubeVideo()) {
                if (screen.hasDownloadProgressReceived()) {
                    text = Text.translatable("text.collins.youtube.download.progress", 0);
                } else {
                    text = Text.translatable("text.collins.youtube.preparing");
                }
            } else if (screen.isResolvingYouTube()) {
                text = Text.translatable("text.collins.youtube.preparing");
            } else if (totalMb > 0) {
                text = Text.translatable("text.collins.video.download.progress_size", pct, dlMb, totalMb);
            } else if (pct > 0) {
                text = Text.translatable("text.collins.video.download.progress", pct);
            } else if (dlMb > 0) {
                text = Text.translatable("text.collins.video.download.size", dlMb);
            } else {
                text = Text.translatable("text.collins.video.preparing");
            }
            ctx.drawCenteredTextWithShadow(client.textRenderer, text, sw / 2, y, 0xFFFF00);
            return;
        }

        if (screen.isEnded()) {
            Text text = screen.hasCachedFile()
                ? Text.translatable("text.collins.video.ended.cached", screen.getCachedFileSizeMb())
                : Text.translatable("text.collins.video.ended");
            ctx.drawCenteredTextWithShadow(client.textRenderer, text, sw / 2, y, 0x55FF55);
            if (screen.hasCachedFile()) {
                Text hint = Text.translatable("text.collins.video.delete_hint");
                ctx.drawCenteredTextWithShadow(client.textRenderer, hint, sw / 2, y + 12, 0x888888);
            }
            return;
        }

        if (screen.hasEnded()) {
            return;
        }

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMsForDisplay(serverNowMs);
        long durMs = screen.durationMs();

        Text text = (durMs > 0)
            ? Text.translatable("text.collins.timeline.short", TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs))
            : Text.literal(TimeFormatUtil.formatMs(posMs)).formatted(Formatting.GREEN);

        ctx.drawCenteredTextWithShadow(client.textRenderer, text, sw / 2, y, 0x00FF00);
    }
}
