package org.sawiq.collins.fabric.client.video;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Direct YouTube live stream resolver.
 *
 * <p>For live streams (and only those) we obtain {@code hlsManifestUrl}
 * directly from the public Innertube {@code /player} endpoint, bypassing
 * yt-dlp entirely. Startup is ~1s instead of the 3-6s yt-dlp needs and we
 * avoid the recurring "yt-dlp broken because YouTube changed something"
 * problem.</p>
 *
 * <p>The endpoint is queried with several client signatures in the same
 * order yt-dlp uses today (TV, WEB_SAFARI, MWEB, ANDROID). We deliberately
 * skip the IOS client because YouTube now requires a PO Token for IOS HLS,
 * and {@code TVHTML5_SIMPLY_EMBEDDED_PLAYER} which is rejected outright.</p>
 *
 * <p>Returns {@code null} for non-live videos or when every client failed;
 * callers must fall through to the existing yt-dlp flow.</p>
 */
public final class YouTubeLiveClient {
    /** Public Innertube key the youtube.com WEB client uses (works for every clientName). */
    private static final String INNERTUBE_KEY_WEB = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";

    private static final String INNERTUBE_URL_FMT =
        "https://www.youtube.com/youtubei/v1/player?key=%s&prettyPrint=false";

    /**
     * UA strings tied 1:1 to the corresponding {@code clientName}/
     * {@code clientVersion} below. Mismatching them makes YouTube return
     * playabilityStatus=ERROR + reason "Precondition check failed".
     */
    private static final String UA_TV =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), "
            + "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)";
    private static final String UA_WEB_SAFARI =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)";
    private static final String UA_MWEB =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)";
    private static final String UA_ANDROID =
        "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip";

    /**
     * Reserved for callers that already expect a cookie string. The Innertube
     * flow does not need cookies because the manifest URL is signed end to
     * end (sig=, expire=, sparams=); googlevideo.com authenticates segments
     * by signature, not session cookies.
     */
    public static String pickVisitorCookie() {
        return null;
    }

    private YouTubeLiveClient() {
    }

    /**
     * @deprecated use {@link #resolveLiveHlsUrl(String, int)} so the helper
     *     can pin a specific quality variant. Without a height hint we fall
     *     back to handing the master playlist to FFmpeg, which leads to
     *     inconsistent quality picks and ABR mid-stream transitions.
     */
    @Deprecated
    public static String resolveLiveHlsUrl(String videoId) {
        return resolveLiveHlsUrl(videoId, 720);
    }

    /**
     * Returns a playable HLS playlist URL if the given video is a live stream,
     * otherwise {@code null}. Never throws.
     *
     * <p>The returned URL is the highest-resolution variant whose height
     * does not exceed {@code preferredHeight}; the master playlist is only
     * returned if variant selection fails entirely. Pinning a single variant
     * eliminates ABR transitions that produce green-stripe artefacts on the
     * decoder side.</p>
     */
    public static String resolveLiveHlsUrl(String videoId, int preferredHeight) {
        if (videoId == null || videoId.isBlank()) return null;

        dbg("resolveLiveHlsUrl: trying clients for videoId=" + videoId
            + " preferredHeight=" + preferredHeight);

        // Clients listed in the order yt-dlp uses for live HLS in 2026:
        // 1) TVHTML5 v7  - canonical TV/Cobalt client, returns HLS for live, no PO required.
        // 2) WEB_SAFARI  - Safari UA flips WEB to pre-merged HLS, no PO required for live.
        // 3) MWEB        - mobile web with iPad UA, HLS doesn't require PO.
        // 4) ANDROID v21 - native Android client, HLS doesn't require PO.
        //
        // We deliberately skip:
        //   * IOS - per yt-dlp/_base.py, HLS now REQUIRES a PO token, so the
        //           manifest will 403 on every segment.
        //   * TVHTML5_SIMPLY_EMBEDDED_PLAYER - rejected by current YouTube,
        //           returns playabilityStatus=ERROR "YouTube is no longer
        //           supported in this application or device".
        String[] clientNames = {
            "TV",
            "WEB_SAFARI",
            "MWEB",
            "ANDROID"
        };

        for (String name : clientNames) {
            try {
                String master = resolveViaInnertube(videoId, name);
                if (master != null) {
                    log("resolveLiveHlsUrl: success via " + name);
                    String variant = HlsVariantPicker.pick(master, preferredHeight, uaFor(name));
                    if (variant != null && !variant.equals(master)) {
                        return variant;
                    }
                    return master;
                }
            } catch (Exception e) {
                dbg("resolveLiveHlsUrl: " + name + " threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // Last-ditch fallback: the public watch page embeds the full player
        // response (ytInitialPlayerResponse) including hlsManifestUrl for
        // live streams, and is NOT subject to the Innertube client-version
        // checks that periodically break the API clients above. This keeps
        // live playback working even when YouTube rejects every API client.
        try {
            String fromPage = resolveViaWatchPage(videoId);
            if (fromPage != null) {
                log("resolveLiveHlsUrl: success via watch page");
                String variant = HlsVariantPicker.pick(fromPage, preferredHeight, UA_WEB_SAFARI);
                if (variant != null && !variant.equals(fromPage)) {
                    return variant;
                }
                return fromPage;
            }
        } catch (Exception e) {
            dbg("resolveLiveHlsUrl: watch page fallback threw " + e.getMessage());
        }

        log("resolveLiveHlsUrl: not a live stream / all clients rejected");
        return null;
    }

    /**
     * Cheap live-status probe used by the resolver's failure paths to decide
     * whether a video may be routed through the VOD download pipeline.
     * Returns {@code Boolean.TRUE} if the watch page says the video is live,
     * {@code Boolean.FALSE} if it positively says it is not, and {@code null}
     * when the page could not be fetched/parsed (caller should stay
     * optimistic and fall back to its existing behaviour).
     *
     * <p>This matters because routing a live stream into the yt-dlp VOD
     * download path makes yt-dlp record the stream forever - the user sees
     * an infinite "preparing video" phase that never completes.</p>
     */
    public static Boolean checkLiveStatus(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        try {
            String html = fetchWatchPage(videoId);
            if (html == null || html.isBlank()) return null;
            boolean live = html.contains("\"isLive\":true")
                || html.contains("\"isLiveNow\":true")
                || (html.contains("\"isLiveContent\":true") && html.contains("hlsManifestUrl"));
            if (live) return Boolean.TRUE;
            // Only report a confident "not live" when the player response is
            // actually present; consent walls / bot pages must yield null.
            if (html.contains("ytInitialPlayerResponse")) return Boolean.FALSE;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts {@code hlsManifestUrl} from the public watch page, or null. */
    private static String resolveViaWatchPage(String videoId) throws IOException {
        String html = fetchWatchPage(videoId);
        if (html == null || html.isBlank()) return null;

        int idx = html.indexOf("\"hlsManifestUrl\":\"");
        if (idx < 0) return null;
        int start = idx + "\"hlsManifestUrl\":\"".length();
        int end = html.indexOf('"', start);
        if (end <= start) return null;

        String url = html.substring(start, end)
            .replace("\\/", "/")
            .replace("\\u0026", "&");
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") || !lower.contains("googlevideo.com")) {
            dbg("resolveViaWatchPage: rejected manifest url " + url);
            return null;
        }
        return url;
    }

    private static String fetchWatchPage(String videoId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
            "https://www.youtube.com/watch?v=" + videoId + "&hl=en").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA_WEB_SAFARI);
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI");
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                dbg("fetchWatchPage: HTTP " + code);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                // 2.5 MB is plenty: ytInitialPlayerResponse sits near the top
                // of the document, well within the first megabyte.
                byte[] buf = in.readNBytes(2_500_000);
                return new String(buf, StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** UA the variant playlist fetch should use to match the client that produced the master. */
    private static String uaFor(String clientName) {
        return switch (clientName) {
            case "TV" -> UA_TV;
            case "WEB_SAFARI" -> UA_WEB_SAFARI;
            case "MWEB" -> UA_MWEB;
            case "ANDROID" -> UA_ANDROID;
            default -> null;
        };
    }

    /** Debug toggle for per-client innertube tracing. Off in production. */
    private static final boolean DEBUG = false;

    private static void dbg(String msg) {
        if (!DEBUG) return;
        log(msg);
    }

    private static void log(String msg) {
        try {
            System.out.println("[CollinsYTLive] " + msg);
        } catch (Exception ignored) {
        }
    }

    /**
     * Calls the public Innertube /player endpoint with one of the embedded /
     * mobile client signatures. Returns the {@code hlsManifestUrl} if the
     * response contains one, else {@code null}.
     */
    private static String resolveViaInnertube(String videoId, String clientName) throws IOException {
        JsonObject client = new JsonObject();
        String userAgent;
        String apiKey;
        boolean isMobile = false;

        // Versions/UAs are mirrored from yt-dlp/yt_dlp/extractor/youtube/_base.py
        // (master, 2026.01). YouTube enforces minimum versions and rejects
        // the request with playabilityStatus=ERROR otherwise.
        switch (clientName) {
            case "TV" -> {
                client.addProperty("clientName", "TVHTML5");
                client.addProperty("clientVersion", "7.20260114.12.00");
                userAgent = UA_TV;
                apiKey = INNERTUBE_KEY_WEB;
            }
            case "WEB_SAFARI" -> {
                // Safari UA flips WEB into pre-merged HLS mode (yt-dlp comment
                // in _base.py). Live streams expose hlsManifestUrl exactly as
                // for TVHTML5, but with a different signature scheme.
                client.addProperty("clientName", "WEB");
                client.addProperty("clientVersion", "2.20260114.08.00");
                client.addProperty("userAgent", UA_WEB_SAFARI);
                userAgent = UA_WEB_SAFARI;
                apiKey = INNERTUBE_KEY_WEB;
            }
            case "MWEB" -> {
                client.addProperty("clientName", "MWEB");
                client.addProperty("clientVersion", "2.20260115.01.00");
                client.addProperty("userAgent", UA_MWEB);
                userAgent = UA_MWEB;
                apiKey = INNERTUBE_KEY_WEB;
            }
            case "ANDROID" -> {
                client.addProperty("clientName", "ANDROID");
                client.addProperty("clientVersion", "21.02.35");
                client.addProperty("androidSdkVersion", 30);
                client.addProperty("osName", "Android");
                client.addProperty("osVersion", "11");
                userAgent = UA_ANDROID;
                apiKey = INNERTUBE_KEY_WEB;
                isMobile = true;
            }
            default -> throw new IllegalArgumentException("Unknown client: " + clientName);
        }
        client.addProperty("hl", "en");
        client.addProperty("gl", "US");

        JsonObject ctx = new JsonObject();
        ctx.add("client", client);

        JsonObject body = new JsonObject();
        body.add("context", ctx);
        body.addProperty("videoId", videoId);
        body.addProperty("contentCheckOk", true);
        body.addProperty("racyCheckOk", true);

        return postInnertube(apiKey, body, userAgent, isMobile, clientName);
    }

    private static String postInnertube(String apiKey, JsonObject body, String userAgent, boolean isMobile, String clientName) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(String.format(INNERTUBE_URL_FMT, apiKey)).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Cookie", "CONSENT=YES+1");
        if (!isMobile) {
            conn.setRequestProperty("Origin", "https://www.youtube.com");
            conn.setRequestProperty("Referer", "https://www.youtube.com/");
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            out.write(payload);
        }

        int code;
        String response;
        try {
            code = conn.getResponseCode();
            try (InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                response = (in == null) ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }

        if (code != 200) {
            String snippet = response.length() > 200 ? response.substring(0, 200) : response;
            dbg(clientName + ": HTTP " + code + " body=" + snippet.replace('\n', ' '));
            return null;
        }

        try {
            JsonElement root = JsonParser.parseString(response);
            if (!root.isJsonObject()) {
                log(clientName + ": response is not a JSON object");
                return null;
            }
            JsonObject obj = root.getAsJsonObject();

            // Surface playability status so we know if the stream is geo-locked,
            // age-gated, or something similar.
            String playStatus = null;
            try {
                JsonObject ps = obj.getAsJsonObject("playabilityStatus");
                if (ps != null) {
                    JsonElement status = ps.get("status");
                    if (status != null && !status.isJsonNull()) {
                        playStatus = status.getAsString();
                        if (!"OK".equals(playStatus)) {
                            JsonElement reason = ps.get("reason");
                            dbg(clientName + ": playabilityStatus=" + playStatus
                                + (reason != null && !reason.isJsonNull() ? " reason=" + reason.getAsString() : ""));
                            return null;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // Capture videoData.is_live so we don't accidentally hand a VOD's
            // pseudo-HLS URL to FFmpeg (those manifests are pre-finalised and
            // confuse the live HLS demuxer flags we set in VideoPlayer).
            try {
                JsonObject vd = obj.getAsJsonObject("videoDetails");
                if (vd != null) {
                    JsonElement isLive = vd.get("isLive");
                    JsonElement isLiveContent = vd.get("isLiveContent");
                    boolean live = (isLive != null && !isLive.isJsonNull() && isLive.getAsBoolean())
                        || (isLiveContent != null && !isLiveContent.isJsonNull() && isLiveContent.getAsBoolean());
                    if (!live) {
                        dbg(clientName + ": videoDetails says not a live stream");
                        return null;
                    }
                }
            } catch (Exception ignored) {
            }

            JsonObject streamingData = obj.getAsJsonObject("streamingData");
            if (streamingData == null) {
                dbg(clientName + ": no streamingData in response");
                return null;
            }
            JsonElement hls = streamingData.get("hlsManifestUrl");
            if (hls == null || hls.isJsonNull()) {
                dbg(clientName + ": streamingData has no hlsManifestUrl (probably VOD/non-live)");
                return null;
            }
            String url = hls.getAsString();
            if (url == null || url.isBlank()) {
                dbg(clientName + ": hlsManifestUrl is empty");
                return null;
            }
            // A valid live manifest URL must contain googlevideo.com host AND a
            // signature parameter (sig=, sparams=) issued by Google. Manifests
            // returned by stale clients sometimes look superficially OK but lack
            // signatures, which makes the CDN 403 every segment fetch.
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.contains("googlevideo.com")) {
                dbg(clientName + ": hlsManifestUrl host is not googlevideo.com - " + url);
                return null;
            }
            if (!lower.contains("/sig/") && !lower.contains("sig=") && !lower.contains("sparams=")) {
                dbg(clientName + ": hlsManifestUrl missing signature params - probably stub");
                return null;
            }
            return url;
        } catch (Exception e) {
            dbg(clientName + ": JSON parse error " + e.getMessage());
            return null;
        }
    }
}
