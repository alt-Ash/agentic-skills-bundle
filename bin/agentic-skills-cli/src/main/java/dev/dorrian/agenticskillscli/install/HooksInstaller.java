package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.HookRegistrar;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The hook-install step shared by the Quick and Full install flows: put the
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
        return installAndRegister(PackageRoot.hooksJar(), HooksJarLocation.jarPath(), settingsFile);
    }

    public static Path installAndRegister(Path bundledJar, Path targetJar, Path settingsFile) {
        Path installed = HooksJarLocation.installFrom(bundledJar, targetJar);
        HookRegistrar.registerAll(settingsFile, installed);
        return installed;
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
        return removed;
    }

    /** True if our hooks are registered in {@code settingsFile} or the jar is still installed. */
    public static boolean isInstalled(Path settingsFile) {
        return HookRegistrar.isRegistered(settingsFile) || Files.exists(HooksJarLocation.jarPath());
    }
}
