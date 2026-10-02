package dev.dorrian.agenticskillscli.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestVersionTest {

    private static final String BODY = "{\"tag_name\":\"v3.1.0\"}";
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    @TempDir
    Path tmp;

    private final AtomicInteger calls = new AtomicInteger();

    private LatestVersion sut(LatestVersion.Fetcher fetcher, Instant now, Map<String, String> env) {
        return new LatestVersion(fetcher, Clock.fixed(now, ZoneOffset.UTC), tmp.resolve("sub/update-check.json"), env);
    }

    private LatestVersion.Fetcher counting(String body) {
        return () -> {
            calls.incrementAndGet();
            return body;
        };
    }

    @Test
    void isNewerComparesMajorMinorPatchNumerically() {
        assertTrue(LatestVersion.isNewer("3.0.0", "3.0.1"));
        assertTrue(LatestVersion.isNewer("3.0.9", "3.1.0"));
        assertTrue(LatestVersion.isNewer("2.9.9", "3.0.0"));
        assertTrue(LatestVersion.isNewer("3.9.0", "3.10.0"));
    }

    @Test
    void isNewerIsFalseForEqualOrOlder() {
        assertFalse(LatestVersion.isNewer("3.0.0", "3.0.0"));
        assertFalse(LatestVersion.isNewer("3.1.0", "3.0.9"));
        assertFalse(LatestVersion.isNewer("4.0.0", "3.99.99"));
    }

    @Test
    void preReleaseIsOlderThanTheFinalRelease() {
        assertTrue(LatestVersion.isNewer("3.1.0-rc1", "3.1.0"));
        assertFalse(LatestVersion.isNewer("3.1.0", "3.1.0-rc1"));
        assertFalse(LatestVersion.isNewer("3.1.0-rc1", "3.1.0-rc2"));
        assertTrue(LatestVersion.isNewer("3.0.0-rc1", "3.0.1-rc1"));
    }

    @Test
    void isNewerIsFalseForUnparsableInput() {
        assertFalse(LatestVersion.isNewer("dev", "3.0.0"));
        assertFalse(LatestVersion.isNewer("3.0.0", "latest"));
        assertFalse(LatestVersion.isNewer(null, "3.0.0"));
        assertFalse(LatestVersion.isNewer("3.0.0", null));
        assertFalse(LatestVersion.isNewer("3.0", "3.1.0"));
        assertFalse(LatestVersion.isNewer("99999999999.0.0", "3.0.0"));
    }

    @Test
    void noticeIsTheOneLineUpgradeMessage() {
        assertEquals("A newer agentic-skills (3.1.0) is available. Update it with your installer"
            + " (e.g. brew upgrade agentic-skills), then run: agentic-skills upgrade",
            LatestVersion.notice("3.1.0"));
    }

    @Test
    void returnsLatestWhenNewerAndStripsLeadingV() {
        assertEquals(Optional.of("3.1.0"), sut(counting(BODY), NOW, Map.of()).check("3.0.0"));
    }

    @Test
    void emptyWhenRunningIsEqualOrNewer() {
        assertEquals(Optional.empty(), sut(counting(BODY), NOW, Map.of()).check("3.1.0"));
        assertEquals(Optional.empty(), sut(counting(BODY), NOW, Map.of()).check("4.0.0"));
    }

    @Test
    void devOrUnparsableRunningVersionIsEmptyWithoutFetching() {
        assertEquals(Optional.empty(), sut(counting(BODY), NOW, Map.of()).check("dev"));
        assertEquals(Optional.empty(), sut(counting(BODY), NOW, Map.of()).check(null));
        assertEquals(Optional.empty(), sut(counting(BODY), NOW, Map.of()).check("banana"));
        assertEquals(0, calls.get());
    }

    @Test
    void freshCacheAvoidsFetch() {
        sut(counting(BODY), NOW, Map.of()).check("3.0.0");
        Optional<String> second = sut(counting(BODY), NOW.plusSeconds(3600), Map.of()).check("3.0.0");
        assertEquals(Optional.of("3.1.0"), second);
        assertEquals(1, calls.get());
    }

    @Test
    void expiredCacheRefetches() {
        sut(counting(BODY), NOW, Map.of()).check("3.0.0");
        Optional<String> later = sut(counting("{\"tag_name\":\"v3.2.0\"}"), NOW.plusSeconds(24 * 3600 + 1), Map.of())
            .check("3.0.0");
        assertEquals(Optional.of("3.2.0"), later);
        assertEquals(2, calls.get());
    }

    @Test
    void fetchFailureIsEmpty() {
        LatestVersion.Fetcher failing = () -> {
            throw new java.io.IOException("offline");
        };
        assertEquals(Optional.empty(), sut(failing, NOW, Map.of()).check("3.0.0"));
    }

    @Test
    void malformedJsonIsEmpty() {
        assertEquals(Optional.empty(), sut(counting("not json {"), NOW, Map.of()).check("3.0.0"));
        assertEquals(Optional.empty(), sut(counting("{\"other\":1}"), NOW, Map.of()).check("3.0.0"));
        assertEquals(Optional.empty(), sut(counting("{\"tag_name\":\"nightly\"}"), NOW, Map.of()).check("3.0.0"));
    }

    @Test
    void corruptCacheFallsBackToFetch() throws Exception {
        Path cache = tmp.resolve("sub/update-check.json");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "garbage");
        assertEquals(Optional.of("3.1.0"), sut(counting(BODY), NOW, Map.of()).check("3.0.0"));
        assertEquals(1, calls.get());
    }

    @Test
    void unwritableCacheIsIgnored() throws Exception {
        Path blocker = tmp.resolve("file");
        Files.writeString(blocker, "x");
        LatestVersion sut = new LatestVersion(counting(BODY), Clock.fixed(NOW, ZoneOffset.UTC),
            blocker.resolve("c.json"), Map.of());
        assertEquals(Optional.of("3.1.0"), sut.check("3.0.0"));
    }

    @Test
    void envVarDisablesTheCheckEntirely() {
        Map<String, String> env = Map.of(LatestVersion.DISABLE_ENV_VAR, "1");
        assertEquals(Optional.empty(), sut(counting(BODY), NOW, env).check("3.0.0"));
        assertEquals(0, calls.get());
    }

    @Test
    void emptyEnvVarDoesNotDisable() {
        Map<String, String> env = Map.of(LatestVersion.DISABLE_ENV_VAR, "");
        assertEquals(Optional.of("3.1.0"), sut(counting(BODY), NOW, env).check("3.0.0"));
    }

    @Test
    void failedLookupIsCachedSoOfflineRunsDoNotRetryEveryTime() {
        LatestVersion.Fetcher failing = () -> {
            calls.incrementAndGet();
            throw new java.io.IOException("offline");
        };
        assertEquals(Optional.empty(), sut(failing, NOW, Map.of()).check("3.0.0"));
        assertEquals(Optional.empty(), sut(failing, NOW.plusSeconds(60), Map.of()).check("3.0.0"));
        assertEquals(1, calls.get());
        // after the (shorter) failure TTL it tries again
        assertEquals(Optional.empty(), sut(failing, NOW.plus(LatestVersion.FAILURE_TTL).plusSeconds(1), Map.of()).check("3.0.0"));
        assertEquals(2, calls.get());
    }

    @Test
    void zeroOrFalseEnvValueDoesNotDisable() {
        assertEquals(Optional.of("3.1.0"), sut(counting(BODY), NOW, Map.of(LatestVersion.DISABLE_ENV_VAR, "0")).check("3.0.0"));
        assertEquals(Optional.of("3.1.0"), sut(counting(BODY), NOW, Map.of(LatestVersion.DISABLE_ENV_VAR, "false")).check("3.0.0"));
    }
}
