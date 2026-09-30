package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMcpRegistryTest {

    @Test
    void figmaSkillsAllShareTheSameEntriesObject() {
        assertEquals(
            SkillMcpRegistry.serversFor("figma-design-to-code", "claude"),
            SkillMcpRegistry.serversFor("figma-code-connect", "claude")
        );
        assertTrue(SkillMcpRegistry.serversFor("figma-generate-library", "opencode").containsKey("figma-mcp"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void muiMigrationZedUsesDifferentServerKeyAndNestedCommandShape() {
        Map<String, Object> zedServers = SkillMcpRegistry.serversFor("mui-migration", "zed");
        assertTrue(zedServers.containsKey("mui-mcp-server"));
        Map<String, Object> serverConfig = (Map<String, Object>) zedServers.get("mui-mcp-server");
        assertTrue(serverConfig.get("command") instanceof Map);
        assertEquals(null, serverConfig.get("type"));
    }

    @Test
    void muiMigrationHasNoGeminiOrCodexEntry() {
        assertTrue(SkillMcpRegistry.serversFor("mui-migration", "gemini").isEmpty());
        assertTrue(SkillMcpRegistry.serversFor("mui-migration", "codex").isEmpty());
    }
}
