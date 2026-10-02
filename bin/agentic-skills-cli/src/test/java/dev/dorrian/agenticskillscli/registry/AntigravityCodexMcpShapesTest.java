package dev.dorrian.agenticskillscli.registry;

import dev.dorrian.agenticskillscli.mcp.local.IssueTicketsMcpInstaller;
import dev.dorrian.agenticskillscli.mcp.local.SecurityScannerMcpInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact MCP config shapes for Antigravity (mcp_config.json) and Codex CLI (config.toml). */
class AntigravityCodexMcpShapesTest {

    private static final String C7 = "https://mcp.context7.com/mcp";
    private static final Path JAR = Path.of("/jar/issue-tickets.jar");
    private static final String AZURE_SECRET = "c2VjcmV0LWF6dXJl";
    private static final String GITHUB_SECRET = "c2VjcmV0LWdpdGh1Yg==";

    // ─── engram ──────────────────────────────────────────────────────────────

    @Test
    void engramAntigravity() {
        assertEquals(Map.of("command", "engram", "args", List.of("mcp")), GlobalMcpConfigRegistry.engram("antigravity"));
    }

    @Test
    void engramCodex() {
        assertEquals(Map.of("command", "engram", "args", List.of("mcp")), GlobalMcpConfigRegistry.engram("codex"));
    }

    // ─── remote: context7 / figma ───────────────────────────────────────────

    @Test
    void context7AntigravityUsesServerUrl() {
        // Antigravity rejects the legacy url/httpUrl fields for remote servers
        assertEquals(Map.of("serverUrl", C7), GlobalMcpConfigRegistry.context7("antigravity", null));
        assertEquals(Map.of("serverUrl", C7, "headers", Map.of("Authorization", "Bearer abc")),
            GlobalMcpConfigRegistry.context7("antigravity", "abc"));
    }

    @Test
    void context7CodexUsesUrlAndHttpHeaders() {
        assertEquals(Map.of("url", C7), GlobalMcpConfigRegistry.context7("codex", null));
        assertEquals(Map.of("url", C7, "http_headers", Map.of("Authorization", "Bearer abc")),
            GlobalMcpConfigRegistry.context7("codex", "abc"));
    }

    @Test
    void figmaCodexIsAFigmaCatalogClientSoUsesHostedServer() {
        assertEquals(Map.of("url", "https://mcp.figma.com/mcp"), GlobalMcpConfigRegistry.figma("codex"));
    }

    @Test
    void figmaAntigravityIsNotInTheCatalogSoUsesDesktopServer() {
        assertEquals(Map.of("serverUrl", "http://127.0.0.1:3845/mcp"), GlobalMcpConfigRegistry.figma("antigravity"));
    }

    @Test
    void figmaSetupNoteMentionsAntigravityAndCodex() {
        assertTrue(GlobalMcpConfigRegistry.FIGMA_SETUP_NOTE.contains("Codex"));
        assertTrue(GlobalMcpConfigRegistry.FIGMA_SETUP_NOTE.contains("Antigravity"));
    }

    // ─── local java servers ─────────────────────────────────────────────────

    @Test
    void issueTicketsAntigravityGetsResolvedValuesInTheDocumentedShape() {
        // Whether Antigravity expands $VAR in env is undocumented, so (like Claude Code and Cursor) it gets values,
        // in mcp_config.json's {command, args, env} shape with no "type" key.
        assertEquals(Map.of(
            "command", "java",
            "args", List.of("-jar", JAR.toString()),
            "env", Map.of(
                "AZURE_DEVOPS_ACCOUNTS_B64", AZURE_SECRET,
                "GITHUB_ACCOUNTS_B64", GITHUB_SECRET)
        ), IssueTicketsMcpInstaller.config("antigravity", JAR, AZURE_SECRET, GITHUB_SECRET));
    }

    @Test
    void issueTicketsCodexForwardsEnvVarsByName() {
        assertEquals(Map.of(
            "command", "java",
            "args", List.of("-jar", JAR.toString()),
            "env_vars", List.of("AZURE_DEVOPS_ACCOUNTS_B64", "GITHUB_ACCOUNTS_B64")
        ), IssueTicketsMcpInstaller.config("codex", JAR, AZURE_SECRET, GITHUB_SECRET));
    }

    @Test
    void securityScannerAntigravityAndCodex() {
        Path jar = Path.of("/jar/security-scanner.jar");
        Map<String, Object> expected = Map.of("command", "java", "args", List.of("-jar", jar.toString()));
        assertEquals(expected, SecurityScannerMcpInstaller.config("antigravity", jar));
        assertEquals(expected, SecurityScannerMcpInstaller.config("codex", jar));
    }

    @Test
    void antigravityEntriesCarryNoTypeKeyOrNpx() {
        String all = String.join(" ",
            GlobalMcpConfigRegistry.engram("antigravity").toString(),
            GlobalMcpConfigRegistry.context7("antigravity", null).toString(),
            GlobalMcpConfigRegistry.figma("antigravity").toString(),
            IssueTicketsMcpInstaller.config("antigravity", JAR, AZURE_SECRET, GITHUB_SECRET).toString(),
            SecurityScannerMcpInstaller.config("antigravity", JAR).toString());
        assertFalse(all.contains("type="), all);
        assertFalse(all.contains("npx"), all);
        assertFalse(all.contains("url="), "Antigravity uses serverUrl, never url/httpUrl: " + all);
        assertFalse(all.contains("httpUrl"), all);
    }

    @ParameterizedTest
    @ValueSource(strings = {"codex"})
    void noTokensTypeKeysOrNpxAnywhere(String toolKey) {
        String all = String.join(" ",
            GlobalMcpConfigRegistry.engram(toolKey).toString(),
            GlobalMcpConfigRegistry.context7(toolKey, null).toString(),
            GlobalMcpConfigRegistry.figma(toolKey).toString(),
            IssueTicketsMcpInstaller.config(toolKey, JAR, AZURE_SECRET, GITHUB_SECRET).toString(),
            SecurityScannerMcpInstaller.config(toolKey, JAR).toString());
        assertFalse(all.contains(AZURE_SECRET), all);
        assertFalse(all.contains(GITHUB_SECRET), all);
        assertFalse(all.contains("npx"), all);
        assertFalse(all.contains("type="), "Codex entries carry no 'type' key: " + all);
    }
}
