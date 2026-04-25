package org.sawiq.collins.fabric.client.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronously checks Modrinth for a newer release of the
 * {@code collins-fabric} mod and reports the result via {@link Result}.
 *
 * <p>Mirrors the version checker shipped with the author's other mods so
 * the update flow stays consistent across them.</p>
 */
public final class ModrinthVersionChecker {
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/collins-fabric/version";
    private static final String MODRINTH_PAGE = "https://modrinth.com/mod/collins-fabric/versions";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final String currentVersion;
    private boolean checked;

    public ModrinthVersionChecker() {
        this.currentVersion = FabricLoader.getInstance()
                .getModContainer("collins-fabric")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public CompletableFuture<Result> checkAsync() {
        if (this.checked) {
            return CompletableFuture.completedFuture(null);
        }
        this.checked = true;

        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(MODRINTH_API).toURL().openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "collins-fabric/" + this.currentVersion);

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    return null;
                }

                String json;
                try (java.io.InputStream input = connection.getInputStream()) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonArray()) {
                    return null;
                }

                JsonArray versions = root.getAsJsonArray();
                JsonObject latest = null;
                String latestDate = "";

                for (JsonElement element : versions) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject version = element.getAsJsonObject();
                    String date = version.has("date_published") ? version.get("date_published").getAsString() : "";
                    if (latest == null || date.compareTo(latestDate) > 0) {
                        latest = version;
                        latestDate = date;
                    }
                }

                if (latest == null) {
                    return null;
                }

                String latestVersion = latest.has("version_number") ? latest.get("version_number").getAsString() : "unknown";
                String versionName = latest.has("name") ? latest.get("name").getAsString() : latestVersion;

                if (isNewer(latestVersion, this.currentVersion)) {
                    return new Result(versionName, latestVersion, MODRINTH_PAGE);
                }

                return null;
            } catch (Exception e) {
                return null;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static boolean isNewer(String remote, String local) {
        return compareVersions(remote, local) > 0;
    }

    private static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int max = Math.max(partsA.length, partsB.length);

        for (int i = 0; i < max; i++) {
            int numA = i < partsA.length ? parseIntSafe(partsA[i]) : 0;
            int numB = i < partsB.length ? parseIntSafe(partsB[i]) : 0;
            if (numA != numB) {
                return Integer.compare(numA, numB);
            }
        }

        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record Result(String name, String version, String url) {
    }
}
