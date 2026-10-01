package dev.dorrian.agenticskillscli.registry;

import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code figmaMcpEntries} constant and
 * the {@code SKILL_MCPS} object built on top of it — skill name -&gt; tool
 * key -&gt; server name -&gt; server config.
 *
 * <p>Empty as of the Java/Spring Boot content refactor (2026-09-30): every
 * entry this registry used to carry ({@code figma-design-to-code}, {@code
 * figma-generate-library}, {@code figma-code-connect} -&gt; figma-mcp; {@code
 * mui-migration} -&gt; mui-mcp) was for a skill deleted in that refactor, none
 * with a Java/Spring Boot successor. Kept as a live (empty) registry rather
 * than deleted outright, since the shape (skill name -&gt; tool key -&gt; server
 * config) is still the right place for a future skill-specific MCP server.
 */
public final class SkillMcpRegistry {

    public static final Map<String, Map<String, Map<String, Object>>> ALL = Map.of();

    private SkillMcpRegistry() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> serversFor(String skillName, String toolKey) {
        Map<String, Map<String, Object>> byTool = ALL.get(skillName);
        if (byTool == null) return Map.of();
        Map<String, Object> servers = (Map<String, Object>) (Map<String, ?>) byTool.get(toolKey);
        return servers != null ? servers : Map.of();
    }
}
