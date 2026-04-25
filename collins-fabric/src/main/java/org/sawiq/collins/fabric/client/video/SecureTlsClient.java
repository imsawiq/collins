package org.sawiq.collins.fabric.client.video;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTPS client that bypasses the system DNS resolver and Java's
 * {@link java.net.HttpURLConnection} / {@link java.net.http.HttpClient}
 * stack to work around two specific failure modes seen on Russian/CIS
 * networks where users want Twitch to work via VPN:
 *
 * <ol>
 *   <li><b>DNS poisoning.</b> The user's system DNS returns garbage IPs for
 *       {@code gql.twitch.tv}, {@code usher.ttvnw.net},
 *       {@code *.playlist.ttvnw.net} (e.g. {@code 82.22.36.11} - a UK BT
 *       address completely unrelated to Twitch infrastructure). All TCP
 *       connects to these IPs are answered with RST. We sidestep the
 *       poisoned resolver by querying DNS-over-HTTPS directly against
 *       hardcoded Cloudflare/Google/Quad9 IPs.</li>
 *   <li><b>Java HTTP fingerprint.</b> Even with correct IPs from a clean
 *       DNS, Java's HttpClient sends an ALPN-h2 ClientHello that some DPI
 *       middleboxes flag for further inspection. yt-dlp (Python OpenSSL)
 *       on the same host succeeds because it uses TLS 1.2 with HTTP/1.1
 *       and a smaller cipher suite. Our raw {@link SSLSocket} request
 *       mirrors that.</li>
 * </ol>
 *
 * <p>All network methods are blocking and intended to be called from a
 * worker thread (proxy handler, video resolver). Connect/read timeouts
 * are 5 s by default - tuned for live HLS pull cadence where the
 * upstream is rarely more than 100 ms RTT away.</p>
 */
public final class SecureTlsClient {
    private SecureTlsClient() {
    }

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    /**
     * 5 minute DoH cache TTL is short enough to recover from CDN failover,
     * long enough to avoid paying ~150 ms per fetch on busy live sessions.
     */
    private static final long DNS_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long FALLBACK_CACHE_TTL_MS = 30_000L;

    private static final ConcurrentHashMap<String, CachedAddrs> DNS_CACHE = new ConcurrentHashMap<>();

    private record CachedAddrs(List<InetAddress> ips, long expiresAtMs) {
    }

