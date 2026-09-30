package dev.dorrian.agenticskillscli.registry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Java port of {@code bin/install.js}'s {@code MCP_CONFIG} object — 6 tools
 * (gemini and codex are deliberately absent, matching the original; they
 * have no documented MCP config file format).
 */
public final class McpConfigRegistry {

    public static final Map<String, McpConfigDef> ALL = build();

    private McpConfigRegistry() {
    }

    public static Optional<McpConfigDef> get(String toolKey) {
        return Optional.ofNullable(ALL.get(toolKey));
    }

    private static Path home(String... segments) {
        Path p = Paths.get(System.getProperty("user.home"));
        for (String s : segments) {
            p = p.resolve(s);
        }
        return p;
    }

    private static Map<String, McpConfigDef> build() {
        Map<String, McpConfigDef> m = new LinkedHashMap<>();

        m.put("opencode", new McpConfigDef(
            "opencode", home(".config", "opencode", "opencode.json"), "mcp", "opencode"
        ));
        m.put("claude", new McpConfigDef(
            "claude", home(".claude", "settings.json"), "mcpServers", "stdio"
        ));
        m.put("cursor", new McpConfigDef(
            "cursor", home(".cursor", "mcp.json"), "mcpServers", "stdio"
        ));
        m.put("vscode", new McpConfigDef(
            "vscode", AgentToolRegistry.vsCodeUserDir().resolve("mcp.json"), "servers", "stdio"
        ));
        m.put("windsurf", new McpConfigDef(
            "windsurf", home(".codeium", "windsurf", "mcp_config.json"), "mcpServers", "stdio"
        ));
        m.put("zed", new McpConfigDef(
            "zed", home(".config", "zed", "settings.json"), "context_servers", "zed"
        ));

        return m;
    }
}
