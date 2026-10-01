package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.HookRegistrar;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;

import java.nio.file.Path;

/**
 * The hook-install step shared by the Quick and Full install flows: put the
 * bundled analytics-hooks jar at {@link HooksJarLocation#jarPath()} first,
 * then register it in Claude Code's {@code settings.json}. Doing both here
 * (and in that order) guarantees no registered hook ever points at a jar that
 * was never copied — if the copy fails, nothing is registered.
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
}
