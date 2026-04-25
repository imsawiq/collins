package org.sawiq.collins.fabric.client.video;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Direct Twitch HLS stream resolver.
 *
 * <p>Talks to the same public GraphQL endpoint that twitch.tv uses internally,
 * obtains a {@code PlaybackAccessToken} and builds the master HLS playlist URL
 * from {@code usher.ttvnw.net}. This avoids the heavy yt-dlp invocation
 * entirely (saves ~10-20s on cold start), and lets us pin a specific quality
 * variant up-front, which sidesteps mid-stream ABR transitions that have been
 * a source of green-stripe artefacts in the player.</p>
 *
 * <p>Returns {@code null} on any failure so callers can fall back to yt-dlp.</p>
 */
public final class TwitchStreamClient {
    /** Public web client id used by twitch.tv. */
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String USHER_URL_FMT = "https://usher.ttvnw.net/api/channel/hls/%s.m3u8";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * Persisted-query SHA256 for {@code PlaybackAccessToken}, mirroring
     * upstream streamlink's twitch plugin. Twitch caches persisted queries
     * server-side and rejects unknown ad-hoc inline queries on the same
     * operationName, so using the persisted hash matches the canonical
     * twitch.tv web client request and avoids any ad-trigger heuristics
     * triggered by non-canonical query bodies.
     */
    private static final String GQL_HASH =
        "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9";

    private static final boolean DEBUG = true;

    private static void dbg(String msg) {
        if (!DEBUG) return;
        try {
            System.out.println("[CollinsTwitch] " + msg);
        } catch (Exception ignored) {
        }
    }

    private TwitchStreamClient() {
    }

