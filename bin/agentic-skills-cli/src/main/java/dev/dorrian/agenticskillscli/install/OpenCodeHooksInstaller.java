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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern GUARD_LINE = Pattern.compile("(?m)^const GUARD = (true|false)\\s*$");

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

    /** The opt-ins baked into the installed plugin (only guard exists here); NONE if missing or unreadable. */
    HookInstallOptions currentOptions() {
        try {
            Path file = pluginFile();
            if (!Files.isRegularFile(file)) {
                return HookInstallOptions.NONE;
            }
            String content = Files.readString(file);
            Matcher m = GUARD_LINE.matcher(content);
            boolean guard = content.contains(MARKER) && m.find() && Boolean.parseBoolean(m.group(1));
            return new HookInstallOptions(guard, false, false);
        } catch (IOException | RuntimeException e) {
            return HookInstallOptions.NONE;
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
