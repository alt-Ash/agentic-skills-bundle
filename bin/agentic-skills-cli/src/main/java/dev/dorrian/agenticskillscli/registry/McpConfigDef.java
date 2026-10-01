package dev.dorrian.agenticskillscli.registry;

import java.nio.file.Path;

/**
 * MCP config file location/shape for one AI tool — port of one entry in
 * {@code bin/install.js}'s {@code MCP_CONFIG} object.
 *
 * <p>Note on Claude Code: this entry is kept for API-shape parity, but is
 * effectively dead for MCP install purposes — Claude Code never reads MCP
 * servers from {@code settings.json}, only from {@code .mcp.json} or {@code
 * ~/.claude.json}. All MCP install/uninstall/detect code special-cases
 * {@code toolKey == "claude"} to shell out to the {@code claude mcp} CLI via
 * {@link dev.dorrian.agenticskillscli.config.ClaudeCliMcpRegistrar} instead
 * of touching this file — see the original comment at bin/install.js:206-209.
 */
public record McpConfigDef(String toolKey, Path globalFile, String mcpKey, String serverFormat) {
}
