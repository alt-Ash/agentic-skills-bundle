package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.AntigravityHookRegistrar;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Installs the hooks jar and registers it with Antigravity (analytics always; the guard, verify gate and context
 * injection if chosen).
 */
public final class AntigravityHooksInstaller implements ToolHooksInstaller {

    private final Supplier<Path> bundledJar;
    private final Supplier<Path> targetJar;
    private final Supplier<Path> hooksFile;

    public AntigravityHooksInstaller() {
        this(PackageRoot::hooksJar, HooksJarLocation::jarPath, AntigravityHookRegistrar::defaultFile);
    }

    AntigravityHooksInstaller(Supplier<Path> bundledJar, Supplier<Path> targetJar, Supplier<Path> hooksFile) {
        this.bundledJar = bundledJar;
        this.targetJar = targetJar;
        this.hooksFile = hooksFile;
    }

    @Override
    public Path install(HookInstallOptions options) {
        Path installed = HooksJarLocation.installFrom(bundledJar.get(), targetJar.get());
        AntigravityHookRegistrar.register(hooksFile.get(), installed, options.guard(), options.verify(), options.context());
        return installed;
    }

    @Override
    public int uninstall() {
        return AntigravityHookRegistrar.unregister(hooksFile.get());
    }

    @Override
    public boolean isRegistered() {
        return AntigravityHookRegistrar.isRegistered(hooksFile.get());
    }
}
