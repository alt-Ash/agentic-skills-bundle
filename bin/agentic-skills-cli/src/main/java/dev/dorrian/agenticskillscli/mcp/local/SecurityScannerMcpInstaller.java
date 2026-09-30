package dev.dorrian.agenticskillscli.mcp.local;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.registry.McpServerConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;

/**
 * Java port of {@code bin/install.js}'s {@code installSecurityScannerMcp} /
 * {@code securityScannerMcpConfig} / {@code uninstallSecurityScannerMcp}.
 * Unlike issue-tickets, this MCP needs no credentials — it is gated
 * entirely by a project-local allowlist file it reads at runtime, not by
 * install-time secrets.
 */
public final class SecurityScannerMcpInstaller {

    public static final Path DEFAULT_INSTALL_DIR =
        HomeDir.resolve().resolve(".config").resolve("opencode").resolve("mcp").resolve("security-scanner");
    private static final String JAR_NAME = "security-scanner.jar";

    private SecurityScannerMcpInstaller() {
    }

    public static McpInstallResult install() {
        return install(PackageRoot.securityScannerMcpSrc(), DEFAULT_INSTALL_DIR);
    }

    public static McpInstallResult install(Path sourceDir, Path installDir) {
        MavenRunner.checkAvailable();
        MavenRunner.packageProject(sourceDir);

        try {
            Files.createDirectories(installDir);
            Files.copy(
                sourceDir.resolve("target").resolve(JAR_NAME),
                installDir.resolve(JAR_NAME),
                StandardCopyOption.REPLACE_EXISTING
            );
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
