package dev.dorrian.agenticskillscli.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;
import static dev.dorrian.agenticskillscli.registry.McpServerConfig.of;

/**
 * Java port of {@code bin/install.js}'s {@code AGENT_MCP_SERVERS} object:
 * agent name -&gt; tool key -&gt; server name -&gt; server config. Ported
 * field-for-field from the source read directly on 2026-09-30 (2 agents:
 * react-browser-debugger, figma-style-migrator).
 */
public final class AgentMcpServerRegistry {

    public static final Map<String, Map<String, Map<String, Map<String, Object>>>> ALL = build();

    private AgentMcpServerRegistry() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> serversFor(String agentName, String toolKey) {
        Map<String, Map<String, Map<String, Object>>> byTool = ALL.get(agentName);
        if (byTool == null) return Map.of();
        Map<String, Map<String, Object>> servers = byTool.get(toolKey);
        return servers != null ? (Map<String, Object>) (Map<String, ?>) servers : Map.of();
    }

    private static Map<String, Map<String, Map<String, Map<String, Object>>>> build() {
        Map<String, Map<String, Map<String, Map<String, Object>>>> m = new LinkedHashMap<>();

        Map<String, Map<String, Object>> browserDebuggerOpencode = new LinkedHashMap<>();
        browserDebuggerOpencode.put("chrome-devtools", of(
            "type", "local",
            "command", list("npx", "-y", "chrome-devtools-mcp@latest", "--no-usage-statistics")
        ));
        browserDebuggerOpencode.put("playwright", of(
            "type", "local",
            "command", list("npx", "-y", "@playwright/mcp@latest", "--browser", "chromium", "--headless", "false")
        ));

        Map<String, Map<String, Object>> browserDebuggerClaude = new LinkedHashMap<>();
        browserDebuggerClaude.put("chrome-devtools", of(
            "type", "stdio",
            "command", "npx",
            "args", list("-y", "chrome-devtools-mcp@latest", "--no-usage-statistics")
        ));
        browserDebuggerClaude.put("playwright", of(
            "type", "stdio",
            "command", "npx",
            "args", list("-y", "@playwright/mcp@latest", "--browser", "chromium", "--headless", "false")
        ));

        Map<String, Map<String, Map<String, Object>>> reactBrowserDebugger = new LinkedHashMap<>();
        reactBrowserDebugger.put("opencode", browserDebuggerOpencode);
        reactBrowserDebugger.put("claude", browserDebuggerClaude);
        m.put("react-browser-debugger", reactBrowserDebugger);

        Map<String, Object> figmaEnv = of("FIGMA_ACCESS_TOKEN", "{env:FIGMA_ACCESS_TOKEN}");

        Map<String, Map<String, Object>> figmaOpencode = new LinkedHashMap<>();
        figmaOpencode.put("figma-mcp", of(
            "type", "local",
            "command", list("npx", "-y", "@figma/mcp"),
            "environment", figmaEnv
        ));

        Map<String, Map<String, Map<String, Object>>> figmaStyleMigrator = new LinkedHashMap<>();
        figmaStyleMigrator.put("opencode", figmaOpencode);
        for (String toolKey : new String[] {"claude", "cursor", "gemini", "codex"}) {
            Map<String, Map<String, Object>> byTool = new LinkedHashMap<>();
            byTool.put("figma-mcp", of(
                "type", "stdio",
                "command", "npx",
                "args", list("-y", "@figma/mcp"),
                "env", figmaEnv
            ));
            figmaStyleMigrator.put(toolKey, byTool);
        }
        m.put("figma-style-migrator", figmaStyleMigrator);

        return m;
    }
}
