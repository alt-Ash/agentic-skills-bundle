package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.HookRegistrar;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HookToolSupport;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;
import dev.dorrian.agenticskillscli.registry.McpConfigDef;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The hook-install step shared by the install flows: put the
 * bundled analytics-hooks jar at {@link HooksJarLocation#jarPath()} first,
 * then register it in Claude Code's {@code settings.json}. Doing both here
 * (and in that order) guarantees no registered hook ever points at a jar that
 * was never copied — if the copy fails, nothing is registered. {@link
 * #uninstall} reverses it in the opposite order for the same reason.
 */
public final class HooksInstaller {

    private HooksInstaller() {
    }

    public static Path installAndRegister(Path settingsFile) {
        return installAndRegister(settingsFile, false);
    }

    public static Path installAndRegister(Path settingsFile, boolean includeGuard) {
        return installAndRegister(PackageRoot.hooksJar(), HooksJarLocation.jarPath(), settingsFile, includeGuard);
    }

    public static Path installAndRegister(Path settingsFile, HookInstallOptions options) {
        return installAndRegister(PackageRoot.hooksJar(), HooksJarLocation.jarPath(), settingsFile, options);
    }

    public static Path installAndRegister(Path bundledJar, Path targetJar, Path settingsFile) {
        return installAndRegister(bundledJar, targetJar, settingsFile, false);
    }

    public static Path installAndRegister(Path bundledJar, Path targetJar, Path settingsFile, boolean includeGuard) {
        return installAndRegister(bundledJar, targetJar, settingsFile, new HookInstallOptions(includeGuard, false, false));
    }

    public static Path installAndRegister(Path bundledJar, Path targetJar, Path settingsFile, HookInstallOptions options) {
        // Replacing a jar that a running hook may have open is the risky part: leave an identical
        // jar untouched, and otherwise rely on installFrom's temp-sibling + move (never half-written).
        Path installed = sameContent(bundledJar, targetJar) ? targetJar : HooksJarLocation.installFrom(bundledJar, targetJar);
        HookRegistrar.registerAll(settingsFile, installed, options);
        return installed;
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.isRegularFile(a) && Files.isRegularFile(b) && Files.mismatch(a, b) == -1L;
        } catch (IOException e) {
            return false;
        }
    }

    // ─── per tool ───────────────────────────────────────────────────────────

    /** Installs the jar and registers hooks for one tool. Only for tools listed in {@link HookToolSupport}. */
    public static Path installForTool(String toolKey, HookInstallOptions options) {
        if (!HookToolSupport.supports(toolKey)) {
            throw new IllegalArgumentException("No hook support for tool: " + toolKey);
        }
        return switch (toolKey) {
            case "claude" -> installAndRegister(claudeSettings(), options);
            default -> ToolHooksInstallers.forTool(toolKey)
                .orElseThrow(() -> new IllegalArgumentException("No hook installer for tool: " + toolKey))
                .install(options);
        };
    }

    /**
     * The opt-in hooks currently registered for this tool, read back from its own config (Claude
     * settings, OpenCode plugin file, Antigravity hooks.json). Unknown, missing or unreadable gives NONE.
     */
    public static HookInstallOptions currentOptionsForTool(String toolKey) {
        try {
            return switch (toolKey) {
                case "claude" -> HookRegistrar.registeredOptIns(claudeSettings());
                default -> switch (ToolHooksInstallers.forTool(toolKey).orElse(null)) {
                    case OpenCodeHooksInstaller o -> o.currentOptions();
                    case AntigravityHooksInstaller a -> a.currentOptions();
                    case null, default -> HookInstallOptions.NONE;
                };
            };
        } catch (RuntimeException e) {
            return HookInstallOptions.NONE;
        }
    }

    /** True only if our hooks are registered in this tool's own config (the shared jar alone does not count). */
    public static boolean isRegisteredForTool(String toolKey) {
        return switch (toolKey) {
            case "claude" -> HookRegistrar.isRegistered(claudeSettings());
            default -> ToolHooksInstallers.forTool(toolKey).map(ToolHooksInstaller::isRegistered).orElse(false);
        };
    }

    /**
     * True if the installed hooks jar is byte-identical to the bundled one, so a refresh changes nothing.
     * Limitation: hook registration entries in tool configs cannot be compared, only the jar.
     */
    public static boolean isCurrentForTool(String toolKey) {
        return sameContent(PackageRoot.hooksJar(), HooksJarLocation.jarPath());
    }

    /** True if our hooks are registered for this tool, or (Claude) the jar is still installed. */
    public static boolean isInstalledForTool(String toolKey) {
        return switch (toolKey) {
            case "claude" -> isInstalled(claudeSettings());
            default -> ToolHooksInstallers.forTool(toolKey).map(ToolHooksInstaller::isRegistered).orElse(false);
        };
    }

    /**
     * Removes this tool's hook registrations; the shared jar is deleted only once no supported tool
     * still has hooks registered. Returns the number of entries removed.
     */
    public static int uninstallForTool(String toolKey) {
        int removed = switch (toolKey) {
            case "claude" -> HookRegistrar.unregisterAll(claudeSettings());
            default -> ToolHooksInstallers.forTool(toolKey).map(ToolHooksInstaller::uninstall).orElse(0);
        };
        boolean stillUsed = HookToolSupport.TOOLS.stream().anyMatch(t -> isRegisteredFor(t));
        if (!stillUsed) {
            deleteInstalledJar(HooksJarLocation.jarPath());
        }
        return removed;
    }

    private static boolean isRegisteredFor(String toolKey) {
        return switch (toolKey) {
            case "claude" -> HookRegistrar.isRegistered(claudeSettings());
            default -> ToolHooksInstallers.forTool(toolKey).map(ToolHooksInstaller::isRegistered).orElse(false);
        };
    }

    private static Path claudeSettings() {
        return McpConfigRegistry.get("claude").map(McpConfigDef::globalFile)
            .orElseThrow(() -> new IllegalStateException("No Claude Code config location known"));
    }

    /** Removes our hooks from Claude Code's {@code settings.json} and deletes the installed jar. */
    public static int uninstall(Path settingsFile) {
        return uninstall(HooksJarLocation.jarPath(), settingsFile);
    }

    /**
     * Unregisters first, then deletes {@code installedJar} (and its directory if that leaves it
     * empty) — so a failure part-way never leaves a registered hook pointing at a deleted jar.
     * Returns the number of hook commands removed from {@code settingsFile}.
     */
    public static int uninstall(Path installedJar, Path settingsFile) {
        int removed = HookRegistrar.unregisterAll(settingsFile);
        deleteInstalledJar(installedJar);
        return removed;
    }

    private static void deleteInstalledJar(Path installedJar) {
        try {
            Files.deleteIfExists(installedJar);
            Path dir = installedJar.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                try (var entries = Files.list(dir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete hooks jar " + installedJar, e);
        }
    }

    /** True if our hooks are registered in {@code settingsFile} or the jar is still installed. */
    public static boolean isInstalled(Path settingsFile) {
        return HookRegistrar.isRegistered(settingsFile) || Files.exists(HooksJarLocation.jarPath());
    }
}
