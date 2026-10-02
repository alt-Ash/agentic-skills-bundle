package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.detect.InstalledToolDetector;
import dev.dorrian.agenticskillscli.install.HooksInstaller;
import dev.dorrian.agenticskillscli.mcp.local.IssueTicketsMcpInstaller;
import dev.dorrian.agenticskillscli.mcp.local.SecurityScannerMcpInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HookToolSupport;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything {@link UpgradePlanner} reads, injected so tests can point at temp directories
 * (the registries and {@code HomeDir} are static finals).
 *
 * @param tools          tool definitions keyed by tool key
 * @param detectedTools  keys of tools considered present on this machine
 * @param mcps           local MCP jars; refreshed only if the installed jar already exists
 */
public record UpgradeEnvironment(
    Map<String, AgentToolDef> tools,
    Set<String> detectedTools,
    Path skillsDir,
    Path agentsDir,
    Path commandsDir,
    Path templatesDir,
    Hooks hooks,
    List<McpJar> mcps
) {

    /** Hook operations, abstracted from {@link HooksInstaller}. */
    public interface Hooks {
        boolean supports(String toolKey);

        boolean isInstalled(String toolKey);

        HookInstallOptions currentOptions(String toolKey);

        void install(String toolKey, HookInstallOptions options);
    }

    /** A bundled MCP jar and where it is installed; the tool config entry is never touched. */
    public record McpJar(String name, Path bundledJar, Path installDir, String jarName, Installer installer) {

        public Path installedJar() {
            return installDir.resolve(jarName);
        }

        @FunctionalInterface
        public interface Installer {
            void install(Path bundledJar, Path installDir);
        }
    }

    /** The real installation: static registries, the live package root, and the real hook/MCP installers. */
    public static UpgradeEnvironment defaults() {
        Set<String> detected = InstalledToolDetector.detect().entrySet().stream()
            .filter(Map.Entry::getValue).map(Map.Entry::getKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Hooks hooks = new Hooks() {
            @Override public boolean supports(String toolKey) {
                return HookToolSupport.supports(toolKey);
            }

            @Override public boolean isInstalled(String toolKey) {
                return HooksInstaller.isRegisteredForTool(toolKey);
            }

            @Override public HookInstallOptions currentOptions(String toolKey) {
                return HooksInstaller.currentOptionsForTool(toolKey);
            }

            @Override public void install(String toolKey, HookInstallOptions options) {
                HooksInstaller.installForTool(toolKey, options);
            }
        };
        List<McpJar> mcps = List.of(
            new McpJar("issue-tickets", PackageRoot.issueTicketsMcpJar(),
                IssueTicketsMcpInstaller.DEFAULT_INSTALL_DIR, IssueTicketsMcpInstaller.JAR_NAME,
                IssueTicketsMcpInstaller::install),
            new McpJar("security-scanner", PackageRoot.securityScannerMcpJar(),
                SecurityScannerMcpInstaller.DEFAULT_INSTALL_DIR, SecurityScannerMcpInstaller.JAR_NAME,
                SecurityScannerMcpInstaller::install));
        return new UpgradeEnvironment(AgentToolRegistry.ALL, detected, PackageRoot.skillsDir(),
            PackageRoot.agentsDir(), PackageRoot.commandsDir(), PackageRoot.templatesDir(), hooks, mcps);
    }
}
