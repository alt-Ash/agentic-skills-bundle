package dev.dorrian.agenticskillscli.mcp.local;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.registry.McpServerConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.dorrian.agenticskillscli.registry.McpServerConfig.list;

/**
 * Installs the issue-tickets MCP server (Java/Spring Boot, {@code mcp/issue-tickets}) by copying
 * the prebuilt, self-contained fat jar shipped in the installer's bundle to
 * {@code ~/.config/opencode/mcp/issue-tickets/}, and builds each tool's config entry for it.
 * (Earlier versions built it from source with {@code mvn} at install time; the jar is now built
 * once, by the reactor, and bundled into {@code agentic-skills.jar}.)
 */
public final class IssueTicketsMcpInstaller {

    public static final Path DEFAULT_INSTALL_DIR =
        HomeDir.resolve().resolve(".config").resolve("opencode").resolve("mcp").resolve("issue-tickets");
    private static final String JAR_NAME = "issue-tickets.jar";

    private IssueTicketsMcpInstaller() {
    }

    public static McpInstallResult install() {
        return install(PackageRoot.issueTicketsMcpJar(), DEFAULT_INSTALL_DIR);
    }

    /**
     * Copies the prebuilt fat jar shipped in the bundle into {@code installDir}, overwriting
     * any older copy. No build step and no child process: end users need only a JRE.
     */
    public static McpInstallResult install(Path bundledJar, Path installDir) {
        if (!Files.isRegularFile(bundledJar)) {
            throw new IllegalStateException("Bundled issue-tickets MCP jar not found: " + bundledJar);
        }
        try {
            Files.createDirectories(installDir);
            Files.copy(bundledJar, installDir.resolve(JAR_NAME), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new McpInstallResult(true, installDir);
    }

    /**
     * Builds the config entry for a given tool. opencode gets {@code
     * {env:VAR}} placeholders; all other hosts get resolved values. The
     * server is a self-contained Spring Boot fat jar — launched via
     * {@code java -jar}, no separate runtime dependency install needed
     * post-build.
     */
    public static Map<String, Object> config(String toolKey, String azureAccountsB64, String githubAccountsB64) {
        return config(toolKey, DEFAULT_INSTALL_DIR.resolve(JAR_NAME), azureAccountsB64, githubAccountsB64);
    }

    public static Map<String, Object> config(String toolKey, Path jarPath, String azureAccountsB64, String githubAccountsB64) {
        String jar = jarPath.toString();

        if ("opencode".equals(toolKey)) {
            Map<String, Object> environment = new LinkedHashMap<>();
            environment.put("AZURE_DEVOPS_ACCOUNTS_B64", "{env:AZURE_DEVOPS_ACCOUNTS_B64}");
            environment.put("GITHUB_ACCOUNTS_B64", "{env:GITHUB_ACCOUNTS_B64}");
            return McpServerConfig.of("type", "local", "command", list("java", "-jar", jar), "environment", environment);
        }

        Map<String, Object> env = new LinkedHashMap<>();
        if (azureAccountsB64 != null && !azureAccountsB64.isEmpty()) env.put("AZURE_DEVOPS_ACCOUNTS_B64", azureAccountsB64);
        if (githubAccountsB64 != null && !githubAccountsB64.isEmpty()) env.put("GITHUB_ACCOUNTS_B64", githubAccountsB64);

        if ("zed".equals(toolKey)) {
            return McpServerConfig.of("source", "custom", "command", "java", "args", list("-jar", jar), "env", env);
        }
        return McpServerConfig.of("type", "stdio", "command", "java", "args", list("-jar", jar), "env", env);
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
