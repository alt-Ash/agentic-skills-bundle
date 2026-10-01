package dev.dorrian.agenticskillscli.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP config file location/shape for one AI tool — port of one entry in
 * {@code bin/install.js}'s {@code MCP_CONFIG} object.
 *
 * <p>{@code extraFiles} are additional config files the same servers are
 * mirrored into, for tools whose documented config location is ambiguous
 * (Devin Desktop, formerly Windsurf: {@code ~/.codeium/windsurf/mcp_config.json}
 * per its FAQ/CLI docs, {@code ~/.config/devin/mcp_config.json} per its MCP
 * page). An extra file is only ever <em>written</em> when its parent directory
 * already exists — the installer never creates another tool's config dir —
 * but uninstall and detection always check every file. Empty for every other
 * tool, which then behaves exactly as with a single {@code globalFile}.
 *
 * <p>Note on Claude Code: this entry is kept for API-shape parity, but is
 * effectively dead for MCP install purposes — Claude Code never reads MCP
 * servers from {@code settings.json}, only from {@code .mcp.json} or {@code
 * ~/.claude.json}. All MCP install/uninstall/detect code special-cases
 * {@code toolKey == "claude"} to shell out to the {@code claude mcp} CLI via
 * {@link dev.dorrian.agenticskillscli.config.ClaudeCliMcpRegistrar} instead
 * of touching this file — see the original comment at bin/install.js:206-209.
 */
public record McpConfigDef(String toolKey, Path globalFile, String mcpKey, String serverFormat, List<Path> extraFiles) {

    public McpConfigDef {
        extraFiles = extraFiles == null ? List.of() : List.copyOf(extraFiles);
    }

    /** Single-file config (every tool except Devin Desktop). */
    public McpConfigDef(String toolKey, Path globalFile, String mcpKey, String serverFormat) {
        this(toolKey, globalFile, mcpKey, serverFormat, List.of());
    }

    /** {@code globalFile} followed by every extra file — what uninstall and detection check. */
    public List<Path> allFiles() {
        List<Path> files = new ArrayList<>(1 + extraFiles.size());
        files.add(globalFile);
        files.addAll(extraFiles);
        return List.copyOf(files);
    }

    /** {@code globalFile} plus the extra files whose parent directory exists — what install/overwrite write to. */
    public List<Path> writableFiles() {
        List<Path> files = new ArrayList<>(1 + extraFiles.size());
        files.add(globalFile);
        for (Path extra : extraFiles) {
            if (extra.getParent() != null && Files.isDirectory(extra.getParent())) {
                files.add(extra);
            }
        }
        return List.copyOf(files);
    }
}