    /**
     * Fetches the body of {@code url} via GET. Returns the response body or
     * {@code null} on non-200 / network failure (the caller decides how to
     * recover - HLS proxy logs a 502, variant picker falls back to handing
     * the master URL to FFmpeg, etc).
     */
    public static String get(String url, Map<String, String> headers) {
        return request("GET", url, null, headers,
            DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /** Same as {@link #get(String, Map)} with custom timeouts. */
    public static String get(String url, Map<String, String> headers,
                             int connectTimeoutMs, int readTimeoutMs) {
        return request("GET", url, null, headers, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * POSTs {@code body} to {@code url}. Returns the response body or
     * {@code null} on non-200 / network failure. Throws {@link IOException}
     * for connect-level failures so the caller can distinguish "server said
     * no" from "couldn't reach server".
     */
    public static String post(String url, byte[] body, Map<String, String> headers,
                              int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return requestThrowing("POST", url, body, headers, connectTimeoutMs, readTimeoutMs);
    }

    private static String request(String method, String url, byte[] body,
                                  Map<String, String> headers,
                                  int connectTimeoutMs, int readTimeoutMs) {
        try {
            return requestThrowing(method, url, body, headers, connectTimeoutMs, readTimeoutMs);
        } catch (IOException ioe) {
            return null;
        }
    }

    private static String requestThrowing(String method, String url, byte[] body,
                                          Map<String, String> headers,
                                          int connectTimeoutMs, int readTimeoutMs)
        throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IOException("bad URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IOException("only https is supported, got: " + scheme);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (port < 0) port = 443;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();

        List<InetAddress> addrs = resolveHostSecure(host);
        if (addrs.isEmpty()) {
            throw new IOException("no IPv4 addresses for " + host);
        }
        IOException lastError = null;
        for (InetAddress addr : addrs) {
            try {
                return tlsRequest(addr, host, port, method, path, body, headers,
                    connectTimeoutMs, readTimeoutMs);
            } catch (IOException ioe) {
                lastError = ioe;
            }
        }
        throw lastError != null ? lastError : new IOException("all IPs failed for " + host);
    }

    /**
     * One TLS request against a specific resolved IP. Connect/read timeouts
     * are enforced at the socket level. Returns body for 200, {@code null}
     * for non-200, throws for connect/read errors.
     */
    private static String tlsRequest(InetAddress addr, String sni, int port,
                                     String method, String path, byte[] body,
                                     Map<String, String> headers,
                                     int connectTimeoutMs, int readTimeoutMs)
        throws IOException {
        Socket plain = null;
        SSLSocket tls = null;
        try {
            plain = new Socket();
            plain.connect(new InetSocketAddress(addr, port), connectTimeoutMs);
            plain.setSoTimeout(readTimeoutMs);
            SSLSocketFactory factory = (SSLSocketFactory) SSLContext.getDefault().getSocketFactory();
            tls = (SSLSocket) factory.createSocket(plain, sni, port, true);
            // TLS 1.2 only - matches Python OpenSSL default and produces a
            // smaller ClientHello that Russian/CIS DPI engines historically
            // pattern-match less aggressively than TLS 1.3 + ALPN h2.
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls.startHandshake();

            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(sni).append("\r\n");
            if ("POST".equals(method) && body != null) {
                req.append("Content-Length: ").append(body.length).append("\r\n");
            }
            req.append("Connection: close\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
                }
            }
            req.append("\r\n");
            OutputStream out = tls.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            if ("POST".equals(method) && body != null) out.write(body);
            out.flush();

            InputStream in = tls.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String statusLine = br.readLine();
            if (statusLine == null) throw new IOException("empty response");
            int code = parseStatusCode(statusLine);
            int contentLength = -1;
            boolean chunked = false;
            String header;
            while ((header = br.readLine()) != null) {
                if (header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (colon < 0) continue;
                String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
            StringBuilder bodyBuf = new StringBuilder();
            if (chunked) {
                String sizeLine;
                while ((sizeLine = br.readLine()) != null) {
                    int size = Integer.parseInt(sizeLine.trim(), 16);
                    if (size == 0) break;
                    char[] buf = new char[size];
                    int got = 0;
                    while (got < size) {
                        int r = br.read(buf, got, size - got);
                        if (r < 0) break;
                        got += r;
                    }
                    bodyBuf.append(buf, 0, got);
                    br.readLine();
                }
            } else if (contentLength >= 0) {
                char[] buf = new char[contentLength];
                int got = 0;
                while (got < contentLength) {
                    int r = br.read(buf, got, contentLength - got);
                    if (r < 0) break;
                    got += r;
                }
                bodyBuf.append(buf, 0, got);
            } else {
                int ch;
                while ((ch = br.read()) >= 0) bodyBuf.append((char) ch);
            }
            if (code != 200) {
                return null;
            }
            return bodyBuf.toString();
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("TLS request failed: " + e.getMessage(), e);
        } finally {
            closeQuietly(tls);
            closeQuietly(plain);
        }
    }

    /**
     * Resolves {@code hostname} via DoH (Cloudflare, Google, Quad9) with a
     * fallback to the system resolver. Cached for 5 min on success / 30 s
     * on system-DNS fallback, so DoH outages auto-recover quickly. Returns
     * an empty list only if both DoH and the system resolver fail.
     */
    public static List<InetAddress> resolveHostSecure(String hostname) {
        long now = System.currentTimeMillis();
        CachedAddrs cached = DNS_CACHE.get(hostname);
        if (cached != null && cached.expiresAtMs > now) return cached.ips;

        // DoH endpoints with hardcoded IPs - never round-trip through the
        // system resolver to find the DoH server itself.
        String[][] dohEndpoints = {
            {"1.1.1.1", "cloudflare-dns.com"},
            {"1.0.0.1", "cloudflare-dns.com"},
            {"8.8.8.8", "dns.google"},
            {"8.8.4.4", "dns.google"},
            {"9.9.9.9", "dns.quad9.net"},
        };
        for (String[] doh : dohEndpoints) {
            try {
                List<InetAddress> ips = dohQuery(doh[0], doh[1], hostname);
                if (!ips.isEmpty()) {
                    DNS_CACHE.put(hostname, new CachedAddrs(ips, now + DNS_CACHE_TTL_MS));
                    return ips;
                }
            } catch (Exception ignored) {
            }
        }
        // System DNS fallback - poisoned IPs end up here, but at least try.
        try {
            InetAddress[] addrs = InetAddress.getAllByName(hostname);
            List<InetAddress> ipv4 = new ArrayList<>();
            for (InetAddress a : addrs) {
                if (a instanceof Inet4Address) ipv4.add(a);
            }
            if (!ipv4.isEmpty()) {
                DNS_CACHE.put(hostname, new CachedAddrs(ipv4, now + FALLBACK_CACHE_TTL_MS));
                return ipv4;
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyList();
    }

    private static List<InetAddress> dohQuery(String dohIp, String dohSni, String hostname)
        throws IOException {
        InetAddress dohAddr = InetAddress.getByAddress(dohSni, parseIpv4(dohIp));
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/dns-json");
        headers.put("User-Agent", "Mozilla/5.0");
        String path = "/dns-query?name=" + URLEncoder.encode(hostname, StandardCharsets.UTF_8) + "&type=A";
        String resp = tlsRequest(dohAddr, dohSni, 443, "GET", path, null, headers, 3_000, 3_000);
        if (resp == null || resp.isBlank()) return Collections.emptyList();
        List<InetAddress> result = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(resp);
            if (!root.isJsonObject()) return Collections.emptyList();
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("Status") && obj.get("Status").getAsInt() != 0) {
                return Collections.emptyList();
            }
            JsonElement ansEl = obj.get("Answer");
            if (ansEl == null || !ansEl.isJsonArray()) return Collections.emptyList();
            for (JsonElement el : ansEl.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject ans = el.getAsJsonObject();
                JsonElement type = ans.get("type");
                if (type == null || type.getAsInt() != 1) continue;  // 1 = A
                JsonElement data = ans.get("data");
                if (data == null) continue;
                String ip = data.getAsString().trim();
                try {
                    result.add(InetAddress.getByAddress(hostname, parseIpv4(ip)));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            throw new IOException("DoH JSON parse error: " + e.getMessage(), e);
        }
        return result;
    }

    private static byte[] parseIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 literal: " + ip);
        }
        byte[] octets = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v = Integer.parseInt(parts[i]);
            if (v < 0 || v > 255) throw new IllegalArgumentException("octet out of range: " + ip);
            octets[i] = (byte) v;
        }
        return octets;
    }

    private static int parseStatusCode(String statusLine) throws IOException {
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) throw new IOException("bad status line: " + statusLine);
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        try {
            String code = secondSpace < 0
                ? statusLine.substring(firstSpace + 1).trim()
                : statusLine.substring(firstSpace + 1, secondSpace).trim();
            return Integer.parseInt(code);
        } catch (NumberFormatException nfe) {
            throw new IOException("bad status code: " + statusLine);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
