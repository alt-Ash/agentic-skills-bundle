package dev.dorrian.agenticskillscli.registry;

import dev.dorrian.agenticskillscli.HomeDir;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical on-disk location for the bundled analytics-hooks jar, referenced
 * by {@link dev.dorrian.agenticskillscli.config.HookRegistrar} when wiring
 * hook registration into Claude Code's {@code settings.json}. Tool-agnostic
 * (unlike the per-opencode-config-dir MCP installers) since hooks fire the
 * same way regardless of which AI CLI invoked them.
 *
 * <p>This class only names where the jar lives — placing it there is a
 * packaging-time concern (bundling {@code hooks/agentic-skills-hooks}'s
 * built artifact into the npm package and copying it here at install time),
 * out of scope for the installer's engine/wizard layer built so far.
 */
public final class HooksJarLocation {

    public static final Path DEFAULT_INSTALL_DIR =
        HomeDir.resolve().resolve(".agentic-skills").resolve("hooks");
    public static final String JAR_NAME = "agentic-skills-hooks.jar";

    private HooksJarLocation() {
    }

    public static Path jarPath() {
        return DEFAULT_INSTALL_DIR.resolve(JAR_NAME);
    }
}
