package org.sawiq.collins.fabric.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
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
    private static final  Component PREFIX = Component.translatable("text.collins.prefix").setStyle(Style.EMPTY.withColor(GREEN));

    private CollinsClientCommands() {
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("collinsc")
                .then(ClientCommands.literal("time")
                    .executes(ctx -> showTimeline(null))
                    .then(ClientCommands.argument("screen", StringArgumentType.word())
                        .executes(ctx -> showTimeline(StringArgumentType.getString(ctx, "screen")))))
                .then(ClientCommands.literal("quality")
                    .executes(ctx -> showQuality())
                    .then(ClientCommands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String option : YouTubeQuality.options()) {
                                builder.suggest(option);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> setQuality(StringArgumentType.getString(ctx, "value"))))));

            dispatcher.register(ClientCommands.literal("collins-cache")
                .executes(ctx -> showCacheInfo())
                .then(ClientCommands.literal("info")
                    .executes(ctx -> showCacheInfo()))
                .then(ClientCommands.literal("open")
                    .executes(ctx -> openCacheFolder()))
                .then(ClientCommands.literal("delete")
                    .executes(ctx -> deletePendingFile()))
                .then(ClientCommands.literal("clear")
                    .executes(ctx -> clearCache())));

            dispatcher.register(ClientCommands.literal("collins-yt")
                .executes(ctx -> showYouTubeInfo())
                .then(ClientCommands.literal("info")
                    .executes(ctx -> showYouTubeInfo()))
                .then(ClientCommands.literal("install")
                    .executes(ctx -> installYtdlp()))
                .then(ClientCommands.literal("update")
                    .executes(ctx -> updateYtdlp())));
        });
    }

    private static MutableComponent label(String key) {
        return Component.translatable(key).setStyle(Style.EMPTY.withColor(GRAY));
    }

    private static MutableComponent value(Object value) {
        return Component.literal(String.valueOf(value)).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE));
    }

    private static int showCacheInfo() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        VideoPlayer.CacheInfo info = VideoPlayer.getCacheInfo();

        Component msg = PREFIX.copy()
            .append(Component.translatable("text.collins.cache.title").setStyle(Style.EMPTY.withColor(GREEN)))
            .append(Component.literal("\n"))
            .append(label("text.collins.cache.folder"))
            .append(value(info.cacheDir()))
            .append(Component.literal("\n"))
            .append(label("text.collins.cache.files"))
            .append(Component.translatable("text.collins.cache.files_value", info.fileCount(), info.cacheSizeMb()).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
            .append(Component.literal("\n"))
            .append(label("text.collins.cache.free_space"))
            .append(Component.translatable("text.collins.cache.free_space_value", info.freeSpaceGb()).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
            .append(Component.literal("\n"))
            .append(label("text.collins.commands"))
            .append(Component.literal("/collins-cache open").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Component.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Component.literal("/collins-cache clear").setStyle(Style.EMPTY.withColor(RED)));

        client.player.sendSystemMessage(msg);
        return Command.SINGLE_SUCCESS;
    }

    private static int openCacheFolder() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        VideoPlayer.openCacheFolder();
        client.player.sendSystemMessage(PREFIX.copy().append(
            Component.translatable("text.collins.cache.opened").setStyle(Style.EMPTY.withColor(GREEN))));
        return Command.SINGLE_SUCCESS;
    }

    private static int deletePendingFile() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        String path = VideoScreenManager.getPendingDeletePath();
        if (path == null || path.isEmpty()) {
            VideoScreen screen = VideoScreenManager.findNearestPlayingOrEnded(client.player.position());
            if (screen != null && screen.hasCachedFile()) {
                path = screen.getCachedFilePath();
            }
        }

        if (path == null || path.isEmpty()) {
            client.player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.cache.no_file_to_delete").setStyle(Style.EMPTY.withColor(RED))));
            return 0;
        }

        boolean deleted = VideoPlayer.deleteCachedFile(path);
        if (deleted) {
            VideoScreenManager.clearPendingDeletePath();
            client.player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.cache.deleted").setStyle(Style.EMPTY.withColor(GREEN))));
        } else {
            client.player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.cache.delete_failed", path).setStyle(Style.EMPTY.withColor(RED))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int clearCache() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        long deleted = VideoPlayer.clearCache();
        long deletedMb = deleted / (1024L * 1024L);

        VideoScreenManager.clearDeletePromptHistory();

        client.player.sendSystemMessage(PREFIX.copy().append(
            Component.translatable("text.collins.cache.cleared", deletedMb).setStyle(Style.EMPTY.withColor(GREEN))));
        return Command.SINGLE_SUCCESS;
    }

    private static int showTimeline(String screenName) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        VideoScreen screen;
        if (screenName != null && !screenName.isBlank()) {
            screen = VideoScreenManager.getByName(screenName);
        } else {
            screen = VideoScreenManager.findNearestPlaying(client.player.position());
        }

        if (screen == null) {
            client.player.sendSystemMessage(PREFIX.copy().append(Component.translatable("text.collins.timeline.no_active_screen").withStyle(ChatFormatting.RED)));
            return 0;
        }

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMsForDisplay(serverNowMs);
        long durMs = screen.durationMs();

        Component msg = durMs > 0
            ? Component.translatable("text.collins.timeline.full", screen.state().name(), TimeFormatUtil.formatMs(posMs), TimeFormatUtil.formatMs(durMs))
            : Component.translatable("text.collins.timeline.single", screen.state().name(), TimeFormatUtil.formatMs(posMs));

        client.player.sendSystemMessage(PREFIX.copy().append(msg.copy().setStyle(Style.EMPTY.withColor(GREEN))));
        return Command.SINGLE_SUCCESS;
    }

    private static int showYouTubeInfo() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        boolean available = YouTubeResolver.isYtdlpAvailable();
        String version = available ? YouTubeResolver.getYtdlpVersion() : Component.translatable("text.collins.youtube.not_installed").getString();
        boolean downloading = YouTubeResolver.isDownloading();
        int progress = YouTubeResolver.getDownloadProgress();
        CollinsClientConfig cfg = CollinsClientConfig.get();

        Component msg = PREFIX.copy()
            .append(Component.translatable("text.collins.youtube.info_title").setStyle(Style.EMPTY.withColor(GREEN)))
            .append(Component.literal("\n"))
            .append(label("text.collins.youtube.status"))
            .append(Component.translatable(available ? "text.collins.youtube.installed" : "text.collins.youtube.not_installed")
                .setStyle(Style.EMPTY.withColor(available ? GREEN : RED)))
            .append(Component.literal("\n"))
            .append(label("text.collins.youtube.version"))
            .append(Component.literal(available ? version : Component.translatable("text.collins.youtube.not_installed").getString())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
            .append(Component.literal("\n"))
            .append(label("text.collins.youtube.quality"))
            .append(Component.literal(YouTubeQuality.display(cfg.youtubeMaxQuality))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));

        if (downloading) {
            msg = msg.copy()
                .append(Component.literal("\n"))
                .append(label("text.collins.youtube.download"))
                .append(Component.translatable("text.collins.youtube.download.progress", progress).setStyle(Style.EMPTY.withColor(YELLOW)));
        }

        msg = msg.copy()
            .append(Component.literal("\n"))
            .append(label("text.collins.commands"))
            .append(Component.literal("/collinsc quality").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Component.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Component.literal("/collins-yt install").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Component.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Component.literal("/collins-yt update").setStyle(Style.EMPTY.withColor(YELLOW)));

        client.player.sendSystemMessage(msg);
        return Command.SINGLE_SUCCESS;
    }

    private static int showQuality() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        CollinsClientConfig cfg = CollinsClientConfig.get();
        client.player.sendSystemMessage(PREFIX.copy().append(
            Component.translatable("text.collins.youtube.quality.current", YouTubeQuality.display(cfg.youtubeMaxQuality))
                .setStyle(Style.EMPTY.withColor(GREEN))));
        return Command.SINGLE_SUCCESS;
    }

    private static int setQuality(String rawValue) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        Integer quality = YouTubeQuality.parse(rawValue);
        if (quality == null) {
            client.player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.youtube.quality.usage")
                    .setStyle(Style.EMPTY.withColor(RED))));
            return 0;
        }

        CollinsClientConfig cfg = CollinsClientConfig.get();
        cfg.youtubeMaxQuality = quality;
        CollinsClientConfig.save();
        VideoScreenManager.stopAllPlayback();

        client.player.sendSystemMessage(PREFIX.copy().append(
            Component.translatable("text.collins.youtube.quality.set", YouTubeQuality.display(cfg.youtubeMaxQuality))
                .setStyle(Style.EMPTY.withColor(GREEN))));
        return Command.SINGLE_SUCCESS;
    }

    private static int installYtdlp() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        if (YouTubeResolver.isYtdlpAvailable()) {
            client.player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.youtube.installed_version", YouTubeResolver.getYtdlpVersion())
                    .setStyle(Style.EMPTY.withColor(GREEN))));
            return Command.SINGLE_SUCCESS;
        }

        if (YouTubeResolver.isDownloading()) {
            client.player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.youtube.already_downloading", YouTubeResolver.getDownloadProgress())
                    .setStyle(Style.EMPTY.withColor(YELLOW))));
            return Command.SINGLE_SUCCESS;
        }

        client.player.sendSystemMessage(PREFIX.copy().append(
            Component.translatable("text.collins.youtube.installing").setStyle(Style.EMPTY.withColor(YELLOW))));

        YouTubeResolver.downloadYtdlpAsync();
        return Command.SINGLE_SUCCESS;
    }

    private static int updateYtdlp() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        if (!YouTubeResolver.isYtdlpAvailable()) {
            client.player.sendSystemMessage(PREFIX.copy().append(
                Component.translatable("text.collins.youtube.not_installed_use_install")
                    .setStyle(Style.EMPTY.withColor(RED))));
            return 0;
        }

        client.player.sendSystemMessage(PREFIX.copy().append(
            Component.translatable("text.collins.youtube.updating")
                .setStyle(Style.EMPTY.withColor(YELLOW))));

        Thread t = new Thread(() -> {
            boolean success = YouTubeResolver.updateYtdlp();
            Minecraft.getInstance().execute(() -> {
                if (client.player != null) {
                    if (success) {
                        client.player.sendSystemMessage(PREFIX.copy().append(
                            Component.translatable("text.collins.youtube.updated_version", YouTubeResolver.getYtdlpVersion())
                                .setStyle(Style.EMPTY.withColor(GREEN))));
                    } else {
                        client.player.sendSystemMessage(PREFIX.copy().append(
                            Component.translatable("text.collins.youtube.update_failed")
                                .setStyle(Style.EMPTY.withColor(RED))));
                    }
                }
            });
        }, "Collins-YtdlpUpdate");
        t.setDaemon(true);
        t.start();

        return Command.SINGLE_SUCCESS;
    }
}
