package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HooksJarLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * OpenCode has no JSON hook config; it loads plugins from {@code <config>/plugins/}. We write one
 * plugin file from the bundled template with the jar path and the guard flag baked in.
 * Only that file is ever touched.
 */
public final class OpenCodeHooksInstaller implements ToolHooksInstaller {

    public static final String PLUGIN_FILE = "agentic-skills-hooks.js";
    public static final String MARKER = "agentic-skills-hooks plugin";
    static final String RESOURCE = "/opencode/agentic-skills-hooks.js";
    static final String JAR_PLACEHOLDER = "__AGENTIC_SKILLS_HOOKS_JAR__";
    static final String GUARD_PLACEHOLDER = "__AGENTIC_SKILLS_GUARD__";

    private final Supplier<Path> bundledJar;
    private final Supplier<Path> targetJar;
    private final Supplier<Path> pluginsDir;

    public OpenCodeHooksInstaller() {
        this(PackageRoot::hooksJar, HooksJarLocation::jarPath, OpenCodeHooksInstaller::defaultPluginsDir);
    }

    OpenCodeHooksInstaller(Supplier<Path> bundledJar, Supplier<Path> targetJar, Supplier<Path> pluginsDir) {
        this.bundledJar = bundledJar;
        this.targetJar = targetJar;
        this.pluginsDir = pluginsDir;
    }

    static Path defaultPluginsDir() {
        return HomeDir.resolve().resolve(".config").resolve("opencode").resolve("plugins");
    }

    private Path pluginFile() {
        return pluginsDir.get().resolve(PLUGIN_FILE);
    }

    @Override
    public Path install(HookInstallOptions options) {
        Path installed = HooksJarLocation.installFrom(bundledJar.get(), targetJar.get());
        String content = loadTemplate()
            .replace(JAR_PLACEHOLDER, jsString(installed.toAbsolutePath().toString()))
            .replace(GUARD_PLACEHOLDER, String.valueOf(options.guard()));
        try {
            Files.createDirectories(pluginsDir.get());
            Files.writeString(pluginFile(), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return installed;
    }

    @Override
    public int uninstall() {
        if (!isRegistered()) {
            return 0;
        }
        try {
            Files.delete(pluginFile());
            return 1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean isRegistered() {
        Path file = pluginFile();
        try {
            return Files.isRegularFile(file) && Files.readString(file).contains(MARKER);
        } catch (IOException e) {
            return false;
        }
    }

    /** JS string literal body (without quotes). */
    private static String jsString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String loadTemplate() {
        try (InputStream in = OpenCodeHooksInstaller.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
