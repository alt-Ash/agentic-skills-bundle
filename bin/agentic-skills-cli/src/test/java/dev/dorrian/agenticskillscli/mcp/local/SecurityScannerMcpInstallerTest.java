package dev.dorrian.agenticskillscli.mcp.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** See IssueTicketsMcpInstallerTest: install copies the bundled jar, no Maven involved. */
class SecurityScannerMcpInstallerTest {

    @Test
    void configHasNoCredentialFieldsAtAll() {
        Map<String, Object> config = SecurityScannerMcpInstaller.config("cursor", Path.of("/jar/security-scanner.jar"));
        assertEquals("stdio", config.get("type"));
        assertEquals("java", config.get("command"));
        assertEquals(List.of("-jar", "/jar/security-scanner.jar"), config.get("args"));
        assertFalse(config.containsKey("env"));
    }

    @Test
    void opencodeConfigUsesLocalCommandArray() {
        Map<String, Object> config = SecurityScannerMcpInstaller.config("opencode", Path.of("/jar/x.jar"));
        assertEquals("local", config.get("type"));
        assertEquals(List.of("java", "-jar", "/jar/x.jar"), config.get("command"));
    }

    @Test
    void uninstallRemovesInstallDirRecursively(@TempDir Path tempDir) throws IOException {
        Path installDir = tempDir.resolve("security-scanner");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("security-scanner.jar"), "fake jar bytes");

        McpUninstallResult result = SecurityScannerMcpInstaller.uninstall(installDir);
        assertTrue(result.success());
        assertFalse(result.skipped());
        assertFalse(Files.exists(installDir));
    }

    @Test
    void installCopiesTheBundledPrebuiltJarIntoTheInstallDir(@TempDir Path tempDir) throws IOException {
        Path bundledJar = tempDir.resolve("dist").resolve("security-scanner.jar");
        Files.createDirectories(bundledJar.getParent());
        Files.writeString(bundledJar, "prebuilt fat jar");
        Path installDir = tempDir.resolve("install");

        McpInstallResult result = SecurityScannerMcpInstaller.install(bundledJar, installDir);

        assertTrue(result.success());
        assertEquals(installDir, result.installDir());
        assertEquals("prebuilt fat jar", Files.readString(installDir.resolve("security-scanner.jar")));
    }

    @Test
    void reinstallOverwritesAnOlderJar(@TempDir Path tempDir) throws IOException {
        Path bundledJar = tempDir.resolve("security-scanner.jar");
        Path installDir = tempDir.resolve("install");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("security-scanner.jar"), "old");
        Files.writeString(bundledJar, "new");

        SecurityScannerMcpInstaller.install(bundledJar, installDir);

        assertEquals("new", Files.readString(installDir.resolve("security-scanner.jar")));
    }

    @Test
    void installFailsClearlyWhenTheBundledJarIsMissing(@TempDir Path tempDir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> SecurityScannerMcpInstaller.install(tempDir.resolve("missing.jar"), tempDir.resolve("install")));
        assertTrue(e.getMessage().contains("missing.jar"), e.getMessage());
    }
}
