package dev.dorrian.agenticskillscli.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;
import static dev.dorrian.agenticskillscli.registry.McpServerConfig.of;

/**
 * Java port of {@code bin/install.js}'s {@code figmaMcpEntries} constant and
 * the {@code SKILL_MCPS} object built on top of it — skill name -&gt; tool
 * key -&gt; server name -&gt; server config. Ported field-for-field from the
 * source read directly on 2026-09-30.
 *
 * <p>{@code figma-design-to-code}, {@code figma-generate-library}, and
 * {@code figma-code-connect} all share the exact same {@code figmaMcpEntries}
 * map in the original (the three skill names alias one object) — reproduced
 * here as three keys pointing at the same built map instance.
 */
public final class SkillMcpRegistry {

    public static final Map<String, Map<String, Object>> FIGMA_MCP_ENTRIES = buildFigmaMcpEntries();
    public static final Map<String, Map<String, Map<String, Object>>> ALL = build();

    private SkillMcpRegistry() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> serversFor(String skillName, String toolKey) {
        Map<String, Map<String, Object>> byTool = ALL.get(skillName);
        if (byTool == null) return Map.of();
        Map<String, Object> servers = (Map<String, Object>) (Map<String, ?>) byTool.get(toolKey);
        return servers != null ? servers : Map.of();
    }

    private static Map<String, Object> figmaEnv() {
        return of("FIGMA_ACCESS_TOKEN", "{env:FIGMA_ACCESS_TOKEN}");
    }

    private static Map<String, Map<String, Object>> buildFigmaMcpEntries() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();

        m.put("opencode", of(
            "figma-mcp", of(
                "type", "local",
                "command", list("npx", "-y", "@figma/mcp"),
                "environment", figmaEnv()
            )
        ));

        for (String toolKey : new String[] {"claude", "cursor", "gemini", "codex", "vscode", "windsurf"}) {
            m.put(toolKey, of(
                "figma-mcp", of(
                    "type", "stdio",
                    "command", "npx",
                    "args", list("-y", "@figma/mcp"),
                    "env", figmaEnv()
                )
            ));
        }

        m.put("zed", of(
            "figma-mcp", of(
                "source", "custom",
                "command", "npx",
                "args", list("-y", "@figma/mcp"),
                "env", figmaEnv()
            )
        ));

        return m;
    }

    private static Map<String, Map<String, Map<String, Object>>> build() {
        Map<String, Map<String, Map<String, Object>>> m = new LinkedHashMap<>();

        m.put("figma-design-to-code", FIGMA_MCP_ENTRIES);
        m.put("figma-generate-library", FIGMA_MCP_ENTRIES);
        m.put("figma-code-connect", FIGMA_MCP_ENTRIES);

        Map<String, Map<String, Object>> muiMigration = new LinkedHashMap<>();
        for (String toolKey : new String[] {"opencode", "claude", "cursor", "vscode", "windsurf"}) {
            muiMigration.put(toolKey, of(
                "mui-mcp", "opencode".equals(toolKey)
                    ? of("type", "local", "command", list("npx", "-y", "@mui/mcp@latest"))
                    : of("type", "stdio", "command", "npx", "args", list("-y", "@mui/mcp@latest"))
            ));
        }
        // zed's entry has a genuinely different shape and server key ("mui-mcp-server",
        // not "mui-mcp") — no "type"/"source" field at all, just a nested "command" object.
        muiMigration.put("zed", of(
            "mui-mcp-server", of(
                "command", of(
                    "path", "npx",
                    "args", list("-y", "@mui/mcp@latest"),
                    "env", Map.of()
                )
            )
        ));
        m.put("mui-migration", muiMigration);

        return m;
    }
}
