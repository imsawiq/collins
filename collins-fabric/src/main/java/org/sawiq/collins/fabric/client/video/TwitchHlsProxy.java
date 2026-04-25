package org.sawiq.collins.fabric.client.video;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local HTTP proxy that strips Twitch stitched ads from a live HLS variant
 * playlist before handing it to FFmpeg.
 *
 * <p>Twitch serves pre-roll and mid-roll ads by splicing them straight into
 * the live segment list, marked by an HLS {@code #EXT-X-DATERANGE} tag with
 * {@code CLASS="twitch-stitched-ad"}. FFmpeg has no way to skip them on its
 * own, so the user sees a "Commercial" overlay (and sometimes a black frame
 * with only ad audio) for the first 30-60 s of every join.</p>
 *
 * <p>The fix used by streamlink, ttv-lol, purpleadblock and every browser
 * extension that targets Twitch ads is to filter the m3u8 playlist itself:
 * detect the ad daterange marker, then drop every segment URI / EXTINF /
 * PROGRAM-DATE-TIME pair until the matching {@code #EXT-X-DISCONTINUITY}
 * that closes the ad block. The result is a playlist with the ads spliced
 * out; the player jumps straight to live content.</p>
 *
 * <p>This proxy runs in-process on {@code 127.0.0.1:&lt;random&gt;} and
 * exposes a single endpoint {@code /proxy.m3u8?u=BASE64_URL}. It only
 * forwards .m3u8 fetches (segments are absolute URLs in the cleaned
 * playlist and FFmpeg fetches them straight from Twitch CDN).</p>
 */
public final class TwitchHlsProxy {
    private static final Object LOCK = new Object();
    private static volatile HttpServer server;
    private static volatile int port = -1;

    /** UA that the upstream playlist fetch will send; mirrors a normal browser. */
    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private TwitchHlsProxy() {
    }

    /**
     * Lazily start the local proxy and return a wrapped URL that, when fetched
     * by FFmpeg, will return an ad-stripped version of {@code upstreamUrl}.
     * Returns the original URL unchanged if the proxy cannot be started for
     * any reason (caller falls through to the unfiltered stream).
     */
    public static String wrap(String upstreamUrl) {
        if (upstreamUrl == null || upstreamUrl.isBlank()) return upstreamUrl;
        if (!upstreamUrl.toLowerCase().endsWith(".m3u8") && !upstreamUrl.toLowerCase().contains(".m3u8?")) {
            // Not a playlist URL - we can't ad-strip a single MP4 / segment.
            log("wrap: not a m3u8 url, passing through: " + upstreamUrl);
            return upstreamUrl;
        }
        if (!ensureStarted()) {
            log("wrap: proxy server unavailable, passing through");
            return upstreamUrl;
        }
        String b64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(upstreamUrl.getBytes(StandardCharsets.UTF_8));
        return "http://127.0.0.1:" + port + "/proxy.m3u8?u=" + b64;
    }

    private static boolean ensureStarted() {
        if (server != null) return true;
        synchronized (LOCK) {
            if (server != null) return true;
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
                s.createContext("/proxy.m3u8", TwitchHlsProxy::handle);
                // Daemon executor: small thread pool, dies with the JVM. We
                // never explicitly stop the proxy because there's no clean
                // hook (multiple players may share it) and it costs ~0 RAM
                // when idle.
                s.setExecutor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "Collins-TwitchProxy");
                    t.setDaemon(true);
                    return t;
                }));
                s.start();
                server = s;
                port = s.getAddress().getPort();
                log("proxy started on 127.0.0.1:" + port);
                return true;
            } catch (Exception e) {
                log("proxy start failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String query = ex.getRequestURI().getRawQuery();
            String upstream = decodeUrlParam(query, "u");
            if (upstream == null) {
                respond(ex, 400, "missing ?u=BASE64");
                return;
            }
            String text = fetch(upstream);
            if (text == null) {
                respond(ex, 502, "upstream fetch failed");
                return;
            }
            FilterResult fr = filterAdsWithStats(text, upstream);
            String filtered = fr.playlist;
            // If the entire current live window is pre-roll ads, replay
            // the last known good playlist (if any) so FFmpeg has segments
            // to advance through. Without this fallback FFmpeg sees an
            // empty playlist, logs "Empty segment", and the upstream
            // resolver loops re-fetching yt-dlp.
            if (fr.keptSegments == 0) {
                String cached = LAST_GOOD_PLAYLIST.get(upstream);
                if (cached != null) {
                    log("filterAds: entire window is ads - replaying cached "
                        + cached.length() + "B playlist for " + shortUrl(upstream));
                    filtered = cached;
                }
            } else {
                LAST_GOOD_PLAYLIST.put(upstream, filtered);
            }
            // Dump first few playlist samples to disk for diagnostic
            // analysis. Helps identify edge cases where Twitch invents new
            // ad markers or formats. Bounded so we don't fill the disk.
            dumpPair(text, filtered);

            byte[] body = filtered.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        } catch (Exception e) {
            log("handle error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                respond(ex, 500, "proxy error");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Diagnostic dumps - writes the first {@value #DUMP_LIMIT} input/output
     * playlist pairs of each JVM session to {@code %TEMP%/collins-twitch-proxy/}.
     * Lets the user share concrete samples when ad detection fails. Bounded
     * so we don't fill disk when streaming continuously.
     *
     * <p>Off by default in production. Enable for a single run via
     * {@code -Dcollins.twitch.dump=true}.</p>
     */
    private static final int DUMP_LIMIT = 6;
    private static final boolean DUMP_ENABLED =
        Boolean.parseBoolean(System.getProperty("collins.twitch.dump", "false"));
    private static final AtomicInteger DUMP_COUNTER = new AtomicInteger(0);
    private static volatile Path DUMP_DIR;

    /**
     * Cache of the last filtered playlist that contained at least one
     * non-ad segment, keyed by the upstream URL. When Twitch is mid-pre-roll
     * and the entire current live window is ads, we'd otherwise return an
     * empty playlist that FFmpeg flags as "Empty segment" and treats as
     * fatal. Returning the most recent good playlist instead lets FFmpeg
     * keep polling the proxy until real content arrives, with the same
     * media-sequence so segments don't get re-played.
     *
     * <p>Bounded LRU: every Twitch session emits a different upstream URL
     * (Usher embeds a random {@code &p=&lt;1..1M&gt;}), so an unbounded map
     * would grow ~10 KB per Play across the JVM lifetime. {@value #CACHE_MAX}
     * is enough to cover a few concurrent screens without surprises.</p>
     */
    private static final int CACHE_MAX = 4;
    @SuppressWarnings("serial")
    private static final java.util.Map<String, String> LAST_GOOD_PLAYLIST =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    /**
     * Pre-populate the last-good cache for {@code upstream} with a known
     * non-ad playlist. Called by {@link TwitchStreamClient} after its
     * pre-roll wait finds a content-bearing playlist - guarantees that the
     * proxy's first fetch (which races against the playlist refresh) has
     * a fallback even if it lands inside a fresh ad pod.
     */
    static void seedLastGoodPlaylist(String upstream, String filteredPlaylist) {
        if (upstream == null || filteredPlaylist == null) return;
        LAST_GOOD_PLAYLIST.put(upstream, filteredPlaylist);
    }

    private static Path dumpDir() {
        Path d = DUMP_DIR;
        if (d != null) return d;
        synchronized (TwitchHlsProxy.class) {
            d = DUMP_DIR;
            if (d != null) return d;
            try {
                Path base = Paths.get(System.getProperty("java.io.tmpdir", "."), "collins-twitch-proxy");
                Files.createDirectories(base);
                DUMP_DIR = base;
                log("dump dir: " + base);
                return base;
            } catch (Exception e) {
                log("dump dir create failed: " + e.getMessage());
                DUMP_DIR = Paths.get(".");
                return DUMP_DIR;
            }
        }
    }

    private static void dumpPair(String upstream, String filtered) {
        if (!DUMP_ENABLED) return;
        int n = DUMP_COUNTER.incrementAndGet();
        if (n > DUMP_LIMIT) return;
        try {
            Path d = dumpDir();
            long ts = System.currentTimeMillis();
            String prefix = String.format("%03d-%d", n, ts);
            Files.writeString(d.resolve(prefix + "-in.m3u8"),
                upstream, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(d.resolve(prefix + "-out.m3u8"),
                filtered, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log("dump #" + n + " saved (" + upstream.length() + "B in, "
                + filtered.length() + "B out): " + d.resolve(prefix + "-in.m3u8"));
        } catch (Exception e) {
            log("dump error: " + e.getMessage());
        }
    }

    private static String fetch(String url) {
        // Route through SecureTlsClient so DNS goes via DoH and TLS uses
        // our hand-rolled HTTP/1.1 path. *.playlist.ttvnw.net are subject
        // to the same ISP-level DNS poisoning that targets gql.twitch.tv;
        // the standard HttpURLConnection here would use the system
        // resolver and consistently get TCP-RST on every fetch.
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/vnd.apple.mpegurl,*/*");
        headers.put("Origin", "https://www.twitch.tv");
        headers.put("Referer", "https://www.twitch.tv/");
        try {
            String body = SecureTlsClient.get(url, headers);
            if (body == null) {
                log("fetch: non-200 or empty body for " + url);
            }
            return body;
        } catch (Exception e) {
            log("fetch error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static final Pattern DURATION_ATTR =
        Pattern.compile("DURATION=([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern START_DATE_ATTR =
        Pattern.compile("START-DATE=\"([^\"]+)\"");

    /**
     * One ad time-range parsed from an {@code #EXT-X-DATERANGE} tag. All times
     * in epoch milliseconds.
     */
    private record AdRange(long startMs, long endMs) {
    }

    /**
     * Strip {@code twitch-stitched-ad} segments from a Twitch live HLS variant
     * playlist using PROGRAM-DATE-TIME based filtering (the same approach
     * streamlink, ttv-lol-pro and purpleadblock use).
     *
     * <p>Twitch's current live HLS format does NOT use {@code EXT-X-DISCONTINUITY}
     * to bracket ad segments. Instead each ad block is declared with a single
     * {@code #EXT-X-DATERANGE:CLASS="twitch-stitched-ad",START-DATE=ISO,DURATION=N}
     * tag, and ad segments are mixed inline with content segments. Each
     * segment carries its own {@code #EXT-X-PROGRAM-DATE-TIME} which we use to
     * decide whether it falls inside an ad range.</p>
     *
     * <p>Two-pass algorithm:</p>
     * <ol>
     *   <li>Scan all lines, collect every {@code stitched-ad} DATERANGE into a
     *       list of {@code [startMs, endMs)} ranges.</li>
     *   <li>Walk lines a second time, grouping each segment as the triple
     *       (PROGRAM-DATE-TIME tag, EXTINF tag, URI). Drop the entire triple
     *       if its PDT falls inside any ad range. Drop all DATERANGE tags
     *       themselves regardless. Keep all other lines (manifest header,
     *       discontinuity markers, EXT-X-TWITCH-* tags, etc.).</li>
     * </ol>
     *
     * <p>Segment URIs in Twitch variant playlists are absolute, so no URI
     * rewrite is needed.</p>
     */
    /** Result of a filter pass: cleaned playlist plus segment counts. */
    record FilterResult(String playlist, int droppedSegments, int keptSegments) {
    }

    /** Backwards-compat shim used by existing callers / tests. */
    static String filterAds(String playlist, String baseUrl) {
        return filterAdsWithStats(playlist, baseUrl).playlist;
    }

    /**
     * Last 60 chars of an upstream URL, for compact logging.
     */
    private static String shortUrl(String url) {
        if (url == null) return "<null>";
        return url.length() <= 60 ? url : "..." + url.substring(url.length() - 60);
    }

    static FilterResult filterAdsWithStats(String playlist, String baseUrl) {
        if (playlist == null) return new FilterResult("", 0, 0);

        // Normalise line endings.
        String[] rawLines = playlist.split("\\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String raw : rawLines) {
            String line = raw;
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }

        // Pass 1: collect ad ranges from DATERANGE tags. Two markers in the
        // wild (matches streamlink's _is_daterange_ad):
        //   1. CLASS="twitch-stitched-ad"    - classic Twitch-stitched ads
        //   2. ID="stitched-ad-..."          - newer Amazon-Ads-platform pods
        // Either one is sufficient.
        List<AdRange> adRanges = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith("#EXT-X-DATERANGE:")) continue;
            if (!isAdDaterange(t)) continue;
            Long startMs = parseStartDateMs(t);
            Double durSec = parseDurationSec(t);
            if (startMs == null || durSec == null || durSec <= 0) continue;
            long endMs = startMs + (long) Math.ceil(durSec * 1000.0);
            adRanges.add(new AdRange(startMs, endMs));
        }

        // Pass 2: walk lines, using the segment URI as the boundary marker.
        // Every per-segment tag (EXTINF, PROGRAM-DATE-TIME, DISCONTINUITY,
        // BYTERANGE, KEY, MAP) gets buffered until we hit the URI; at that
        // point we either flush the whole group to the output or drop it.
        // This works regardless of tag ordering (PDT-first vs EXTINF-first)
        // and avoids orphan #EXTINF lines in the cleaned playlist - which
        // FFmpeg reports as "Empty segment".
        StringBuilder out = new StringBuilder(playlist.length());
        int droppedSegments = 0;
        int keptSegments = 0;
        List<String> segBuf = new ArrayList<>(8);

        for (String line : lines) {
            String t = line.trim();

            // Always drop ad DATERANGE markers; consumed in pass 1.
            if (t.startsWith("#EXT-X-DATERANGE:") && isAdDaterange(t)) {
                continue;
            }

            // URI line (non-tag, non-empty) closes the current segment group.
            if (!t.startsWith("#") && !t.isEmpty()) {
                segBuf.add(line);
                long pdtMs = findPdtMs(segBuf);
                boolean drop =
                    (pdtMs > 0 && isInsideAd(pdtMs, adRanges))
                    || hasAmazonExtinfTitle(segBuf);
                if (drop) {
                    droppedSegments++;
                } else {
                    for (String b : segBuf) out.append(b).append('\n');
                    keptSegments++;
                }
                segBuf.clear();
                continue;
            }

            // Empty line - attach to buffer if mid-segment, else pass through.
            if (t.isEmpty()) {
                if (segBuf.isEmpty()) out.append(line).append('\n');
                else segBuf.add(line);
                continue;
            }

            // Tag line. Per-segment tags go to the buffer; playlist-scoped
            // tags must NOT cause a stale segBuf flush - flushing a buffer
            // that lacks its URI line yields an orphan EXTINF in the output
            // and FFmpeg logs "Empty segment". Drop any stale per-segment
            // tags instead (they belong to a segment that never closed -
            // typically a partial playlist or a post-filter artefact).
            if (isSegmentScopedTag(t)) {
                segBuf.add(line);
            } else {
                if (!segBuf.isEmpty()) segBuf.clear();
                out.append(line).append('\n');
            }
        }
        // Trailing per-segment tags with no URI - drop, never emit. Emitting
        // them would create the exact "Empty segment" warning we are trying
        // to avoid.
        // (segBuf is dropped silently.)

        // Quiet, transition-only logging: emitting one line per playlist
        // refresh produces ~900 INFO lines per hour of streaming. Instead,
        // log only when the ad situation TRANSITIONS for a given upstream
        // (entered/left an ad pod), or on the first observation of ads.
        // The full per-fetch breakdown stays available via the on-disk dumps
        // (-Dcollins.twitch.dump=true).
        boolean adsActive = !adRanges.isEmpty() || droppedSegments > 0;
        boolean preRollish = adsActive && keptSegments == 0;
        Boolean prev = LAST_AD_STATE.get(baseUrl);
        Boolean nowState = adsActive ? Boolean.valueOf(preRollish) : null;
        if (!java.util.Objects.equals(prev, nowState)) {
            if (adsActive) {
                LAST_AD_STATE.put(baseUrl, nowState);
                log("filterAds: " + adRanges.size() + " ad range(s), dropped "
                    + droppedSegments + " ad segment(s), kept " + keptSegments
                    + " content segment(s)");
            } else if (prev != null) {
                LAST_AD_STATE.remove(baseUrl);
                log("filterAds: ads ended for " + shortUrl(baseUrl));
            }
        }
        return new FilterResult(out.toString(), droppedSegments, keptSegments);
    }

    /**
     * Tracks the last observed ad state per upstream URL so we can log
     * transitions only. {@code null} = no ads, {@code true} = pre-roll
     * (kept=0), {@code false} = mid-roll mixed with content. Bounded LRU
     * with the same {@link #CACHE_MAX} cap as {@link #LAST_GOOD_PLAYLIST}.
     */
    @SuppressWarnings("serial")
    private static final java.util.Map<String, Boolean> LAST_AD_STATE =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Boolean>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    /**
     * Whether {@code trimmedLine} is a per-segment tag that must travel with
     * its segment URI through the ad filter.
     */
    private static boolean isSegmentScopedTag(String trimmedLine) {
        return trimmedLine.startsWith("#EXTINF:")
            || trimmedLine.startsWith("#EXT-X-PROGRAM-DATE-TIME:")
            || trimmedLine.startsWith("#EXT-X-DISCONTINUITY")
            || trimmedLine.startsWith("#EXT-X-BYTERANGE:")
            || trimmedLine.startsWith("#EXT-X-KEY:")
            || trimmedLine.startsWith("#EXT-X-MAP:");
    }

    /**
     * Find the PROGRAM-DATE-TIME (in epoch ms) inside a buffered segment
     * group, or {@code -1} if absent / unparseable.
     */
    private static long findPdtMs(List<String> segBuf) {
        for (String b : segBuf) {
            String bt = b.trim();
            if (bt.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                return parseIsoMs(bt.substring("#EXT-X-PROGRAM-DATE-TIME:".length()));
            }
        }
        return -1L;
    }

    /**
     * Whether the EXTINF in {@code segBuf} carries an Amazon-Ads title.
     * Twitch's Amazon Ads platform stitches its mid-roll ad pods with
     * {@code #EXTINF:6.0,Amazon} (or similar). Streamlink uses the same
     * fallback heuristic to drop ads that have no DATERANGE counterpart.
     */
    private static boolean hasAmazonExtinfTitle(List<String> segBuf) {
        for (String b : segBuf) {
            String bt = b.trim();
            if (!bt.startsWith("#EXTINF:")) continue;
            int comma = bt.indexOf(',');
            if (comma < 0) continue;
            String title = bt.substring(comma + 1).trim();
            if (!title.isEmpty() && title.toLowerCase(Locale.ROOT).contains("amazon")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code dateRangeTag} marks a Twitch ad break. Matches the
     * union of streamlink's {@code _is_daterange_ad} checks:
     * {@code CLASS="twitch-stitched-ad"} OR {@code ID="stitched-ad-..."}.
     */
    private static boolean isAdDaterange(String dateRangeTag) {
        if (dateRangeTag.contains("CLASS=\"twitch-stitched-ad")) return true;
        if (dateRangeTag.contains("ID=\"stitched-ad-")) return true;
        return false;
    }

    private static boolean isInsideAd(long pdtMs, List<AdRange> ranges) {
        for (AdRange r : ranges) {
            if (pdtMs >= r.startMs && pdtMs < r.endMs) return true;
        }
        return false;
    }

    private static Long parseStartDateMs(String dateRangeTag) {
        Matcher m = START_DATE_ATTR.matcher(dateRangeTag);
        if (!m.find()) return null;
        return parseIsoMs(m.group(1));
    }

    private static Double parseDurationSec(String dateRangeTag) {
        Matcher m = DURATION_ATTR.matcher(dateRangeTag);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseIsoMs(String iso) {
        try {
            return Instant.parse(iso.trim()).toEpochMilli();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Returns the value of the {@code key} query parameter, base64-url-decoded.
     */
    private static String decodeUrlParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            if (!k.equals(key)) continue;
            String v = pair.substring(eq + 1);
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(v);
                String s = new String(decoded, StandardCharsets.UTF_8);
                // Sanity: only allow http(s) URLs targeting twitch CDN.
                if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
                String hostLower;
                try {
                    hostLower = URI.create(s).getHost();
                    if (hostLower != null) hostLower = hostLower.toLowerCase();
                } catch (Exception e) {
                    return null;
                }
                if (hostLower == null) return null;
                if (!(hostLower.endsWith(".ttvnw.net")
                    || hostLower.equals("ttvnw.net")
                    || hostLower.endsWith(".twitch.tv")
                    || hostLower.equals("twitch.tv"))) {
                    log("decodeUrlParam: refusing non-twitch host " + hostLower);
                    return null;
                }
                return s;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static void respond(HttpExchange ex, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void log(String msg) {
        try {
            System.out.println("[CollinsTwitchProxy] " + msg);
        } catch (Exception ignored) {
        }
    }
}
