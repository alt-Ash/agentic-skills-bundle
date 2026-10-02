package dev.dorrian.agenticskillscli.registry;

import dev.dorrian.agenticskillscli.HomeDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-tool MCP config file locations — originally a port of {@code bin/install.js}'s
 * {@code MCP_CONFIG} object, now covering all 8 tools:
 * <ul>
 *   <li>antigravity: {@code ~/.gemini/config/mcp_config.json} → {@code mcpServers} (stdio {@code
 *       {command,args,env}}, remote {@code {serverUrl,headers}}; shared by the CLI and the desktop IDE;
 *       shape verified with {@code agy mcp add})</li>
 *   <li>codex: {@code ~/.codex/config.toml} → {@code [mcp_servers.<name>]} tables, edited
 *       text-level by {@link dev.dorrian.agenticskillscli.config.TomlMcpConfigStore}
 *       (serverFormat {@code "toml"}) — learn.chatgpt.com/docs/extend/mcp</li>
 *   <li>windsurf (Devin Desktop) is the only entry with {@link McpConfigDef#extraFiles()}:
 *       {@code ~/.codeium/windsurf/mcp_config.json} is always written, {@code
 *       ~/.config/devin/mcp_config.json} (see {@link AgentToolRegistry#devinConfigDir()})
 *       only when that directory exists.</li>
 * </ul>
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
        // Antigravity keeps MCP servers in a standalone mcp_config.json (verified with `agy mcp add`), shared by the
        // CLI and the desktop IDE. Remote servers use serverUrl; see GlobalMcpConfigRegistry.
        m.put("antigravity", new McpConfigDef(
            "antigravity", home(".gemini", "config", "mcp_config.json"), "mcpServers", "stdio"
        ));
        m.put("codex", new McpConfigDef(
            "codex", home(".codex", "config.toml"), "mcp_servers", "toml"
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
