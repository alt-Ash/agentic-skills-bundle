package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMcpServerRegistryTest {

    @Test
    void reactBrowserDebuggerHasChromeDevtoolsAndPlaywrightForOpencodeAndClaude() {
        Map<String, Object> opencodeServers = AgentMcpServerRegistry.serversFor("react-browser-debugger", "opencode");
        assertTrue(opencodeServers.containsKey("chrome-devtools"));
        assertTrue(opencodeServers.containsKey("playwright"));

        Map<String, Object> claudeServers = AgentMcpServerRegistry.serversFor("react-browser-debugger", "claude");
        assertTrue(claudeServers.containsKey("chrome-devtools"));
        assertTrue(claudeServers.containsKey("playwright"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void opencodeChromeDevtoolsUsesCommandArrayNotArgsField() {
        Map<String, Object> servers = AgentMcpServerRegistry.serversFor("react-browser-debugger", "opencode");
        Map<String, Object> chromeDevtools = (Map<String, Object>) servers.get("chrome-devtools");
        assertEquals("local", chromeDevtools.get("type"));
        assertTrue(chromeDevtools.get("command") instanceof java.util.List);
        assertEquals(null, chromeDevtools.get("args"));
    }

    @Test
    void figmaStyleMigratorIsRegisteredForFourNonOpencodeTools() {
        for (String toolKey : new String[] {"claude", "cursor", "gemini", "codex"}) {
            Map<String, Object> servers = AgentMcpServerRegistry.serversFor("figma-style-migrator", toolKey);
            assertTrue(servers.containsKey("figma-mcp"), "expected figma-mcp for " + toolKey);
        }
    }

    @Test
    void unknownAgentOrToolReturnsEmptyMap() {
        assertTrue(AgentMcpServerRegistry.serversFor("no-such-agent", "opencode").isEmpty());
        assertTrue(AgentMcpServerRegistry.serversFor("react-browser-debugger", "windsurf").isEmpty());
    }
}
