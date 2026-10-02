package dev.dorrian.agenticskillscli.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.agenticskillscli.HomeDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up the latest published release and reports it only when it is newer
 * than the running version. Never throws: any failure yields an empty result.
 * At most one network call per {@link #CACHE_TTL}; disabled by
 * {@link #DISABLE_ENV_VAR}.
 */
public final class LatestVersion {

    public static final String DISABLE_ENV_VAR = "AGENTIC_SKILLS_NO_UPDATE_CHECK";
    static final String LATEST_RELEASE_URL =
        "https://api.github.com/repos/alt-Ash/agentic-skills-bundle/releases/latest";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    static final Duration CACHE_TTL = Duration.ofHours(24);
    static final String CACHE_FILE_NAME = "update-check.json";

    private static final Pattern SEMVER = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(-.+)?$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Returns the raw release JSON body; may throw on any failure. */
    @FunctionalInterface
    interface Fetcher {
        String fetch() throws Exception;
    }

    private final Fetcher fetcher;
    private final Clock clock;
    private final Path cacheFile;
    private final Map<String, String> env;

    LatestVersion(Fetcher fetcher, Clock clock, Path cacheFile, Map<String, String> env) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.cacheFile = cacheFile;
        this.env = env;
    }

    public static Optional<String> newerThan(String running) {
        Path cache = HomeDir.resolve().resolve(".agentic-skills").resolve(CACHE_FILE_NAME);
        return new LatestVersion(LatestVersion::fetchFromGitHub, Clock.systemUTC(), cache, System.getenv())
            .check(running);
    }

    public static String notice(String latest) {
        return "A newer agentic-skills (" + latest + ") is available. Upgrade with: brew upgrade agentic-skills";
    }

    /** True when {@code latest} is a strictly higher X.Y.Z than {@code running}; X.Y.Z-suffix sorts before X.Y.Z. */
    public static boolean isNewer(String running, String latest) {
        int[] r = parse(running);
        int[] l = parse(latest);
        if (r == null || l == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (l[i] != r[i]) {
                return l[i] > r[i];
            }
        }
        return l[3] < r[3]; // latest is final (0), running is pre-release (1)
    }

    Optional<String> check(String running) {
        try {
            String disabled = env.get(DISABLE_ENV_VAR);
            if (disabled != null && !disabled.isEmpty()) {
                return Optional.empty();
            }
            if (parse(running) == null) {
                return Optional.empty();
            }
            String latest = readFreshCache();
            if (latest == null) {
                latest = extractVersion(fetcher.fetch());
                if (latest == null) {
                    return Optional.empty();
                }
                writeCache(latest);
            }
            return isNewer(running, latest) ? Optional.of(latest) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Returns int[]{major, minor, patch, preRelease(1|0)} or null when unparsable. */
    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = SEMVER.matcher(version.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            return new int[]{
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), m.group(4) != null ? 1 : 0};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractVersion(String body) throws Exception {
        JsonNode tag = MAPPER.readTree(body).path("tag_name");
        if (!tag.isTextual()) {
            return null;
        }
        String v = tag.asText().strip();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        return parse(v) != null ? v : null;
    }

    private String readFreshCache() {
        try {
            JsonNode node = MAPPER.readTree(Files.readString(cacheFile, StandardCharsets.UTF_8));
            long checkedAt = node.path("checkedAt").asLong(-1);
            String latest = node.path("latest").asText(null);
            if (checkedAt < 0 || latest == null || parse(latest) == null) {
                return null;
            }
            long age = clock.millis() - checkedAt;
            return age >= 0 && age < CACHE_TTL.toMillis() ? latest : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String latest) {
        try {
            Files.createDirectories(cacheFile.getParent());
            ObjectNode node = MAPPER.createObjectNode();
            node.put("checkedAt", clock.millis());
            node.put("latest", latest);
            Files.writeString(cacheFile, MAPPER.writeValueAsString(node), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // cache is best-effort
        }
    }

    private static String fetchFromGitHub() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }
}
