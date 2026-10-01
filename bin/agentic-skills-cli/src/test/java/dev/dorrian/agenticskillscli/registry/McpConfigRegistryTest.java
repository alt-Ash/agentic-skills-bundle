package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigRegistryTest {

    @Test
    void hasExactlySixTools() {
        assertEquals(6, McpConfigRegistry.ALL.size());
        assertTrue(McpConfigRegistry.ALL.containsKey("opencode"));
        assertTrue(McpConfigRegistry.ALL.containsKey("claude"));
        assertTrue(McpConfigRegistry.ALL.containsKey("cursor"));
        assertTrue(McpConfigRegistry.ALL.containsKey("vscode"));
        assertTrue(McpConfigRegistry.ALL.containsKey("windsurf"));
        assertTrue(McpConfigRegistry.ALL.containsKey("zed"));
    }

    @Test
    void geminiAndCodexAreDeliberatelyAbsent() {
        assertTrue(McpConfigRegistry.get("gemini").isEmpty());
        assertTrue(McpConfigRegistry.get("codex").isEmpty());
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
}
