package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolRegistryTest {

    @Test
    void hasExactlyEightToolsInSourceOrder() {
        assertEquals(
            List.of("opencode", "claude", "cursor", "gemini", "codex", "vscode", "windsurf", "zed"),
            List.copyOf(AgentToolRegistry.ALL.keySet())
        );
    }

    @Test
    void opencodeSupportsCommandsAndAgentsAndHasAJsonAgentConfig() {
        AgentToolDef opencode = AgentToolRegistry.get("opencode");
        assertTrue(opencode.supportsCommands());
        assertTrue(opencode.supportsAgents());
        assertEquals("agent", opencode.agentConfigKey());
        assertTrue(opencode.agentConfigFile().toString().endsWith("opencode.json"));
    }

    @Test
    void claudeHasNoAgentConfigFile() {
        AgentToolDef claude = AgentToolRegistry.get("claude");
        assertNull(claude.agentConfigFile());
        assertNull(claude.agentConfigKey());
        assertTrue(claude.supportsAgents());
    }

    @Test
    void vscodeHasAgentsGlobalPathButNoAgentsProjectFolder() {
        AgentToolDef vscode = AgentToolRegistry.get("vscode");
        assertTrue(vscode.agentsGlobalPath().toString().contains(".copilot"));
        assertNull(vscode.agentsProjectFolder());
    }

    @Test
    void windsurfAndZedSupportNeitherCommandsNorAgents() {
        assertFalse(AgentToolRegistry.get("windsurf").supportsAgents());
        assertFalse(AgentToolRegistry.get("windsurf").supportsCommands());
        assertFalse(AgentToolRegistry.get("zed").supportsAgents());
        assertFalse(AgentToolRegistry.get("zed").supportsCommands());
    }

    @Test
    void unknownToolKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentToolRegistry.get("not-a-tool"));
    }
}
