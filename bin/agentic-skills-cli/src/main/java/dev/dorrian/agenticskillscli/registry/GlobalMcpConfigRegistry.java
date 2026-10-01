package dev.dorrian.agenticskillscli.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;
import static dev.dorrian.agenticskillscli.registry.McpServerConfig.of;

/**
 * Config builders for the three global MCP servers offered directly by the
 * Quick-install and Full-install wizards (Engram, Context7, Figma) —
 * independent of the {@code SKILL_MCPS}/{@code AGENT_MCP_SERVERS}
 * registries.
 *
 * <p>Context7 and Figma point at the vendors' hosted HTTP endpoints, so users
 * need no Node/npx. Only the 6 tools in {@link McpConfigRegistry} (plus
 * {@code claude}, which goes through {@code ClaudeCliMcpRegistrar}) ever
 * receive these configs; gemini/codex have no MCP config writer.
 *
 * <p>Per-tool remote shapes (verified 2026-10-01):
 * <ul>
 *   <li>opencode: {@code {type:"remote", url, enabled, headers?}} — opencode.ai/docs/mcp-servers</li>
 *   <li>claude: {@code {type:"http", url, headers?}} — translated to
 *       {@code claude mcp add --transport http [--header ...]} (see {@code claude mcp add --help})</li>
 *   <li>cursor: {@code {url, headers?}} — cursor.com/docs/context/mcp</li>
 *   <li>vscode: {@code {type:"http", url, headers?}} — Context7/Figma VS Code docs</li>
 *   <li>windsurf: {@code {serverUrl, headers?}} — docs.windsurf.com/windsurf/cascade/mcp
 *       (now docs.devin.ai/desktop/cascade/mcp)</li>
 *   <li>zed: {@code {url, headers?}} under {@code context_servers} — zed.dev/docs/ai/mcp
 *       (Zed starts the MCP OAuth flow when no Authorization header is set)</li>
 * </ul>
 */
public final class GlobalMcpConfigRegistry {

    /** Context7 hosted endpoint — github.com/upstash/context7 docs/resources/all-clients.mdx. */
    static final String CONTEXT7_URL = "https://mcp.context7.com/mcp";

    /**
     * Figma's hosted server — OAuth only (no PAT), and only clients listed in
     * the Figma MCP Catalog (figma.com/mcp-catalog) may connect. Of our tools
     * that is claude, cursor and vscode
     * (developers.figma.com/docs/figma-mcp-server/remote-server-installation).
     */
    static final String FIGMA_REMOTE_URL = "https://mcp.figma.com/mcp";

    /**
     * Figma desktop app's local server (Dev Mode) — no auth, not client-gated
     * (developers.figma.com/docs/figma-mcp-server/local-server-installation).
     * Used for opencode/windsurf/zed: Zed is catalogued as local-server-only,
     * and opencode/windsurf are absent from the catalog entirely, so the
     * hosted server's OAuth would reject them. The previous npx fallback
     * ({@code @figma/mcp}) is not an option: that package 404s on npm.
     */
    static final String FIGMA_DESKTOP_URL = "http://127.0.0.1:3845/mcp";

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

    /**
     * apiKey may be null/blank for the anonymous (rate-limited) tier; the
     * hosted server works without one. When present it is sent as
     * {@code Authorization: Bearer <key>} (not {@code CONTEXT7_API_KEY}:
     * Context7's Cursor docs warn underscore header names get dropped by
     * proxies).
     */
    public static Map<String, Object> context7(String toolKey, String apiKey) {
        Map<String, Object> headers = apiKey == null || apiKey.isBlank()
            ? null
            : of("Authorization", "Bearer " + apiKey);
        return remote(toolKey, CONTEXT7_URL, headers);
    }

    /** OAuth-only: no token is written anywhere; each client runs Figma's sign-in flow on first use. */
    public static Map<String, Object> figma(String toolKey) {
        boolean inFigmaCatalog = "claude".equals(toolKey) || "cursor".equals(toolKey) || "vscode".equals(toolKey);
        return remote(toolKey, inFigmaCatalog ? FIGMA_REMOTE_URL : FIGMA_DESKTOP_URL, null);
    }

    private static Map<String, Object> remote(String toolKey, String url, Map<String, Object> headers) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        switch (toolKey) {
            case "opencode" -> {
                cfg.put("type", "remote");
                cfg.put("url", url);
                cfg.put("enabled", true);
            }
            case "claude", "vscode" -> {
                cfg.put("type", "http");
                cfg.put("url", url);
            }
            case "windsurf" -> cfg.put("serverUrl", url);
            // cursor, zed, and any future JSON tool: plain {url}
            default -> cfg.put("url", url);
        }
        if (headers != null) {
            cfg.put("headers", headers);
        }
        return cfg;
    }
}
