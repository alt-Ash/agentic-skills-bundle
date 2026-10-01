package dev.dorrian.agenticskillscli.registry;

import dev.dorrian.agenticskillscli.HomeDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Canonical on-disk location for the bundled analytics-hooks jar, referenced
 * by {@link dev.dorrian.agenticskillscli.config.HookRegistrar} when wiring
 * hook registration into Claude Code's {@code settings.json}. Tool-agnostic
 * (unlike the per-opencode-config-dir MCP installers) since hooks fire the
 * same way regardless of which AI CLI invoked them.
 *
 * <p>The jar is copied here from the extracted bundle ({@link #installFrom})
 * by {@link dev.dorrian.agenticskillscli.install.HooksInstaller} right before
 * hooks are registered, so the registered path is stable across upgrades
 * (new versions overwrite it in place).
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

    /** Copies {@code bundledJar} to {@link #jarPath()}, overwriting any older copy. */
    public static Path installFrom(Path bundledJar) {
        return installFrom(bundledJar, jarPath());
    }

    /**
     * Copies {@code bundledJar} to {@code target} (via a temp sibling + move, so a hook firing
     * mid-upgrade never sees a half-written jar). Throws {@link IllegalStateException} if the
     * bundled jar is missing.
     */
    public static Path installFrom(Path bundledJar, Path target) {
        if (!Files.isRegularFile(bundledJar)) {
            throw new IllegalStateException("Bundled hooks jar not found: " + bundledJar);
        }
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(bundledJar, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not install hooks jar to " + target, e);
        }
        return target;
    }
}
