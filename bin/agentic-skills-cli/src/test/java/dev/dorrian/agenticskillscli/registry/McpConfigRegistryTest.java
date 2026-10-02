package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigRegistryTest {

    @Test
    void hasExactlyEightTools() {
        assertEquals(8, McpConfigRegistry.ALL.size());
        assertTrue(McpConfigRegistry.ALL.containsKey("antigravity"));
        assertTrue(McpConfigRegistry.ALL.containsKey("codex"));
        assertTrue(McpConfigRegistry.ALL.containsKey("opencode"));
        assertTrue(McpConfigRegistry.ALL.containsKey("claude"));
        assertTrue(McpConfigRegistry.ALL.containsKey("cursor"));
        assertTrue(McpConfigRegistry.ALL.containsKey("vscode"));
        assertTrue(McpConfigRegistry.ALL.containsKey("windsurf"));
        assertTrue(McpConfigRegistry.ALL.containsKey("zed"));
    }

    @Test
    void antigravityUsesTheSharedMcpConfigJson() {
        McpConfigDef agy = McpConfigRegistry.get("antigravity").orElseThrow();
        assertTrue(agy.globalFile().endsWith(java.nio.file.Path.of(".gemini", "config", "mcp_config.json")));
        assertEquals("mcpServers", agy.mcpKey());
    }

    @Test
    void codexUsesConfigTomlMcpServersTables() {
        McpConfigDef codex = McpConfigRegistry.get("codex").orElseThrow();
        assertTrue(codex.globalFile().endsWith(java.nio.file.Path.of(".codex", "config.toml")));
        assertEquals("mcp_servers", codex.mcpKey());
        assertEquals("toml", codex.serverFormat());
    }

    @Test
    void opencodeUsesMcpKey() {
        assertEquals("mcp", McpConfigRegistry.get("opencode").orElseThrow().mcpKey());
    }

    @Test
    void claudeEntryExistsButIsDeadForInstallPurposes() {
        McpConfigDef claude = McpConfigRegistry.get("claude").orElseThrow();
        assertEquals("mcpServers", claude.mcpKey());
        assertTrue(claude.globalFile().toString().endsWith("settings.json"));
    }

    @Test
    void zedUsesContextServersKey() {
        assertEquals("context_servers", McpConfigRegistry.get("zed").orElseThrow().mcpKey());
    }

    @Test
    void windsurfKeepsTheCodeiumFileAndAlsoTargetsTheDevinConfigFile() {
        McpConfigDef windsurf = McpConfigRegistry.get("windsurf").orElseThrow();
        assertTrue(windsurf.globalFile().endsWith(Path.of(".codeium", "windsurf", "mcp_config.json")));
        assertEquals(List.of(AgentToolRegistry.devinConfigDir().resolve("mcp_config.json")), windsurf.extraFiles());
        assertEquals("mcpServers", windsurf.mcpKey());
    }

    @Test
    void everyOtherToolHasNoExtraFiles() {
        McpConfigRegistry.ALL.forEach((key, def) -> {
            if (!"windsurf".equals(key)) {
                assertEquals(List.of(), def.extraFiles(), key);
            }
        });
    }

    @Test
    void fourArgConstructorDefaultsToNoExtraFiles() {
        assertEquals(List.of(), new McpConfigDef("x", Path.of("f.json"), "mcpServers", "stdio").extraFiles());
    }
}
