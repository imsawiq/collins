package org.sawiq.collins.fabric.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;
import org.sawiq.collins.fabric.client.video.VideoPlayer;
import org.sawiq.collins.fabric.client.video.VideoScreen;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;
import org.sawiq.collins.fabric.client.video.YouTubeResolver;
import org.sawiq.collins.fabric.client.video.YouTubeQuality;

public final class CollinsClientCommands {

    private static final int GREEN = 0x00FF00;
    private static final int YELLOW = 0xFFFF55;
    private static final int RED = 0xFF5555;
    private static final int GRAY = 0xAAAAAA;
    private static final Text PREFIX = Text.translatable("text.collins.prefix").setStyle(Style.EMPTY.withColor(GREEN));

    private CollinsClientCommands() {
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("collinsc")
                .then(ClientCommandManager.literal("time")
                    .executes(ctx -> showTimeline(null))
                    .then(ClientCommandManager.argument("screen", StringArgumentType.word())
                        .executes(ctx -> showTimeline(StringArgumentType.getString(ctx, "screen")))))
                .then(ClientCommandManager.literal("quality")
                    .executes(ctx -> showQuality())
                    .then(ClientCommandManager.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String option : YouTubeQuality.options()) {
                                builder.suggest(option);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> setQuality(StringArgumentType.getString(ctx, "value"))))));

            dispatcher.register(ClientCommandManager.literal("collins-cache")
                .executes(ctx -> showCacheInfo())
                .then(ClientCommandManager.literal("info")
                    .executes(ctx -> showCacheInfo()))
                .then(ClientCommandManager.literal("open")
                    .executes(ctx -> openCacheFolder()))
                .then(ClientCommandManager.literal("delete")
                    .executes(ctx -> deletePendingFile()))
                .then(ClientCommandManager.literal("clear")
                    .executes(ctx -> clearCache())));

            dispatcher.register(ClientCommandManager.literal("collins-yt")
                .executes(ctx -> showYouTubeInfo())
                .then(ClientCommandManager.literal("info")
                    .executes(ctx -> showYouTubeInfo()))
                .then(ClientCommandManager.literal("install")
                    .executes(ctx -> installYtdlp()))
                .then(ClientCommandManager.literal("update")
                    .executes(ctx -> updateYtdlp())));
        });
    }

    private static MutableText label(String key) {
        return Text.translatable(key).setStyle(Style.EMPTY.withColor(GRAY));
    }

    private static MutableText value(Object value) {
        return Text.literal(String.valueOf(value)).setStyle(Style.EMPTY.withColor(Formatting.WHITE));
    }

    private static int showCacheInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        VideoPlayer.CacheInfo info = VideoPlayer.getCacheInfo();

        Text msg = PREFIX.copy()
            .append(Text.translatable("text.collins.cache.title").setStyle(Style.EMPTY.withColor(GREEN)))
            .append(Text.literal("\n"))
            .append(label("text.collins.cache.folder"))
            .append(value(info.cacheDir()))
            .append(Text.literal("\n"))
            .append(label("text.collins.cache.files"))
            .append(Text.translatable("text.collins.cache.files_value", info.fileCount(), info.cacheSizeMb()).setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
            .append(Text.literal("\n"))
            .append(label("text.collins.cache.free_space"))
            .append(Text.translatable("text.collins.cache.free_space_value", info.freeSpaceGb()).setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
            .append(Text.literal("\n"))
            .append(label("text.collins.commands"))
            .append(Text.literal("/collins-cache open").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Text.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("/collins-cache clear").setStyle(Style.EMPTY.withColor(RED)));

        client.player.sendMessage(msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int openCacheFolder() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        VideoPlayer.openCacheFolder();
        client.player.sendMessage(PREFIX.copy().append(
            Text.translatable("text.collins.cache.opened").setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int deletePendingFile() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String path = VideoScreenManager.getPendingDeletePath();
        if (path == null || path.isEmpty()) {
            VideoScreen screen = VideoScreenManager.findNearestPlayingOrEnded(client.player.getPos());
            if (screen != null && screen.hasCachedFile()) {
                path = screen.getCachedFilePath();
            }
        }

        if (path == null || path.isEmpty()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.translatable("text.collins.cache.no_file_to_delete").setStyle(Style.EMPTY.withColor(RED))), false);
            return 0;
        }

        boolean deleted = VideoPlayer.deleteCachedFile(path);
        if (deleted) {
            VideoScreenManager.clearPendingDeletePath();
            client.player.sendMessage(PREFIX.copy().append(
                Text.translatable("text.collins.cache.deleted").setStyle(Style.EMPTY.withColor(GREEN))), false);
        } else {
            client.player.sendMessage(PREFIX.copy().append(
                Text.translatable("text.collins.cache.delete_failed", path).setStyle(Style.EMPTY.withColor(RED))), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int clearCache() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        long deleted = VideoPlayer.clearCache();
        long deletedMb = deleted / (1024L * 1024L);

        VideoScreenManager.clearDeletePromptHistory();

        client.player.sendMessage(PREFIX.copy().append(
            Text.translatable("text.collins.cache.cleared", deletedMb).setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showTimeline(String screenName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        VideoScreen screen;
        if (screenName != null && !screenName.isBlank()) {
            screen = VideoScreenManager.getByName(screenName);
        } else {
            screen = VideoScreenManager.findNearestPlaying(client.player.getPos());
        }

        if (screen == null) {
            client.player.sendMessage(PREFIX.copy().append(Text.translatable("text.collins.timeline.no_active_screen").formatted(Formatting.RED)), false);
            return 0;
        }

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMsForDisplay(serverNowMs);
        long durMs = screen.durationMs();

        Text msg = durMs > 0
            ? Text.translatable("text.collins.timeline.full", screen.state().name(), TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs))
            : Text.translatable("text.collins.timeline.single", screen.state().name(), TimeFormatUtil.formatMs(posMs));

        client.player.sendMessage(PREFIX.copy().append(msg.copy().setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showYouTubeInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        boolean available = YouTubeResolver.isYtdlpAvailable();
        String version = available ? YouTubeResolver.getYtdlpVersion() : Text.translatable("text.collins.youtube.not_installed").getString();
        boolean downloading = YouTubeResolver.isDownloading();
        int progress = YouTubeResolver.getDownloadProgress();
        CollinsClientConfig cfg = CollinsClientConfig.get();

        Text msg = PREFIX.copy()
            .append(Text.translatable("text.collins.youtube.info_title").setStyle(Style.EMPTY.withColor(GREEN)))
            .append(Text.literal("\n"))
            .append(label("text.collins.youtube.status"))
            .append(Text.translatable(available ? "text.collins.youtube.installed" : "text.collins.youtube.not_installed")
                .setStyle(Style.EMPTY.withColor(available ? GREEN : RED)))
            .append(Text.literal("\n"))
            .append(label("text.collins.youtube.version"))
            .append(Text.literal(available ? version : Text.translatable("text.collins.youtube.not_installed").getString())
                .setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
            .append(Text.literal("\n"))
            .append(label("text.collins.youtube.quality"))
            .append(Text.literal(YouTubeQuality.display(cfg.youtubeMaxQuality))
                .setStyle(Style.EMPTY.withColor(Formatting.WHITE)));

        if (downloading) {
            msg = msg.copy()
                .append(Text.literal("\n"))
                .append(label("text.collins.youtube.download"))
                .append(Text.translatable("text.collins.youtube.download.progress", progress).setStyle(Style.EMPTY.withColor(YELLOW)));
        }

        msg = msg.copy()
            .append(Text.literal("\n"))
            .append(label("text.collins.commands"))
            .append(Text.literal("/collinsc quality").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Text.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("/collins-yt install").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Text.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("/collins-yt update").setStyle(Style.EMPTY.withColor(YELLOW)));

        client.player.sendMessage(msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showQuality() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        CollinsClientConfig cfg = CollinsClientConfig.get();
        client.player.sendMessage(PREFIX.copy().append(
            Text.translatable("text.collins.youtube.quality.current", YouTubeQuality.display(cfg.youtubeMaxQuality))
                .setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setQuality(String rawValue) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        Integer quality = YouTubeQuality.parse(rawValue);
        if (quality == null) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.translatable("text.collins.youtube.quality.usage")
                    .setStyle(Style.EMPTY.withColor(RED))), false);
            return 0;
        }

        CollinsClientConfig cfg = CollinsClientConfig.get();
        cfg.youtubeMaxQuality = quality;
        CollinsClientConfig.save();
        VideoScreenManager.stopAllPlayback();

        client.player.sendMessage(PREFIX.copy().append(
            Text.translatable("text.collins.youtube.quality.set", YouTubeQuality.display(cfg.youtubeMaxQuality))
                .setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int installYtdlp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        if (YouTubeResolver.isYtdlpAvailable()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.translatable("text.collins.youtube.installed_version", YouTubeResolver.getYtdlpVersion())
                    .setStyle(Style.EMPTY.withColor(GREEN))), false);
            return Command.SINGLE_SUCCESS;
        }

        if (YouTubeResolver.isDownloading()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.translatable("text.collins.youtube.already_downloading", YouTubeResolver.getDownloadProgress())
                    .setStyle(Style.EMPTY.withColor(YELLOW))), false);
            return Command.SINGLE_SUCCESS;
        }

        client.player.sendMessage(PREFIX.copy().append(
            Text.translatable("text.collins.youtube.installing").setStyle(Style.EMPTY.withColor(YELLOW))), false);

        YouTubeResolver.downloadYtdlpAsync();
        return Command.SINGLE_SUCCESS;
    }

    private static int updateYtdlp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        if (!YouTubeResolver.isYtdlpAvailable()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.translatable("text.collins.youtube.not_installed_use_install")
                    .setStyle(Style.EMPTY.withColor(RED))), false);
            return 0;
        }

        client.player.sendMessage(PREFIX.copy().append(
            Text.translatable("text.collins.youtube.updating")
                .setStyle(Style.EMPTY.withColor(YELLOW))), false);

        Thread t = new Thread(() -> {
            boolean success = YouTubeResolver.updateYtdlp();
            MinecraftClient.getInstance().execute(() -> {
                if (client.player != null) {
                    if (success) {
                        client.player.sendMessage(PREFIX.copy().append(
                            Text.translatable("text.collins.youtube.updated_version", YouTubeResolver.getYtdlpVersion())
                                .setStyle(Style.EMPTY.withColor(GREEN))), false);
                    } else {
                        client.player.sendMessage(PREFIX.copy().append(
                            Text.translatable("text.collins.youtube.update_failed")
                                .setStyle(Style.EMPTY.withColor(RED))), false);
                    }
                }
            });
        }, "Collins-YtdlpUpdate");
        t.setDaemon(true);
        t.start();

        return Command.SINGLE_SUCCESS;
    }
}
