package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.registry.HookInstallOptions;

import java.nio.file.Path;

/** Installs, detects and removes the hooks for one non-Claude AI tool (OpenCode, Antigravity, ...). */
public interface ToolHooksInstaller {

    /** Copies/refreshes the hooks jar as needed and registers hooks for this tool. Returns the installed jar. */
    Path install(HookInstallOptions options);

    /** Removes this tool's registrations (not the shared jar). Returns the number of entries removed. */
    int uninstall();

    /** True if our hooks are currently registered for this tool. */
    boolean isRegistered();
}
