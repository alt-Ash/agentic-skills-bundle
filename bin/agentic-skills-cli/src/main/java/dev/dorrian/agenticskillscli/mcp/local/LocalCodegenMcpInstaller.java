package dev.dorrian.agenticskillscli.mcp.local;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.registry.McpServerConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;

/**
 * A Java MCP that offloads mechanical code generation to a local model; needs no credentials; the capability check that gates it runs at runtime in the MCP's health tool.
 */
public final class LocalCodegenMcpInstaller {

    public static final Path DEFAULT_INSTALL_DIR =
        HomeDir.resolve().resolve(".config").resolve("opencode").resolve("mcp").resolve("local-codegen");
    public static final String JAR_NAME = "local-codegen.jar";

    private LocalCodegenMcpInstaller() {
    }

    /**
     * Copies the prebuilt fat jar shipped in the bundle into {@code installDir}, overwriting
     * any older copy. No build step and no child process: end users need only a JRE.
     */
    public static McpInstallResult install(Path bundledJar, Path installDir) {
        if (!Files.isRegularFile(bundledJar)) {
            throw new IllegalStateException("Bundled local-codegen MCP jar not found: " + bundledJar);
        }
        try {
            Files.createDirectories(installDir);
            Files.copy(bundledJar, installDir.resolve(JAR_NAME), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new McpInstallResult(true, installDir);
    }

    public static Map<String, Object> config(String toolKey) {
        return config(toolKey, DEFAULT_INSTALL_DIR.resolve(JAR_NAME));
    }

    public static Map<String, Object> config(String toolKey, Path jarPath) {
        String jar = jarPath.toString();
        if ("opencode".equals(toolKey)) {
            return McpServerConfig.of("type", "local", "command", list("java", "-jar", jar));
        }
        if ("zed".equals(toolKey)) {
            return McpServerConfig.of("source", "custom", "command", "java", "args", list("-jar", jar));
        }
        if ("antigravity".equals(toolKey) || "codex".equals(toolKey)) {
            return McpServerConfig.of("command", "java", "args", list("-jar", jar));
        }
        return McpServerConfig.of("type", "stdio", "command", "java", "args", list("-jar", jar));
    }

    public static McpUninstallResult uninstall() {
        return uninstall(DEFAULT_INSTALL_DIR);
    }

    public static McpUninstallResult uninstall(Path installDir) {
        if (Files.exists(installDir)) {
            try {
                deleteRecursive(installDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new McpUninstallResult(true, false);
        }
        return new McpUninstallResult(true, true);
    }

    private static void deleteRecursive(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}