    /**
     * Resolves a playable HLS playlist URL for a Twitch channel.
     *
     * @param channel         channel login name (case-insensitive).
     * @param preferredHeight desired video height in pixels.
     * @return playable playlist URL, or {@code null} on failure.
     */
    public static String resolveHlsUrl(String channel, int preferredHeight) {
        if (channel == null || channel.isBlank()) {
            dbg("resolveHlsUrl: blank channel");
            return null;
        }
        String login = channel.trim().toLowerCase(Locale.ROOT);
        if (!login.matches("[a-z0-9_]+")) {
            dbg("resolveHlsUrl: invalid login '" + login + "'");
            return null;
        }

        // Retry up to 3 times: a fresh TCP/TLS socket sometimes survives
        // where the previous one was RST-ed (DPI middleboxes track first
        // packet of each flow). A short backoff helps the kernel rotate
        // ephemeral source ports.
        AccessToken token = null;
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                token = fetchAccessToken(login);
                if (token != null) break;
            } catch (IOException ioe) {
                lastError = ioe;
                dbg("resolveHlsUrl: " + ioe.getClass().getSimpleName()
                    + " attempt " + attempt + " (" + ioe.getMessage() + ")");
            } catch (Exception e) {
                dbg("resolveHlsUrl: exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        if (token == null) {
            dbg("resolveHlsUrl: no access token for " + login
                + (lastError != null ? " (last=" + lastError.getClass().getSimpleName() + ")" : ""));
            return null;
        }

        try {
            String master = buildMasterUrl(login, token);
            String variant = HlsVariantPicker.pick(master, preferredHeight, USER_AGENT);
            String chosen = (variant != null && !variant.equals(master)) ? variant : master;
            // Skip Twitch pre-roll: poll the variant playlist until at least
            // one non-ad segment is present. The ad-stripping HLS proxy
            // produces a 0-segment playlist while the live window is 100%
            // pre-roll, and FFmpeg's HLS demuxer cannot open such a
            // playlist - it fails the stream probe and the player loops on
            // playOnce() retrying every second. Streamlink does the same
            // wait: pre-roll silently delays the user-visible start by
            // <=30 s but content then plays cleanly.
            if (variant != null && !variant.equals(master)) {
                waitForContentSegments(chosen, 30_000);
            }
            dbg("resolveHlsUrl: " + (variant != null && !variant.equals(master)
                ? "variant" : "master") + " playlist used for " + login);
            return chosen;
        } catch (Exception e) {
            dbg("resolveHlsUrl: post-token exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Polls {@code variantUrl} until at least one non-ad segment is present
     * in the live window, or until {@code timeoutMs} has elapsed. Twitch
     * pre-rolls are typically 15-30 s; if the timeout fires we return anyway
     * so the caller can give FFmpeg a chance with whatever's there.
     *
     * <p>The poll interval is the playlist's target duration (typically 2 s)
     * which matches the rate at which Twitch refreshes the live window.</p>
     */
    private static void waitForContentSegments(String variantUrl, long timeoutMs) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/vnd.apple.mpegurl,*/*");
        headers.put("Origin", "https://www.twitch.tv");
        headers.put("Referer", "https://www.twitch.tv/");

        long deadline = System.currentTimeMillis() + timeoutMs;
        int polls = 0;
        while (System.currentTimeMillis() < deadline) {
            // VideoPlayer.stop() interrupts the playback thread; bail out
            // immediately so a stop() during pre-roll wait doesn't hang
            // the player UI for up to 30 s.
            if (Thread.currentThread().isInterrupted()) {
                dbg("waitForContent: thread interrupted, aborting");
                return;
            }
            polls++;
            String body;
            try {
                // Short timeouts (2s connect, 3s read): these are localhost-
                // adjacent (Twitch CDN ~50-150 ms) so anything longer means
                // network distress. Bounded waits also keep stop() snappy:
                // worst-case interrupt-to-return is one in-flight request
                // (<= 3 s) plus the chunked sleep below (<= 250 ms).
                body = SecureTlsClient.get(variantUrl, headers, 2_000, 3_000);
            } catch (Exception e) {
                dbg("waitForContent: fetch failed (" + e.getMessage() + "), giving up");
                return;
            }
            if (body == null) {
                // 502 / blocked - back off briefly, retry. Past 3 nulls
                // assume the URL is unreachable and let the caller handle.
                if (polls >= 3) {
                    dbg("waitForContent: 3x null fetches, giving up");
                    return;
                }
                if (!sleepMs(1_000)) return;
                continue;
            }
            TwitchHlsProxy.FilterResult fr = TwitchHlsProxy.filterAdsWithStats(body, variantUrl);
            if (fr.keptSegments() > 0) {
                // Seed the proxy's last-good cache so the FFmpeg-driven
                // first fetch never sees an empty playlist even if the
                // upstream live window has rolled into a fresh ad pod by
                // the time FFmpeg connects.
                TwitchHlsProxy.seedLastGoodPlaylist(variantUrl, fr.playlist());
                dbg("waitForContent: ok after " + polls + " poll(s), kept "
                    + fr.keptSegments() + " content segment(s)");
                return;
            }
            // Window is still 100% ads. Sleep approximately one segment
            // duration before re-polling.
            if (!sleepMs(1_500)) return;
        }
        dbg("waitForContent: timeout after " + polls + " poll(s), proceeding anyway");
    }

    /**
     * Sleep that yields to thread interrupt within at most 250 ms so
     * {@code VideoPlayer.stop()} can unwind the playback thread quickly
     * even if it's mid-pre-roll-wait. Returns {@code false} if interrupted.
     */
    private static boolean sleepMs(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return true;
            if (Thread.currentThread().isInterrupted()) return false;
            try {
                Thread.sleep(Math.min(250L, remaining));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private record AccessToken(String value, String signature) {
    }

    private static AccessToken fetchAccessToken(String channel) throws IOException {
        JsonObject variables = new JsonObject();
        variables.addProperty("isLive", true);
        variables.addProperty("login", channel);
        variables.addProperty("isVod", false);
        variables.addProperty("vodID", "");
        // playerType=embed + platform=site is the streamlink-tested combo
        // that minimises stitched ads. The web/mediaplayer player-backend
        // tied to platform=web has more aggressive Amazon-Ads insertion.
        variables.addProperty("playerType", "embed");
        variables.addProperty("platform", "site");

        // Persisted-query body, identical wire shape to twitch.tv's own
        // PlaybackAccessToken request.
        JsonObject persistedQuery = new JsonObject();
        persistedQuery.addProperty("version", 1);
        persistedQuery.addProperty("sha256Hash", GQL_HASH);
        JsonObject extensions = new JsonObject();
        extensions.add("persistedQuery", persistedQuery);

        JsonObject body = new JsonObject();
        body.addProperty("operationName", "PlaybackAccessToken");
        body.add("extensions", extensions);
        body.add("variables", variables);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Client-ID", CLIENT_ID);
        headers.put("Content-Type", "text/plain;charset=UTF-8");
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Origin", "https://www.twitch.tv");
        headers.put("Referer", "https://www.twitch.tv/");
        headers.put("User-Agent", USER_AGENT);
        headers.put("X-Device-Id", generateDeviceId());

        String response = SecureTlsClient.post(GQL_URL, payload, headers, 3_000, 5_000);
        if (response == null) return null;

        JsonElement root;
        try {
            root = JsonParser.parseString(response);
        } catch (Exception e) {
            dbg("fetchAccessToken: JSON parse error " + e.getMessage());
            return null;
        }
        if (!root.isJsonObject()) {
            dbg("fetchAccessToken: response not a JSON object");
            return null;
        }
        JsonObject obj = root.getAsJsonObject();

        if (obj.has("errors") && obj.get("errors").isJsonArray()) {
            String errs = obj.get("errors").toString();
            dbg("fetchAccessToken: GraphQL errors=" + (errs.length() > 200 ? errs.substring(0, 200) + "..." : errs));
        }

        JsonObject data = obj.getAsJsonObject("data");
        if (data == null) {
            dbg("fetchAccessToken: no data field");
            return null;
        }
        JsonElement tokenEl = data.get("streamPlaybackAccessToken");
        if (tokenEl == null || tokenEl.isJsonNull()) {
            dbg("fetchAccessToken: streamPlaybackAccessToken is null (channel offline?)");
            return null;
        }
        if (!tokenEl.isJsonObject()) {
            dbg("fetchAccessToken: streamPlaybackAccessToken is not an object");
            return null;
        }
        JsonObject token = tokenEl.getAsJsonObject();

        String value = optString(token, "value");
        String signature = optString(token, "signature");
        if (value == null || signature == null) {
            dbg("fetchAccessToken: missing value or signature");
            return null;
        }
        return new AccessToken(value, signature);
    }

    private static String generateDeviceId() {
        // 32 hex chars, like the Twitch web client.
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(hex[ThreadLocalRandom.current().nextInt(16)]);
        }
        return sb.toString();
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildMasterUrl(String channel, AccessToken token) {
        // Streamlink's UsherService._create_url emits exactly this set of
        // params - no extras. Anything beyond this (player_version, cdm,
        // reassignments_supported, fast_bread, ...) is just web-player
        // telemetry that Twitch may use to fingerprint a non-canonical
        // client and serve more aggressive ads.
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(USHER_URL_FMT, channel));
        sb.append("?platform=web");
        sb.append("&p=").append(ThreadLocalRandom.current().nextInt(1, 999_999));
        sb.append("&allow_source=true");
        sb.append("&allow_audio_only=true");
        sb.append("&playlist_include_framerate=true");
        sb.append("&supported_codecs=h264");
        sb.append("&sig=").append(URLEncoder.encode(token.signature(), StandardCharsets.UTF_8));
        sb.append("&token=").append(URLEncoder.encode(token.value(), StandardCharsets.UTF_8));
        return sb.toString();
    }

}
