package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.TomlTestSupport;
import dev.dorrian.agenticskillscli.detect.InstalledMcpServerDetector;
import dev.dorrian.agenticskillscli.mcp.local.IssueTicketsMcpInstaller;
import dev.dorrian.agenticskillscli.registry.GlobalMcpConfigRegistry;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;
import static dev.dorrian.agenticskillscli.registry.McpServerConfig.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end through {@link JsonConfigStore}'s toolKey-level API for gemini (JSON) and codex
 * (delegates to {@link TomlMcpConfigStore}). Paths resolve under Surefire's
 * AGENTIC_SKILLS_HOME_OVERRIDE scratch home, never the real ~/.gemini or ~/.codex.
 */
class GeminiCodexMcpConfigStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GEMINI = McpConfigRegistry.get("gemini").orElseThrow().globalFile();
    private static final Path CODEX = McpConfigRegistry.get("codex").orElseThrow().globalFile();

    @BeforeEach
    @AfterEach
    void clean() throws IOException {
        assertTrue(GEMINI.toString().contains("test-home"), "must run under the test home override: " + GEMINI);
        Files.deleteIfExists(GEMINI);
        Files.deleteIfExists(CODEX);
    }

    private static Map<String, Object> globals(String toolKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engram", GlobalMcpConfigRegistry.engram(toolKey));
        m.put("context7", GlobalMcpConfigRegistry.context7(toolKey, null));
        m.put("figma-mcp", GlobalMcpConfigRegistry.figma(toolKey));
        m.put("issue-tickets", IssueTicketsMcpInstaller.config(toolKey, Path.of("/j/it.jar"), "AZ_SECRET", "GH_SECRET"));
        return m;
    }

    @Test
    void geminiInstallWritesMcpServersAndKeepsOtherSettings() throws IOException {
        Files.createDirectories(GEMINI.getParent());
        Files.writeString(GEMINI, "{ \"ui\": { \"theme\": \"GitHub\" } }");

        List<OperationResult> results = JsonConfigStore.installMcpServers(globals("gemini"), "gemini");
        assertEquals(4, results.size());
        assertTrue(results.stream().noneMatch(OperationResult::skipped));

        String raw = Files.readString(GEMINI);
        assertFalse(raw.contains("AZ_SECRET") || raw.contains("GH_SECRET"), raw);
        assertFalse(raw.contains("\"type\""), raw);
        Map<String, Object> json = MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        assertEquals(Map.of("theme", "GitHub"), json.get("ui"));
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) json.get("mcpServers");
        assertEquals(Map.of("httpUrl", "https://mcp.context7.com/mcp"), servers.get("context7"));

        assertTrue(JsonConfigStore.isMcpServerRegistered("issue-tickets", "gemini"));
        assertEquals(Set.of("engram", "issue-tickets"),
            InstalledMcpServerDetector.detect(List.of("engram", "issue-tickets", "nope"), "gemini"));
    }

    @Test
    void codexFullLifecyclePreservesUserContent() throws IOException {
        String user = "# keep me\nmodel = \"gpt-5\"\n\n[mcp_servers.mine]\ncommand = \"mine\"\n";
        Files.createDirectories(CODEX.getParent());
        Files.writeString(CODEX, user);

        List<OperationResult> results = JsonConfigStore.installMcpServers(globals("codex"), "codex");
        assertTrue(results.stream().allMatch(r -> r.success() && !r.skipped()), results.toString());
        String afterInstall = Files.readString(CODEX);
        assertTrue(afterInstall.startsWith(user));
        assertFalse(afterInstall.contains("AZ_SECRET") || afterInstall.contains("GH_SECRET"), afterInstall);

        Map<String, Object> parsed = TomlTestSupport.parse(afterInstall);
        assertEquals(Map.of("command", "java", "args", List.of("-jar", "/j/it.jar"),
                "env_vars", List.of("AZURE_DEVOPS_ACCOUNTS_B64", "GITHUB_ACCOUNTS_B64")),
            TomlTestSupport.table(parsed, "mcp_servers", "issue-tickets"));
        assertEquals(Map.of("url", "https://mcp.figma.com/mcp"), TomlTestSupport.table(parsed, "mcp_servers", "figma-mcp"));

        // idempotent
        assertTrue(JsonConfigStore.installMcpServers(globals("codex"), "codex").stream().allMatch(OperationResult::skipped));
        assertEquals(afterInstall, Files.readString(CODEX));

        assertTrue(JsonConfigStore.isMcpServerRegistered("mine", "codex"));
        assertEquals(Set.of("engram", "mine"), InstalledMcpServerDetector.detect(List.of("engram", "mine", "x"), "codex"));

        OperationResult ow = JsonConfigStore.overwriteMcpServerEntry("engram", of("command", "engram2", "args", list("mcp")), "codex");
        assertTrue(ow.success());
        assertEquals("engram2", TomlTestSupport.table(TomlTestSupport.parse(Files.readString(CODEX)), "mcp_servers", "engram").get("command"));

        List<OperationResult> removed = JsonConfigStore.uninstallMcpServers(
            List.of("engram", "context7", "figma-mcp", "issue-tickets"), "codex");
        assertTrue(removed.stream().noneMatch(OperationResult::skipped));
        assertEquals(user, Files.readString(CODEX));
    }

    @Test
    void codexMigratesLegacyNpxEntries() throws IOException {
        Files.createDirectories(CODEX.getParent());
        Files.writeString(CODEX, "[mcp_servers.context7]\ncommand = \"npx\"\nargs = [\"-y\", \"@upstash/context7-mcp\"]\n");

        List<OperationResult> migrated = JsonConfigStore.migrateLegacyNpxServers(globals("codex"), "codex");
        assertEquals(1, migrated.size());
        assertEquals("context7", migrated.get(0).name());
        assertEquals(Map.of("url", "https://mcp.context7.com/mcp"),
            TomlTestSupport.table(TomlTestSupport.parse(Files.readString(CODEX)), "mcp_servers", "context7"));
    }

    @Test
    void codexUninstallWithoutConfigFileCreatesNothing() {
        List<OperationResult> results = JsonConfigStore.uninstallMcpServers(List.of("engram"), "codex");
        assertTrue(results.get(0).skipped());
        assertFalse(Files.exists(CODEX));
        assertFalse(JsonConfigStore.isMcpServerRegistered("engram", "codex"));
    }
}
