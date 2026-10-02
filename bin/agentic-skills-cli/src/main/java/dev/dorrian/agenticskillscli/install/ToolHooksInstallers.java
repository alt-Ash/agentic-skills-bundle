package dev.dorrian.agenticskillscli.install;

import java.util.Map;
import java.util.Optional;

/**
 * The per-tool hook installers for tools other than Claude Code, keyed by tool key. A tool must also
 * be listed in {@code HookToolSupport.TOOLS} for the install flows to offer hooks for it. Owned by
 * the Gemini/OpenCode slice; empty until those installers exist.
 */
public final class ToolHooksInstallers {

    private static final Map<String, ToolHooksInstaller> INSTALLERS = Map.of();

    private ToolHooksInstallers() {
    }

    public static Optional<ToolHooksInstaller> forTool(String toolKey) {
        return Optional.ofNullable(INSTALLERS.get(toolKey));
    }
}
