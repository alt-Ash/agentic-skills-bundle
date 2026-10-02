package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.GeminiHookRegistrar;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Gemini CLI hooks: copies the shared jar, then registers the analytics hooks in
 * {@code ~/.gemini/settings.json}. Guard/verify/context options are ignored for Gemini (its tool
 * names differ from the Claude names the guard matches).
 */
public final class GeminiHooksInstaller implements ToolHooksInstaller {

    private final Supplier<Path> bundledJar;
    private final Supplier<Path> targetJar;
    private final Supplier<Path> settingsFile;

    public GeminiHooksInstaller() {
        this(PackageRoot::hooksJar, HooksJarLocation::jarPath, GeminiHooksInstaller::defaultSettings);
    }

    GeminiHooksInstaller(Supplier<Path> bundledJar, Supplier<Path> targetJar, Supplier<Path> settingsFile) {
        this.bundledJar = bundledJar;
        this.targetJar = targetJar;
        this.settingsFile = settingsFile;
    }

    static Path defaultSettings() {
        return HomeDir.resolve().resolve(".gemini").resolve("settings.json");
    }

    @Override
    public Path install(HookInstallOptions options) {
        Path installed = HooksJarLocation.installFrom(bundledJar.get(), targetJar.get());
        GeminiHookRegistrar.register(settingsFile.get(), installed);
        return installed;
    }

    @Override
    public int uninstall() {
        return GeminiHookRegistrar.unregister(settingsFile.get());
    }

    @Override
    public boolean isRegistered() {
        return GeminiHookRegistrar.isRegistered(settingsFile.get());
    }
}
