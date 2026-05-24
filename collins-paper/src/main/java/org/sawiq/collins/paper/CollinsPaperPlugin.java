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
import org.sawiq.collins.paper.selection.SelectionVisualizer;
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
    private CollinsClientMessageListener clientMessageListener;
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
        clientMessageListener = new CollinsClientMessageListener(this, moddedPlayers, moddedPlayerVersions,
                outdatedModdedPlayers, messenger, this::isClientModOutdated);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, "collins:main", clientMessageListener);

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

        // Memory watchdog. OFF by default (heapHighWatermark <= 0): the
        // primary defence is the hard-cap on our own caches (FFprobeUtil
        // CACHE_MAX_ENTRIES + the per-player maps swept once a minute),
        // which keep our footprint to well under a megabyte regardless
        // of server uptime. The watchdog is purely a safety net for
        // operators who want belt-and-suspenders. When enabled we
        // explicitly verify that OUR caches are big enough to be worth
        // clearing before doing anything — otherwise a global heap
        // spike caused by some unrelated plugin would silently dump our
        // cached durations and trigger a yt-dlp re-probe storm.
        double heapHighWatermark = getConfig().getDouble("memory.heapHighWatermark", 0.0);
        long minFootprintBytes = Math.max(0L,
                getConfig().getLong("memory.minOwnedHeapBytes", 10L * 1024L * 1024L));
        long memCheckIntervalMs = Math.max(1_000L,
                getConfig().getLong("memory.checkIntervalMs", 10_000L));
        boolean watchdogEnabled = heapHighWatermark >= 0.50 && heapHighWatermark <= 0.98;
        if (watchdogEnabled) {
            getLogger().info(String.format(
                    "Memory watchdog enabled: trip at heap >= %.0f%%, will only clear if Collins owns >= %d KB.",
                    heapHighWatermark * 100.0, minFootprintBytes / 1024L));
            Bukkit.getAsyncScheduler().runAtFixedRate(this,
                    t -> runMemoryWatchdog(heapHighWatermark, minFootprintBytes),
                    memCheckIntervalMs, memCheckIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

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

            // Watchdog: a URL we already know yt-dlp / ffprobe will
            // never resolve (PO token gate, "Video unavailable",
            // private/age-restricted, "Failed to extract", etc.) is not
            // worth keeping a server-side timeline for. The clients
            // just spin on "Preparing..." indefinitely while the server
            // ticks forward, which is exactly the symptom users see
            // when /collins seturl points at a dead link. Stop the
            // playback so /collins list reflects reality and the
            // server stops broadcasting an empty-track timeline.
            if (FFprobeUtil.isKnownPermanentlyDead(screen.mp4Url())) {
                getLogger().info("Auto-stopping screen '" + screen.name()
                        + "': URL is in the permanent-failure cache (yt-dlp/ffprobe gave up).");
                var updated = screen.withPlaying(false);
                store.put(updated);
                runtime.stopPlayback(screen.name());
                needBroadcast = true;
                continue;
            }

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
     * Pre-flight check for {@code /collins play} and friends. Returns
     * {@code null} if the URL is something we are willing to start a
     * playback timeline for, or a translation key suitable for
     * {@code lang.send(...)} explaining why we refuse.
     *
     * <p>Refusal is intentionally narrow:</p>
     * <ul>
     *   <li>{@code null} / blank — there is literally no URL set.</li>
     *   <li>Scheme that ffmpeg cannot speak ({@code file://},
     *       {@code gopher://}, raw filesystem path, leading dash —
     *       SSRF / arg-injection vectors).</li>
     *   <li>The URL is in {@code FFprobeUtil}'s permanent-failure cache
     *       — yt-dlp / ffprobe both already gave up with an
     *       unrecoverable marker (PO token gate, "Video unavailable",
     *       private/age-restricted, "Failed to extract any player
     *       response", etc.). Letting the player issue {@code play}
     *       again on the same dead link would just spin its HUD on
     *       "preparing" while the server timeline ticks forward
     *       feeding nothing — exactly the symptom users see today.</li>
     * </ul>
     *
     * <p>Transient failures, never-probed URLs, and any URL that
     * resolved to a non-zero duration last time are all allowed
     * through. We do NOT do a synchronous yt-dlp here: we never want
     * a chat command to block on a 30 s subprocess.</p>
     */
    public String checkPlayableUrl(String url) {
        if (url == null || url.isBlank()) {
            return "error.no_url";
        }
        String trimmed = url.trim();
        if (trimmed.charAt(0) == '-') {
            return "error.bad_url";
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        boolean schemeOk = lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("rtmp://")
                || lower.startsWith("rtmps://")
                || lower.startsWith("rtsp://");
        if (!schemeOk) {
            return "error.bad_url";
        }
        if (FFprobeUtil.isKnownPermanentlyDead(trimmed)) {
            return "error.dead_url";
        }
        return null;
    }

    /**
     * Two-phase playback start used by {@code /collins play} and
     * {@code /collins resume}.
     *
     * <p>Phase 1: tell the player we're validating the URL, push a
     * `cmd.validating` message after a short grace period (so a
     * cache-hit probe — instant — does not produce noise). Run
     * {@link FFprobeUtil#getDurationMs(String)} on the async
     * scheduler.</p>
     *
     * <p>Phase 2: when the probe completes, run a continuation on the
     * global region scheduler that updates {@link ScreenStore} /
     * {@link CollinsRuntimeState} and sends the success / failure
     * message. This keeps every Bukkit-state mutation on a server
     * thread (Folia-safe).</p>
     *
     * <p>If the probe returns 0 ms (yt-dlp / ffprobe both failed) we
     * refuse the start and tell the player. The URL is now in the
     * failure cache, so subsequent {@code /collins play} calls hit
     * the synchronous {@link #checkPlayableUrl(String)} path
     * instantly without waiting for another probe.</p>
     *
     * <p>If the probe returns a positive duration OR yt-dlp marked
     * the URL as live, we proceed with the start and broadcast a
     * sync packet to clients.</p>
     *
     * <p>Hard timeout: configurable via
     * {@code video.preflightTimeoutSeconds}, defaults to {@code 30 s}.
     * After that we either trust a cached positive answer or refuse,
     * regardless of whether yt-dlp is still running in the background.
     * Chat commands are not allowed to stall the server thread for
     * longer than that. The 30 s default covers slow CDNs (cinemap.cc,
     * paid streaming hosts) where the first byte can take 10-20 s
     * after a cold connect; the previous 8 s default refused those
     * URLs spuriously and made admins re-issue {@code /collins play}
     * two or three times before it stuck.</p>
     */
    public void startPlaybackAfterProbe(org.bukkit.entity.Player player, Screen screen, boolean fromZero) {
        String name = screen.name();
        String url = screen.mp4Url();

        // If we already have a positive duration cached for this URL,
        // skip the round-trip and start immediately. This is the common
        // case after the first /collins play has populated the cache.
        long cached = FFprobeUtil.getCachedDurationMs(url);
        boolean knownLive = FFprobeUtil.isKnownLive(url);
        if (cached > 0 || knownLive) {
            doStartPlaybackOnServerThread(player, screen, fromZero, "cached probe ok");
            return;
        }

        // Tell the player we're working. Use a one-tick delay so a
        // probe that completes instantly (e.g. cache miss, but the
        // probe is fast on this URL) does not produce a redundant
        // "validating" line above the "now playing" line.
        Bukkit.getAsyncScheduler().runDelayed(this, t -> {
            if (player.isOnline()) {
                lang.send(player, "cmd.validating", lang.vars("name", name, "url", url));
            }
        }, 800L, java.util.concurrent.TimeUnit.MILLISECONDS);

        // Run the probe asynchronously and dispatch the result back to
        // the global region scheduler so all store/runtime mutations
        // happen on a Bukkit thread (Folia-safe).
        // Probe timeout is clamped to a sensible range: 5 s minimum
        // (any less and we'd miss every yt-dlp warm start), 120 s
        // maximum (longer than that and the player will assume
        // /collins play is broken and try other things, racing the
        // probe against itself).
        long preflightTimeoutSeconds = Math.max(5L, Math.min(120L,
                getConfig().getLong("video.preflightTimeoutSeconds", 30L)));
        java.util.concurrent.CompletableFuture<Long> probeFuture = FFprobeUtil.getDurationMs(url);
        java.util.concurrent.CompletableFuture<Long> bounded = probeFuture
                .completeOnTimeout(0L, preflightTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);

        bounded.whenComplete((duration, ex) -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            // Re-fetch the screen to pick up any changes the player or
            // another admin may have made while the probe was running.
            Screen current = store.get(name);
            if (current == null) {
                if (player.isOnline()) {
                    lang.send(player, "error.screen_not_found", lang.vars("name", name));
                }
                return;
            }
            if (!java.util.Objects.equals(current.mp4Url(), url)) {
                // URL changed mid-probe; abort, the new URL needs its own
                // round of validation.
                if (player.isOnline()) {
                    lang.send(player, "cmd.validation_aborted",
                            lang.vars("name", name, "url", url));
                }
                return;
            }

            boolean live = FFprobeUtil.isKnownLive(url);
            long durationFromCache = FFprobeUtil.getCachedDurationMs(url);
            long resolvedDuration = duration != null && duration > 0
                    ? duration
                    : durationFromCache;

            if (resolvedDuration <= 0 && !live) {
                if (player.isOnline()) {
                    lang.send(player, "error.unplayable_url",
                            lang.vars("name", name, "url", url));
                }
                getLogger().info(player.getName() + " play '" + name
                        + "' rejected: probe returned no duration for " + url);
                return;
            }

            doStartPlaybackOnServerThread(player, current, fromZero, "probe ok");
        }));
    }

    private void doStartPlaybackOnServerThread(org.bukkit.entity.Player player, Screen s,
                                               boolean fromZero, String reason) {
        if (fromZero) {
            runtime.restartPlayback(s.name());
        } else {
            CollinsRuntimeState.Playback pb = runtime.get(s.name());
            pb.startEpochMs = System.currentTimeMillis();
        }

        Screen updated = new Screen(
                s.name(), s.world(),
                s.x1(), s.y1(), s.z1(),
                s.x2(), s.y2(), s.z2(),
                s.axis(),
                s.mp4Url(),
                true,
                s.loop(),
                s.volume(),
                s.youtubeQuality()
        );

        store.put(updated);
        store.save();
        prefetchDuration(updated);
        messenger.requestBroadcastSync();

        if (player.isOnline()) {
            String key = fromZero ? "cmd.playing" : "cmd.resumed";
            lang.send(player, key, lang.vars("name", s.name()));
        }
        getLogger().info(player.getName() + " "
                + (fromZero ? "play '" : "resume '") + s.name() + "' (" + reason + ")");
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
        if (clientMessageListener != null) {
            clientMessageListener.forgetMissingPlayers(online);
        }
        if (selection != null) {
            selection.forgetMissingPlayers(online);
        }
    }

    /**
     * Memory watchdog. OFF by default; opted into via
     * {@code memory.heapHighWatermark} in {@code config.yml}. When
     * enabled it does TWO checks before dumping anything:
     *
     * <ol>
     *   <li>Used heap crosses {@code highWatermark} (a fraction of
     *       {@code Runtime.maxMemory()}).</li>
     *   <li>Our own caches (estimated via
     *       {@link FFprobeUtil#estimatedFootprintBytes()} plus the
     *       size of the per-player and per-screen maps in this
     *       plugin) account for at least {@code minOwnedBytes}.</li>
     * </ol>
     *
     * <p>Without (2) a global heap spike caused by some other plugin
     * (a Dynmap render, a worldedit paste, EssentialsX item-frame
     * scanner, etc.) would silently dump our cached durations and
     * trigger a yt-dlp re-probe storm without actually freeing
     * meaningful memory. The cheap path is now: one
     * {@code Runtime.getRuntime()} read; only when over the
     * watermark do we walk our own maps to estimate ownership.</p>
     *
     * <p>Hysteresis: once tripped we wait until heap drops back below
     * {@code highWatermark - 0.10} before re-arming, so a heap that
     * lingers near the threshold does not produce a clear/log every
     * tick.</p>
     */
    private void runMemoryWatchdog(double highWatermark, long minOwnedBytes) {
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

        // Heap is high. Are WE actually responsible for any meaningful
        // chunk of it, or is some other plugin filling the heap and
        // we'd just be punishing ourselves by clearing our caches?
        long ownedBytes = estimateOwnedHeapBytes();
        if (ownedBytes < minOwnedBytes) {
            // Not our problem. Don't spam the log either — the throttle
            // below the trip line still applies.
            long now = System.currentTimeMillis();
            if (now - lastMemoryWatchdogLogAtMs > 10L * 60L * 1000L) {
                lastMemoryWatchdogLogAtMs = now;
                getLogger().info(String.format(
                        "Memory pressure detected (heap %.0f%% >= %.0f%%) but Collins only owns ~%d KB; not clearing.",
                        usedFraction * 100.0, highWatermark * 100.0, ownedBytes / 1024L));
            }
            return;
        }

        memoryWatchdogTripped = true;
        long now = System.currentTimeMillis();
        if (now - lastMemoryWatchdogLogAtMs > 10L * 60L * 1000L) {
            lastMemoryWatchdogLogAtMs = now;
            getLogger().warning(String.format(
                    "Memory pressure: heap at %.0f%% (>= %.0f%%); Collins owns ~%d KB, dropping caches to avoid OOM.",
                    usedFraction * 100.0, highWatermark * 100.0, ownedBytes / 1024L));
        }

        // Cheap, side-effect-only operations. None of these touch
        // Bukkit world state, so it's safe to run from the async
        // scheduler.
        FFprobeUtil.emergencyClear();
        lastDurationRequestAtMs.clear();
        lastDurationRequestUrl.clear();
        notifiedAdmins.clear();
    }

    /**
     * Order-of-magnitude estimate of how much heap the plugin's own
     * caches and bookkeeping maps occupy. Used by
     * {@link #runMemoryWatchdog(double, long)} to refuse to clear our
     * caches when global heap pressure is not our fault.
     */
    private long estimateOwnedHeapBytes() {
        long total = FFprobeUtil.estimatedFootprintBytes();
        // ~120 B per per-screen request entry + 2 chars per URL char.
        for (var e : lastDurationRequestUrl.entrySet()) {
            String url = e.getValue();
            total += 120L + (url != null ? 2L * url.length() : 0L);
        }
        // ~64 B per Long timestamp entry, keys are screen names.
        for (var e : lastDurationRequestAtMs.entrySet()) {
            total += 64L;
        }
        // ~32 B per UUID set entry. Cheap.
        total += notifiedAdmins.size() * 32L;
        return total;
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
        // Folia/Paper auto-cancel scheduled tasks owned by this plugin
        // when the JavaPlugin instance is disabled (BukkitScheduler /
        // GlobalRegionScheduler / AsyncScheduler all listen for the
        // PluginDisableEvent), so the periodic prune / memory-watchdog
        // / video-end-check tasks unwind themselves. We still need to:
        //   * cancel SelectionVisualizer tasks (they are owned by the
        //     player's region scheduler — auto-cancel on plugin
        //     disable applies, but the TASKS map stays populated and
        //     would be wrong after a /reload that re-enables the
        //     plugin in the same JVM).
        //   * persist on-disk state once more.
        // Caches are static and live with the JVM, so we don't try to
        // clear them: a /reload would lose useful probe history for
        // no benefit.
        SelectionVisualizer.stopAll();
        try {
            store.save();
        } catch (Exception e) {
            getLogger().warning("Failed to save screen store on disable: " + e.getMessage());
        }
        try {
            playlistStore.save();
        } catch (Exception e) {
            getLogger().warning("Failed to save playlist store on disable: " + e.getMessage());
        }
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
        if (clientMessageListener != null) {
            clientMessageListener.forgetPlayer(uuid);
        }
        // Cancel the selection-visualizer scheduled task and drop the
        // player's pos1/pos2 selection. Without this, a player who
        // started a selection but logged out mid-edit would leave a
        // ScheduledTask running for up to 60 s targeting a vanished
        // entity, plus a stale UUID -> Selection entry sitting in
        // SelectionService forever.
        SelectionVisualizer.stop(e.getPlayer());
        if (selection != null) {
            selection.forget(uuid);
        }
        if (moddedPlayers.remove(uuid)) {
            messenger.requestBroadcastSync();
        }
    }
}
