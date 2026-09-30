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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the config-builder and uninstall logic only — {@link
 * IssueTicketsMcpInstaller#install} shells out to a real {@code mvn package}
 * build of the sibling {@code mcp/issue-tickets} Maven project, which is out
 * of scope for a fast unit test suite (that project has its own {@code mvn
 * test} suite, run separately via the root {@code test:issue-tickets} script).
 */
class IssueTicketsMcpInstallerTest {

    @Test
    void opencodeConfigUsesEnvPlaceholdersNotResolvedValues() {
        Map<String, Object> config = IssueTicketsMcpInstaller.config("opencode", Path.of("/jar/issue-tickets.jar"), "azure-b64", "github-b64");
        assertEquals("local", config.get("type"));
        assertEquals(List.of("java", "-jar", "/jar/issue-tickets.jar"), config.get("command"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) config.get("environment");
        assertEquals("{env:AZURE_DEVOPS_ACCOUNTS_B64}", env.get("AZURE_DEVOPS_ACCOUNTS_B64"));
        assertEquals("{env:GITHUB_ACCOUNTS_B64}", env.get("GITHUB_ACCOUNTS_B64"));
    }

    @Test
    void nonOpencodeConfigUsesResolvedEnvValues() {
        Map<String, Object> config = IssueTicketsMcpInstaller.config("cursor", Path.of("/jar/issue-tickets.jar"), "azure-b64", "github-b64");
        assertEquals("stdio", config.get("type"));
        assertEquals("java", config.get("command"));
        assertEquals(List.of("-jar", "/jar/issue-tickets.jar"), config.get("args"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) config.get("env");
        assertEquals("azure-b64", env.get("AZURE_DEVOPS_ACCOUNTS_B64"));
        assertEquals("github-b64", env.get("GITHUB_ACCOUNTS_B64"));
    }

    @Test
    void skippedCredentialsAreNotWrittenAsEmptyStringOverrides() {
        Map<String, Object> config = IssueTicketsMcpInstaller.config("cursor", Path.of("/jar/x.jar"), null, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) config.get("env");
        assertFalse(env.containsKey("AZURE_DEVOPS_ACCOUNTS_B64"));
        assertFalse(env.containsKey("GITHUB_ACCOUNTS_B64"));
    }

    @Test
    void zedConfigUsesSourceCustomShape() {
        Map<String, Object> config = IssueTicketsMcpInstaller.config("zed", Path.of("/jar/x.jar"), null, null);
        assertEquals("custom", config.get("source"));
        assertNull(config.get("type"));
    }

    @Test
    void uninstallRemovesInstallDirRecursively(@TempDir Path tempDir) throws IOException {
        Path installDir = tempDir.resolve("issue-tickets");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("issue-tickets.jar"), "fake jar bytes");

        McpUninstallResult result = IssueTicketsMcpInstaller.uninstall(installDir);
        assertTrue(result.success());
        assertFalse(result.skipped());
        assertFalse(Files.exists(installDir));
    }

    @Test
    void uninstallIsSkippedWhenInstallDirDoesNotExist(@TempDir Path tempDir) {
        McpUninstallResult result = IssueTicketsMcpInstaller.uninstall(tempDir.resolve("does-not-exist"));
        assertTrue(result.success());
        assertTrue(result.skipped());
    }
}
