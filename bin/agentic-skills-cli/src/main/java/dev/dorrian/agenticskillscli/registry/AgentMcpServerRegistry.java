package dev.dorrian.agenticskillscli.registry;

import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code AGENT_MCP_SERVERS} object:
 * agent name -&gt; tool key -&gt; server name -&gt; server config.
 *
 * <p>Empty as of the Java/Spring Boot content refactor (2026-09-30): the two
 * entries this registry used to carry (`react-browser-debugger` -&gt;
 * chrome-devtools/playwright, `figma-style-migrator` -&gt; figma-mcp) were both
 * for agents deleted in that refactor. Neither had a Java/Spring Boot
 * successor — the new {@code spring-boot-backend-engineer} agent verifies
 * its work via actual execution (`mvn test`/`spring-boot:run`), not a
 * browser, so it has no agent-specific MCP wiring to register here. Kept as
 * a live (empty) registry rather than deleted outright, since the shape
 * (agent name -&gt; tool key -&gt; server config) is still the right place for a
 * future agent-specific MCP server, should one ever be needed again.
 */
public final class AgentMcpServerRegistry {

    public static final Map<String, Map<String, Map<String, Map<String, Object>>>> ALL = Map.of();

    private AgentMcpServerRegistry() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> serversFor(String agentName, String toolKey) {
        Map<String, Map<String, Map<String, Object>>> byTool = ALL.get(agentName);
        if (byTool == null) return Map.of();
        Map<String, Map<String, Object>> servers = byTool.get(toolKey);
        return servers != null ? (Map<String, Object>) (Map<String, ?>) servers : Map.of();
    }
}
