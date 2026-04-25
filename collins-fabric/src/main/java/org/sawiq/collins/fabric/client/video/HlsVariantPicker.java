package org.sawiq.collins.fabric.client.video;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure HLS master-playlist variant selector.
 *
 * <p>Both YouTube live and Twitch hand us a master {@code .m3u8} that
 * contains multiple {@code #EXT-X-STREAM-INF} entries (one per quality
 * variant). FFmpeg's HLS demuxer will happily fetch the master and pick a
 * variant on its own, but its choice is unpredictable on Windows builds:
 * it sometimes lands on the lowest-bandwidth variant and stays there even
 * if the user has bandwidth to spare. Worse, automatic ABR transitions
 * mid-stream rebuild the swscale context and produce green/garbage
 * stripes for one frame.</p>
 *
 * <p>This helper fetches the master playlist itself, picks the highest
 * resolution that fits the caller's height budget, and returns the
 * absolute variant URL. FFmpeg then fetches just that variant playlist
 * and stays pinned to one quality.</p>
 */
public final class HlsVariantPicker {
    private static final Pattern STREAM_INF =
        Pattern.compile("#EXT-X-STREAM-INF:([^\\n]*)\\n([^\\n]+)", Pattern.MULTILINE);
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");

    private HlsVariantPicker() {
    }

    /**
     * @return absolute variant URL or {@code null} if anything goes wrong;
     *         caller should fall back to handing the master URL to FFmpeg.
     */
    public static String pick(String masterUrl, int preferredHeight, String userAgent) {
        if (masterUrl == null || masterUrl.isBlank()) return null;
        try {
            String text = fetch(masterUrl, userAgent);
            if (text == null || text.isBlank()) return null;

            List<int[]> heights = new ArrayList<>();
            List<long[]> bandwidths = new ArrayList<>();
            List<String> urls = new ArrayList<>();

            Matcher m = STREAM_INF.matcher(text);
            int idx = 0;
            while (m.find()) {
                String attrs = m.group(1);
                String url = m.group(2).trim();
                if (url.isEmpty() || url.startsWith("#")) continue;

                Matcher rm = RESOLUTION.matcher(attrs);
                int h = rm.find() ? safeInt(rm.group(1)) : 0;
                Matcher bm = BANDWIDTH.matcher(attrs);
                long bw = bm.find() ? safeLong(bm.group(1)) : 0L;

                heights.add(new int[]{idx++, h});
                bandwidths.add(new long[]{bw});
                urls.add(absolutize(masterUrl, url));
            }
            if (urls.isEmpty()) return null;

            // First pass: largest variant whose height is <= preferredHeight.
            int best = -1;
            int bestHeight = -1;
            long bestBw = -1L;
            for (int i = 0; i < heights.size(); i++) {
                int h = heights.get(i)[1];
                long bw = bandwidths.get(i)[0];
                if (h > 0 && h <= preferredHeight && (h > bestHeight || (h == bestHeight && bw > bestBw))) {
                    bestHeight = h;
                    bestBw = bw;
                    best = i;
                }
            }
            // Second pass: if every variant is taller than preferredHeight,
            // pick the smallest one (still better than master fallback).
            if (best < 0) {
                int min = Integer.MAX_VALUE;
                for (int i = 0; i < heights.size(); i++) {
                    int h = heights.get(i)[1];
                    if (h > 0 && h < min) {
                        min = h;
                        best = i;
                    }
                }
            }
            // Third pass: nothing parseable, pick the highest bandwidth.
            if (best < 0) {
                long maxBw = -1L;
                for (int i = 0; i < bandwidths.size(); i++) {
                    long bw = bandwidths.get(i)[0];
                    if (bw > maxBw) {
                        maxBw = bw;
                        best = i;
                    }
                }
            }
            if (best < 0) best = 0;
            return urls.get(best);
        } catch (Exception e) {
            return null;
        }
    }

    private static String fetch(String url, String userAgent) {
        // Route through SecureTlsClient so DNS goes via DoH and TLS uses
        // our hand-rolled HTTP/1.1 path. usher.ttvnw.net (and its variant
        // playlist hosts) are subject to the same DNS poisoning that hits
        // gql.twitch.tv on Russian/CIS networks; the system resolver
        // returns garbage IPs that TCP-RST every connect.
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent != null ? userAgent
            : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        headers.put("Accept", "application/vnd.apple.mpegurl,*/*");
        return SecureTlsClient.get(url, headers);
    }

    /**
     * Converts a possibly-relative variant URL into an absolute one using the
     * master playlist URL as the base. YouTube's variant URLs are absolute,
     * Twitch's are too, but defensive about an HLS spec corner case.
     */
    private static String absolutize(String masterUrl, String maybeRelative) {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative;
        }
        try {
            URI base = URI.create(masterUrl);
            return base.resolve(maybeRelative).toString();
        } catch (Exception e) {
            return maybeRelative;
        }
    }

    private static int safeInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long safeLong(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return 0L;
        }
    }
}
