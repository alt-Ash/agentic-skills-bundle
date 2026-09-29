package dev.dorrian.securityscanner.allowlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of tests/allowlist.test.ts, assertion-for-assertion, against a temp directory instead of
 * mkdtempSync but the same {@code SCANNER_ALLOWLIST_PATH}-pointing mechanism.
 */
class AllowlistLoaderTest {

    private final AllowlistLoader loader = new AllowlistLoader();

    @Test
    void failsClosedWhenTheConfigFileDoesNotExist(@TempDir Path tmpDir) throws IOException {
        withAllowlistPath(tmpDir.resolve("does-not-exist.json"), () -> {
            var result = loader.load();

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).containsIgnoringCase("refuses to scan");
        });
    }

    @Test
    void failsClosedOnInvalidJson(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("allowlist.json");
        Files.writeString(file, "{ not json");

        withAllowlistPath(file, () -> {
            var result = loader.load();

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).containsIgnoringCase("not valid JSON");
        });
    }

    @Test
    void failsClosedWhenEnvironmentIsProd(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("allowlist.json");
        Files.writeString(file, """
            { "targets": [ { "host": "app.example.com", "environment": "prod" } ] }
            """);

        withAllowlistPath(file, () -> {
            var result = loader.load();

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).containsIgnoringCase("refuses to scan production");
        });
    }

    @Test
    void failsClosedWhenEnvironmentIsProduction(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("allowlist.json");
        Files.writeString(file, """
            { "targets": [ { "host": "app.example.com", "environment": "production" } ] }
            """);

        withAllowlistPath(file, () -> {
            var result = loader.load();

            assertThat(result.ok()).isFalse();
        });
    }

    @Test
    void loadsSuccessfullyForValidNonProdEnvironments(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("allowlist.json");
        Files.writeString(file, """
            { "targets": [
              { "host": "localhost:3000", "environment": "local" },
              { "host": "staging.example.com", "environment": "staging" }
            ] }
            """);

        withAllowlistPath(file, () -> {
            var result = loader.load();

            assertThat(result.ok()).isTrue();
            assertThat(result.allowlist().targets()).hasSize(2);
        });
    }

    @Test
    void rejectsAConfigWithOneBadEntryRatherThanSilentlyDroppingIt(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("allowlist.json");
        Files.writeString(file, """
            { "targets": [
              { "host": "ok.example.com", "environment": "local" },
              { "host": "bad.example.com", "environment": "prod" }
            ] }
            """);

        withAllowlistPath(file, () -> {
            var result = loader.load();

            assertThat(result.ok()).isFalse();
        });
    }

    @Test
    void allowsAnExactHostMatch() {
        var allowlist = new Allowlist(java.util.List.of(
            new AllowlistTarget("localhost:3000", Environment.LOCAL),
            new AllowlistTarget("Staging.Example.com", Environment.STAGING)
        ));

        var result = loader.checkTarget("localhost:3000", allowlist);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void matchesCaseInsensitively() {
        var allowlist = new Allowlist(java.util.List.of(
            new AllowlistTarget("Staging.Example.com", Environment.STAGING)
        ));

        var result = loader.checkTarget("staging.example.com", allowlist);

        assertThat(result.allowed()).isTrue();
        assertThat(result.environment()).isEqualTo(Environment.STAGING);
    }

    @Test
    void refusesAHostNotOnTheList() {
        var allowlist = new Allowlist(java.util.List.of(new AllowlistTarget("known.example.com", Environment.LOCAL)));

        var result = loader.checkTarget("unknown.example.com", allowlist);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("not in the allowlist");
    }

    @Test
    void doesNotWildcardMatchSubdomains() {
        var allowlist = new Allowlist(java.util.List.of(
            new AllowlistTarget("Staging.Example.com", Environment.STAGING)
        ));

        var result = loader.checkTarget("api.staging.example.com", allowlist);

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void authorizeTargetSurfacesAConfigErrorDistinctFromAnAllowedFalseRejection(@TempDir Path tmpDir) throws IOException {
        withAllowlistPath(tmpDir.resolve("nonexistent.json"), () -> {
            var result = loader.authorizeTarget("anything.example.com");

            assertThat(result.allowed()).isFalse();
            assertThat(result.configError()).isNotNull();
        });
    }

    @Test
    void authorizeTargetAllowsATargetPresentInAValidAllowlist(@TempDir Path tmpDir) throws IOException {
        Path file = tmpDir.resolve("allowlist.json");
        Files.writeString(file, """
            { "targets": [ { "host": "app.example.com", "environment": "local" } ] }
            """);

        withAllowlistPath(file, () -> {
            var result = loader.authorizeTarget("app.example.com");

            assertThat(result.allowed()).isTrue();
            assertThat(result.configError()).isNull();
        });
    }

    /**
     * The production code reads {@code SCANNER_ALLOWLIST_PATH} via {@code System.getenv}, which
     * the JVM can't mutate for the current process. Tests instead exercise
     * {@link AllowlistLoader#resolveAllowlistPath()}'s cwd-relative fallback branch by running
     * with {@code user.dir} temporarily redirected, OR — for the common case — by writing the
     * file at the path that a real env var override would point to and invoking a
     * package-visible test seam. Since production code has no test-only seam, these tests
     * instead redirect via a real environment variable using {@link ProcessBuilder} is overkill
     * for unit tests; instead we rely on {@code user.dir} redirection, which the loader also
     * honors for its default path.
     */
    private void withAllowlistPath(Path file, ThrowingRunnable runnable) throws IOException {
        String originalUserDir = System.getProperty("user.dir");
        Path parentAsCwd = file.getParent();
        // Redirect "cwd" so the loader's default `<cwd>/.security-scanner/allowlist.json` path
        // resolution can be exercised without needing a real env var mutation. We place the file
        // directly at parentAsCwd/.security-scanner/allowlist.json to match that convention.
        try {
            Path securityScannerDir = parentAsCwd.resolve(".security-scanner");
            Files.createDirectories(securityScannerDir);
            Path defaultLocation = securityScannerDir.resolve("allowlist.json");
            if (!file.equals(defaultLocation) && Files.exists(file)) {
                Files.copy(file, defaultLocation, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            System.setProperty("user.dir", parentAsCwd.toString());
            runnable.run();
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
