package org.sawiq.collins.paper;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.command.CollinsCommand;
import org.sawiq.collins.paper.model.Playlist;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.net.CollinsClientMessageListener;
import org.sawiq.collins.paper.net.CollinsMessenger;
import org.sawiq.collins.paper.selection.SelectionService;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.PlaylistStore;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.update.ModrinthVersionChecker;
import org.sawiq.collins.paper.util.FFprobeUtil;
import org.sawiq.collins.paper.util.Lang;
import org.sawiq.collins.paper.util.ToolsDownloader;
import org.sawiq.collins.paper.util.YouTubeQuality;

import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class CollinsPaperPlugin extends JavaPlugin implements Listener {

    private ScreenStore store;
    private PlaylistStore playlistStore;
    private CollinsMessenger messenger;
    private SelectionService selection;
    private CollinsRuntimeState runtime;
    private Lang lang;
    private CollinsCommand collinsCommand;
    private final Set<UUID> moddedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> moddedPlayerVersions = new ConcurrentHashMap<>();
    private final Set<UUID> outdatedModdedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastDurationRequestAtMs = new ConcurrentHashMap<>();
    private final Map<String, String> lastDurationRequestUrl = new ConcurrentHashMap<>();

    /**
     * Flag toggled by the memory watchdog when used heap crosses the
     * high-water mark. Used to throttle the warning log so we don't
     * spam every 10 s while heap stays elevated, and to skip the next
     * cache rebuild attempt until heap recovers.
     */
    private volatile boolean memoryWatchdogTripped = false;
    private volatile long lastMemoryWatchdogLogAtMs = 0L;

    /**
     * One-time Modrinth version probe. Result is reported to OP players on
     * join (each admin sees the message at most once per server lifetime,
     * tracked by {@link #notifiedAdmins}).
     */
    private ModrinthVersionChecker versionChecker;
    private volatile ModrinthVersionChecker.Result pendingUpdate;
    private volatile String latestFabricVersion;
    private final Set<UUID> notifiedAdmins = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String language = getConfig().getString("language", "en");
        lang = new Lang(this, language);

        store = new ScreenStore(this);
        store.load();

        playlistStore = new PlaylistStore(this);
        playlistStore.load();

        runtime = new CollinsRuntimeState();
        selection = new SelectionService();

        messenger = new CollinsMessenger(this, store, runtime, moddedPlayers, outdatedModdedPlayers);

        collinsCommand = new CollinsCommand(this, store, playlistStore, messenger, selection, runtime, lang);
        var pluginCmd = getCommand("collins");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(collinsCommand);
            pluginCmd.setTabCompleter(collinsCommand);
        } else {
            getLogger().severe("Command /collins not found in plugin.yml");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "collins:main");
        Bukkit.getMessenger().registerIncomingPluginChannel(this, "collins:main",
                new CollinsClientMessageListener(this, moddedPlayers, moddedPlayerVersions,
                        outdatedModdedPlayers, messenger, this::isClientModOutdated));

        ToolsDownloader.init(getLogger(), getDataFolder().toPath());
        if (getConfig().getBoolean("ffprobe.auto_download", true)) {
            ToolsDownloader.ensureToolsAsync();
        }

        String ffprobePath = getConfig().getString("ffprobe.path", "");
        String ytdlpPath = getConfig().getString("ffprobe.ytdlp", "");
        if (ffprobePath.isEmpty() || ffprobePath.equals("auto")) ffprobePath = ToolsDownloader.getFfprobePath();
        if (ytdlpPath.isEmpty() || ytdlpPath.equals("auto")) ytdlpPath = ToolsDownloader.getYtdlpPath();
        FFprobeUtil.init(getLogger(), ffprobePath, ytdlpPath, getConfig().getInt("ffprobe.timeout", 30));

        int endCheckIntervalTicks = Math.max(1, getConfig().getInt("video.endCheckIntervalTicks", 10));
        // Folia-safe: use the global region scheduler instead of the legacy
        // BukkitScheduler which is unsupported on Folia. On Paper this is a
        // thin shim that runs on the main server thread, so behavior is
        // identical.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, t -> checkVideoEndings(), endCheckIntervalTicks, endCheckIntervalTicks);

        int syncBroadcastIntervalTicks = Math.max(0, getConfig().getInt("video.syncBroadcastIntervalTicks", 20));
        if (syncBroadcastIntervalTicks > 0) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, t -> broadcastActiveSync(), syncBroadcastIntervalTicks, syncBroadcastIntervalTicks);
        }

        // Periodic memory hygiene. Without this the FFprobe duration /
        // failure caches and the per-player command rate-limit map grow
        // unboundedly on long-running servers (every fresh
        // `/collins seturl` adds one entry, and TTL eviction is lazy on
        // read). Runs every minute, off the main thread so the cleanup
        // sweep never blocks gameplay.
        long pruneIntervalTicks = 20L * 60L;
        Bukkit.getAsyncScheduler().runAtFixedRate(this, t -> {
            FFprobeUtil.pruneStaleEntries();
            pruneTransientPlayerState();
        }, pruneIntervalTicks * 50L, pruneIntervalTicks * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);

        // Memory watchdog: every 10 seconds, if used heap crosses the
        // configured high-water mark we drop ALL FFprobe caches and any
        // per-player bookkeeping that does not point at an online player.
        // This is the safety net for "the server is starting to thrash" —
        // we'd rather lose a few cached durations (they get re-probed on
        // demand) than OOM the JVM. Runs on the async scheduler with a
        // tiny tick budget (a single Runtime.getRuntime() call per pass
        // when below threshold) so it does not measurably load the box.
        double heapHighWatermark = Math.max(0.50, Math.min(0.98,
                getConfig().getDouble("memory.heapHighWatermark", 0.85)));
        long memCheckIntervalMs = Math.max(1_000L,
                getConfig().getLong("memory.checkIntervalMs", 10_000L));
        Bukkit.getAsyncScheduler().runAtFixedRate(this, t -> runMemoryWatchdog(heapHighWatermark),
                memCheckIntervalMs, memCheckIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        for (Screen screen : store.all()) {
            prefetchDuration(screen);
        }

        // Async Modrinth update probe. Result is delivered to OP players
        // when they join (one message per admin per server lifetime).
        versionChecker = new ModrinthVersionChecker(this);
        versionChecker.checkAsync().thenAccept(result -> {
            if (result == null) return;
            // Hop back to the main thread before logging - keeps us
            // consistent with how we touch any Bukkit state from async
            // tasks elsewhere in the plugin.
            Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                pendingUpdate = result;
                getLogger().info("Update available on Modrinth: "
                        + result.version() + " (current: "
                        + versionChecker.getCurrentVersion() + ")");
                // Notify any OP that is already online when the check
                // completes (e.g. server admin who started the server).
                for (var p : Bukkit.getOnlinePlayers()) {
                    if (p.isOp()) {
                        notifyAdminIfNeeded(p);
                    }
                }
            });
        });
        ModrinthVersionChecker.fetchLatestFabricAsync(getDescription().getVersion()).thenAccept(result -> {
            if (result == null || result.version() == null || result.version().isBlank()) {
                return;
            }
            Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                latestFabricVersion = result.version();
                refreshOutdatedModdedPlayers();
                messenger.requestBroadcastSync();
                getLogger().info("Latest Collins-Fabric version on Modrinth: " + latestFabricVersion);
            });
        });

        getLogger().info("collins-paper enabled. Loaded screens: " + store.all().size());
    }

    private boolean isClientModOutdated(String clientVersion) {
        String latest = latestFabricVersion;
        if (latest == null || latest.isBlank()) {
            return false;
        }
        if (clientVersion == null || clientVersion.isBlank() || clientVersion.equalsIgnoreCase("unknown")) {
            return true;
        }
        return ModrinthVersionChecker.isRemoteNewer(latest, clientVersion);
    }

    private void refreshOutdatedModdedPlayers() {
        outdatedModdedPlayers.clear();
        for (UUID uuid : moddedPlayers) {
            if (isClientModOutdated(moddedPlayerVersions.get(uuid))) {
                outdatedModdedPlayers.add(uuid);
            }
        }
    }

    private void notifyAdminIfNeeded(org.bukkit.entity.Player player) {
        ModrinthVersionChecker.Result update = pendingUpdate;
        if (update == null) return;
        if (!player.isOp()) return;
        if (!notifiedAdmins.add(player.getUniqueId())) return;

        Map<String, String> vars = lang.vars(
                "current", versionChecker != null ? versionChecker.getCurrentVersion() : "?",
                "version", update.version(),
                "url", update.url()
        );
        // Send each line as a separate chat message; lang.send already
        // handles \n splitting.
        lang.send(player, "update.notify", vars);
    }

    private void checkVideoEndings() {
        boolean needBroadcast = false;

        for (var screen : store.all()) {
            if (!screen.playing()) continue;
            if (screen.mp4Url() == null || screen.mp4Url().isBlank()) continue;

            long duration = runtime.getDurationMs(screen.name());

            if (duration <= 0) {
                requestDurationIfNeeded(screen);
            }

            if (!runtime.isVideoEnded(screen.name())) {
                continue;
            }

            if (advancePlaylist(screen)) {
                needBroadcast = true;
                continue;
            }

            if (screen.loop()) {
                runtime.restartPlayback(screen.name());
                needBroadcast = true;
                continue;
            }

            var updated = screen.withPlaying(false);
            store.put(updated);
            runtime.stopPlayback(screen.name());
            needBroadcast = true;
        }

        if (needBroadcast) {
            store.save();
            playlistStore.save();
            messenger.requestBroadcastSync();
        }
    }

    private void broadcastActiveSync() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        for (Screen screen : store.all()) {
            if (screen.playing() && screen.mp4Url() != null && !screen.mp4Url().isBlank()) {
                messenger.broadcastSync();
                return;
            }
        }
    }

    public int defaultYoutubeQuality() {
        return YouTubeQuality.sanitize(
                getConfig().getInt("video.defaultYouTubeQuality", YouTubeQuality.DEFAULT),
                YouTubeQuality.DEFAULT
        );
    }

    public void prefetchDuration(Screen screen) {
        if (screen == null) {
            return;
        }
        requestDurationIfNeeded(screen.name(), screen.mp4Url());
    }

    public void prefetchDuration(String screenName, String url) {
        requestDurationIfNeeded(screenName, url);
    }

    /**
     * Drops bookkeeping entries for a deleted screen so the
     * {@link #lastDurationRequestAtMs} / {@link #lastDurationRequestUrl}
     * maps don't slowly accumulate stale keys across the server's
     * lifetime. Called from {@code /collins remove}.
     */
    public void forgetDurationRequestState(String screenName) {
        if (screenName == null) return;
        String key = screenName.toLowerCase();
        lastDurationRequestAtMs.remove(key);
        lastDurationRequestUrl.remove(key);
    }

    /**
     * Drops bookkeeping entries that pertain to players who are no longer
     * online. Runs from the periodic prune task so even if {@code onQuit}
     * is missed (server crashed mid-disconnect, the listener throws on a
     * bad plugin state, etc.) we still bound the size of these maps.
     */
    private void pruneTransientPlayerState() {
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (var p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
        }
        notifiedAdmins.removeIf(uuid -> !online.contains(uuid));
        if (collinsCommand != null) {
            collinsCommand.forgetMissingPlayers(online);
        }
    }

    /**
     * Memory watchdog: when used heap crosses {@code highWatermark}
     * (a fraction in {@code (0,1)} of {@code Runtime.maxMemory()}) we
     * aggressively drop every cache the plugin owns. The duration cache
     * gets rebuilt on demand the next time {@code checkVideoEndings}
     * needs it, so the worst case is a brief spike in yt-dlp/ffprobe
     * subprocesses for active screens; the alternative is the JVM
     * OOMing under the plugin's caches, which on a public server would
     * take everyone down with it.
     *
     * <p>Cheap to call: the success path is a single
     * {@code Runtime.getRuntime()} read and an {@code if}. We only
     * touch caches when we actually trip.</p>
     *
     * <p>Hysteresis: once tripped we wait until heap drops back below
     * {@code highWatermark - 0.10} before re-arming, so a heap that
     * lingers near the threshold does not produce a clear/log every
     * 10 seconds.</p>
     */
    private void runMemoryWatchdog(double highWatermark) {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0 || max == Long.MAX_VALUE) {
            // -Xmx unbounded: nothing meaningful to compare against.
            return;
        }
        long used = rt.totalMemory() - rt.freeMemory();
        double usedFraction = (double) used / (double) max;

        if (memoryWatchdogTripped) {
            // Re-arm only after a 10 % drop below the trip line.
            if (usedFraction < (highWatermark - 0.10)) {
                memoryWatchdogTripped = false;
                getLogger().info(String.format(
                        "Memory pressure cleared: heap at %.0f%% (was above %.0f%%). Caches will repopulate on demand.",
                        usedFraction * 100.0, highWatermark * 100.0));
            }
            return;
        }

        if (usedFraction < highWatermark) return;

        memoryWatchdogTripped = true;
        long now = System.currentTimeMillis();
        // Throttle the warning to at most once per 10 minutes even if
        // the watchdog keeps tripping.
        if (now - lastMemoryWatchdogLogAtMs > 10L * 60L * 1000L) {
            lastMemoryWatchdogLogAtMs = now;
            getLogger().warning(String.format(
                    "Memory pressure: heap at %.0f%% (>= %.0f%%); dropping Collins caches to avoid OOM.",
                    usedFraction * 100.0, highWatermark * 100.0));
        }

        // Cheap, side-effect-only operations. None of these touch
        // Bukkit world state, so it's safe to run from the async
        // scheduler.
        FFprobeUtil.emergencyClear();
        lastDurationRequestAtMs.clear();
        lastDurationRequestUrl.clear();
        // Don't touch moddedPlayers / outdatedModdedPlayers — those
        // are the wire-protocol source of truth and re-deriving them
        // costs more than they weigh. notifiedAdmins is rebuilt
        // on next join attempt anyway.
        notifiedAdmins.clear();
    }

    private void requestDurationIfNeeded(Screen screen) {
        if (screen == null) {
            return;
        }
        requestDurationIfNeeded(screen.name(), screen.mp4Url());
    }

    private void requestDurationIfNeeded(String screenName, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }

        if (FFprobeUtil.isKnownLive(url)) {
            return;
        }

        long cachedDuration = FFprobeUtil.getCachedDurationMs(url);
        if (cachedDuration > 0) {
            runtime.setDurationFromServer(screenName, cachedDuration);
            return;
        }

        String screenKey = screenName.toLowerCase();
        long now = System.currentTimeMillis();
        long lastReq = lastDurationRequestAtMs.getOrDefault(screenKey, 0L);
        String lastUrl = lastDurationRequestUrl.get(screenKey);
        if (Objects.equals(lastUrl, url) && now - lastReq < 10_000L) {
            return;
        }

        lastDurationRequestAtMs.put(screenKey, now);
        lastDurationRequestUrl.put(screenKey, url);
        final String requestedUrl = url;
        FFprobeUtil.getDurationMs(url).thenAccept(durationMs -> {
            if (durationMs <= 0) {
                return;
            }
            Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                Screen current = store.get(screenName);
                if (current == null) {
                    return;
                }
                if (!Objects.equals(current.mp4Url(), requestedUrl)) {
                    return;
                }
                runtime.setDurationFromServer(screenName, durationMs);
            });
        });
    }

    private boolean advancePlaylist(Screen screen) {
        Playlist playlist = Playlist.get(screen.name());
        if (playlist == null || !playlist.isEnabled() || playlist.isEmpty()) {
            return false;
        }

        Playlist.PlaylistEntry nextEntry = playlist.next();
        if (nextEntry == null) {
            return false;
        }

        boolean sameUrl = Objects.equals(screen.mp4Url(), nextEntry.url());

        Screen updated = new Screen(
                screen.name(), screen.world(),
                screen.x1(), screen.y1(), screen.z1(),
                screen.x2(), screen.y2(), screen.z2(),
                screen.axis(),
                nextEntry.url(),
                true,
                false,
                screen.volume(),
                screen.youtubeQuality()
        );

        runtime.restartPlayback(screen.name(), sameUrl);
        store.put(updated);
        prefetchDuration(updated);
        return true;
    }

    public Lang lang() {
        return lang;
    }

    public ScreenStore getStore() {
        return store;
    }

    public CollinsRuntimeState getRuntime() {
        return runtime;
    }

    @Override
    public void onDisable() {
        store.save();
        playlistStore.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Per-player delayed tasks must run on the entity scheduler so that
        // Folia dispatches them to the player's current region. On Paper
        // this is a shim that behaves identically to runTaskLater.
        var player = e.getPlayer();
        player.getScheduler().runDelayed(this, t -> messenger.sendSync(player), null, 20L);

        // Notify the OP about a pending Modrinth update once per
        // server lifetime. Delayed two seconds so the message lands AFTER
        // Paper's own join messages (otherwise it can be hidden by the
        // motd / spawn chat scroll).
        player.getScheduler().runDelayed(this, t -> {
            if (player.isOnline()) {
                notifyAdminIfNeeded(player);
            }
        }, null, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        moddedPlayerVersions.remove(uuid);
        outdatedModdedPlayers.remove(uuid);
        // The "you have a Modrinth update" notification is shown at most
        // once per server lifetime per player; the bookkeeping entry is
        // only useful while the player is online, so drop it on quit
        // instead of letting it accumulate forever.
        notifiedAdmins.remove(uuid);
        // Per-player command rate limit timestamps are equally pointless
        // once the player is gone; without this `lastCommandAtMs` would
        // keep one entry per player who ever ran `/collins ...`.
        if (collinsCommand != null) {
            collinsCommand.forgetPlayer(uuid);
        }
        if (moddedPlayers.remove(uuid)) {
            messenger.requestBroadcastSync();
        }
    }
}
