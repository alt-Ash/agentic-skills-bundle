package dev.dorrian.agenticskillscli.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;
import static dev.dorrian.agenticskillscli.registry.McpServerConfig.of;

/**
 * Java port of {@code bin/install.js}'s three global-tool MCP config
 * builders: {@code engramMcpConfig}, {@code context7McpConfig}, {@code
 * figmaMcpConfig} (source read directly, lines 242-318, on 2026-09-30).
 * These are independent of the {@code SKILL_MCPS}/{@code AGENT_MCP_SERVERS}
 * registries — Engram/Context7/Figma are offered directly in both the
 * Quick-install and Full-install wizard flows, not tied to any particular
 * skill or agent.
 */
public final class GlobalMcpConfigRegistry {

    private GlobalMcpConfigRegistry() {
    }

    /** Engram must be installed locally (`brew install gentleman-programming/tap/engram`). */
    public static Map<String, Object> engram(String toolKey) {
        if ("opencode".equals(toolKey)) {
            return of("type", "local", "command", list("engram", "mcp"));
        }
        if ("zed".equals(toolKey)) {
            return of("source", "custom", "command", "engram", "args", list("mcp"));
        }
        return of("type", "stdio", "command", "engram", "args", list("mcp"));
    }

    /** apiKey may be null for the free (rate-limited) tier. */
    public static Map<String, Object> context7(String toolKey, String apiKey) {
        boolean hasKey = apiKey != null && !apiKey.isEmpty();

        if ("opencode".equals(toolKey)) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("type", "remote");
            cfg.put("url", "https://mcp.context7.com/mcp");
            cfg.put("enabled", true);
            if (hasKey) cfg.put("headers", of("CONTEXT7_API_KEY", apiKey));
            return cfg;
        }
        if ("zed".equals(toolKey)) {
            return of("source", "custom", "command", "npx", "args", context7Args(apiKey));
        }
        if ("vscode".equals(toolKey)) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("type", "http");
            cfg.put("url", "https://mcp.context7.com/mcp");
            if (hasKey) cfg.put("headers", of("CONTEXT7_API_KEY", apiKey));
            return cfg;
        }
        if ("windsurf".equals(toolKey)) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("serverUrl", "https://mcp.context7.com/mcp");
            if (hasKey) cfg.put("headers", of("CONTEXT7_API_KEY", apiKey));
            return cfg;
        }
        // claude, cursor -> stdio format with npx
        return of("type", "stdio", "command", "npx", "args", context7Args(apiKey));
    }

    private static java.util.List<String> context7Args(String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            return list("-y", "@upstash/context7-mcp", "--api-key", apiKey);
        }
        return list("-y", "@upstash/context7-mcp");
    }

    /** Requires {@code @figma/mcp} to be available via npx. Access token is referenced via {env:FIGMA_ACCESS_TOKEN}. */
    public static Map<String, Object> figma(String toolKey) {
        Map<String, Object> env = of("FIGMA_ACCESS_TOKEN", "{env:FIGMA_ACCESS_TOKEN}");

        if ("opencode".equals(toolKey)) {
            return of("type", "local", "command", list("npx", "-y", "@figma/mcp"), "environment", env);
        }
        if ("zed".equals(toolKey)) {
            return of("source", "custom", "command", "npx", "args", list("-y", "@figma/mcp"), "env", env);
        }
        return of("type", "stdio", "command", "npx", "args", list("-y", "@figma/mcp"), "env", env);
    }
}
