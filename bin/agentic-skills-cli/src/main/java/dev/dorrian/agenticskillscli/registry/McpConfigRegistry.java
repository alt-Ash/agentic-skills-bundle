package dev.dorrian.agenticskillscli.registry;

import dev.dorrian.agenticskillscli.HomeDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Java port of {@code bin/install.js}'s {@code MCP_CONFIG} object — 6 tools
 * (gemini and codex are deliberately absent, matching the original; they
 * have no documented MCP config file format).
 *
 * <p>{@code windsurf} (Devin Desktop) is the only entry with {@link
 * McpConfigDef#extraFiles()}: {@code ~/.codeium/windsurf/mcp_config.json} is
 * always written, {@code ~/.config/devin/mcp_config.json} (see {@link
 * AgentToolRegistry#devinConfigDir()}) only when that directory exists.
 */
public final class McpConfigRegistry {

    public static final Map<String, McpConfigDef> ALL = build();

    private McpConfigRegistry() {
    }

    public static Optional<McpConfigDef> get(String toolKey) {
        return Optional.ofNullable(ALL.get(toolKey));
    }

    private static Path home(String... segments) {
        Path p = HomeDir.resolve();
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
        // Devin Desktop (formerly Windsurf): its FAQ and CLI docs keep the per-user MCP config at
        // ~/.codeium/windsurf/mcp_config.json, while its MCP page says ~/.config/devin/mcp_config.json
        // — so always write the former, and mirror into the latter when that dir already exists.
        m.put("windsurf", new McpConfigDef(
            "windsurf", home(".codeium", "windsurf", "mcp_config.json"), "mcpServers", "stdio",
            List.of(AgentToolRegistry.devinConfigDir().resolve("mcp_config.json"))
        ));
        m.put("zed", new McpConfigDef(
            "zed", home(".config", "zed", "settings.json"), "context_servers", "zed"
        ));

        return m;
    }
}
