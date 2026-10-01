package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.config.AgentRegistrationResult;
import dev.dorrian.agenticskillscli.config.JsonConfigStore;
import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.install.AgentInstaller;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import dev.dorrian.agenticskillscli.install.CommandInstaller;
import dev.dorrian.agenticskillscli.install.HooksInstaller;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import dev.dorrian.agenticskillscli.install.TemplateInstaller;
import dev.dorrian.agenticskillscli.mcp.local.IssueTicketsMcpInstaller;
import dev.dorrian.agenticskillscli.mcp.local.SecurityScannerMcpInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;
import dev.dorrian.agenticskillscli.registry.CommandRegistry;
import dev.dorrian.agenticskillscli.registry.GlobalMcpConfigRegistry;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;
import dev.dorrian.agenticskillscli.shellprofile.AzureOrg;
import dev.dorrian.agenticskillscli.shellprofile.GithubAccount;
import dev.dorrian.agenticskillscli.shellprofile.ShellProfileEnvWriter;
import dev.dorrian.agenticskillscli.ui.Ansi;
import dev.dorrian.agenticskillscli.ui.Prompter;
import dev.dorrian.agenticskillscli.ui.SummaryPrinter;
import dev.dorrian.agenticskillscli.ui.ToolResults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code bin/install.js}'s Full/custom-install branch (source read
 * directly, lines ~2661-3372, on 2026-09-30): a long sequence of granular
 * prompts, each independently skippable, followed by a single "Ready to
 * install" review and a per-tool install loop. Unlike {@link
 * QuickInstallFlow}, the two Java MCPs (issue-tickets, security-scanner)
 * here are gated behind an explicit confirm AND restricted to {@code
 * toolKey == "opencode"} only — this is a pre-existing asymmetry vs.
 * Quick-install (which offers both unconditionally to every selected tool),
 * preserved faithfully rather than "fixed."
 */
public final class FullInstallFlow {

    private FullInstallFlow() {
    }

    public static void run(
        Prompter prompter,
        List<SkillDescriptor> availableSkills,
        List<AgentDescriptor> availableAgentFiles,
        Map<String, Boolean> detectedTools
    ) {
        List<String> selectedTools = promptToolSelection(prompter, detectedTools);

        List<SkillDescriptor> selectedSkills = promptSkillSelection(prompter, availableSkills);

        String skillsInstallTarget = "global";
        Path projectPath = null;
        if (!selectedSkills.isEmpty()) {
            skillsInstallTarget = promptInstallTarget(prompter, "Install skills:");
            if ("project".equals(skillsInstallTarget)) {
                projectPath = promptProjectPath(prompter);
            }
        }

        List<String> toolsSupportingAgents = new ArrayList<>();
        for (String toolKey : selectedTools) {
            if (AgentToolRegistry.get(toolKey).supportsAgents()) toolsSupportingAgents.add(toolKey);
        }

        List<AgentDescriptor> selectedAgentFiles = new ArrayList<>();
        String agentInstallTarget = "global";
        if (!toolsSupportingAgents.isEmpty() && !availableAgentFiles.isEmpty()) {
            selectedAgentFiles = promptAgentSelection(prompter, availableAgentFiles);
            if (!selectedAgentFiles.isEmpty()) {
                boolean defaultProject = "project".equals(skillsInstallTarget);
                agentInstallTarget = promptInstallTarget(prompter, "Install agents:", defaultProject);
                if ("project".equals(agentInstallTarget) && projectPath == null) {
                    projectPath = promptProjectPath(prompter);
                }
            }
        }

        GlobalToolsSelection globalTools = promptGlobalTools(prompter);

        boolean installObTickets = false;
        if (selectedTools.contains("opencode")) {
            installObTickets = promptJavaMcpOptIn(
                prompter,
                Files.exists(IssueTicketsMcpInstaller.DEFAULT_INSTALL_DIR.resolve("issue-tickets.jar")),
                "Install issue-tickets MCP?",
                "— ticket/issue management for Azure DevOps & GitHub"
            );
            if (installObTickets) {
                System.out.println();
                System.out.println("  " + Ansi.dim("issue-tickets reads credentials from env vars — only providers with env vars set will be active."));
                System.out.println();
            }
        }

        List<AzureOrg> azureOrgs = List.of();
        List<GithubAccount> githubAccounts = List.of();
        if (installObTickets) {
            azureOrgs = CredentialPrompts.collectAzureOrgs(prompter);
            githubAccounts = CredentialPrompts.collectGithubAccounts(prompter);
            System.out.println();
        }

        boolean installSecurityScanner = false;
        if (selectedTools.contains("opencode")) {
            installSecurityScanner = promptJavaMcpOptIn(
                prompter,
                Files.exists(SecurityScannerMcpInstaller.DEFAULT_INSTALL_DIR.resolve("security-scanner.jar")),
                "Install security-scanner MCP?",
                "— active URL vulnerability scanner, gated by a per-project allowlist (never targets production)"
            );
            if (installSecurityScanner) {
                System.out.println();
                System.out.println("  " + Ansi.dim("security-scanner refuses to run against any host not listed in that project's"));
                System.out.println("  " + Ansi.dim(".security-scanner/allowlist.json — see mcp/security-scanner/README.md for the format."));
                System.out.println();
            }
        }

        if (selectedSkills.isEmpty() && selectedAgentFiles.isEmpty() && globalTools.selectedGlobalTools().isEmpty()
            && !installObTickets && !installSecurityScanner) {
            System.out.println();
            System.out.println("  " + Ansi.dim("Nothing selected. Installation cancelled."));
            System.out.println();
            return;
        }

        boolean anySupportsCommands = selectedTools.stream().anyMatch(t -> AgentToolRegistry.get(t).supportsCommands());
        boolean installCommands = false;
        List<CommandDescriptor> availableCommands = List.of();
        if (anySupportsCommands) {
            List<CommandDescriptor> skillCmds = selectedSkills.isEmpty() ? List.of() : CommandInstaller.resolveForSkills(selectedSkills);
            List<CommandDescriptor> agentCmds = selectedAgentFiles.isEmpty() ? List.of() : CommandInstaller.resolveForAgents(selectedAgentFiles);
            availableCommands = QuickInstallFlow.dedupedCommands(skillCmds, agentCmds);
            if (!availableCommands.isEmpty()) {
                String names = joinPrefixed(availableCommands, "/");
                installCommands = prompter.confirm("Also install slash commands? (" + names + ")", true);
            }
        }

        boolean installSkillMcps = false;
        List<String> skillMcpPreview = mcpPreview(selectedTools, toolKey -> McpServerMerger.mergeSkillServers(selectedSkills, toolKey));
        if (!skillMcpPreview.isEmpty()) {
            installSkillMcps = prompter.confirm(
                "Install recommended MCP servers for selected skills? (" + String.join(", ", skillMcpPreview) + ")", true);
        }

        boolean installAgentMcps = false;
        List<AgentDescriptor> selectedAgentFilesForPreview = selectedAgentFiles;
        List<String> agentMcpPreview = mcpPreview(selectedTools, toolKey -> McpServerMerger.mergeAgentServers(selectedAgentFilesForPreview, toolKey));
        if (!agentMcpPreview.isEmpty()) {
            installAgentMcps = prompter.confirm(
                "Install required MCP servers for selected agents? (" + String.join(", ", agentMcpPreview) + ")", true);
        }

        printReadySummary(selectedTools, selectedSkills, skillsInstallTarget, projectPath, installCommands, availableCommands,
            selectedAgentFiles, agentInstallTarget, installSkillMcps, installAgentMcps, installObTickets, installSecurityScanner,
            globalTools.selectedGlobalTools());

        if (!prompter.confirm("Proceed with installation?", true)) {
            System.out.println();
            System.out.println("  " + Ansi.dim("Installation cancelled."));
            System.out.println();
            return;
        }

        Map<String, ToolResults> resultsByTool = SummaryPrinter.newResultsMap();
        Path finalProjectPath = projectPath;

        for (String toolKey : selectedTools) {
            AgentToolDef tool = AgentToolRegistry.get(toolKey);
            resultsByTool.put(toolKey, executeInstallForTool(
                tool, toolKey, selectedSkills, skillsInstallTarget, finalProjectPath, installCommands, availableCommands,
                selectedAgentFiles, agentInstallTarget, installSkillMcps, installAgentMcps, globalTools,
                installObTickets, azureOrgs, githubAccounts, installSecurityScanner
            ));
        }

        SummaryPrinter.printInstallSummary(resultsByTool);

        if (globalTools.selectedGlobalTools().contains("figma") && globalTools.figmaAccessToken() != null) {
            try {
                var result = ShellProfileEnvWriter.writeFigmaEnvVar(globalTools.figmaAccessToken());
                if (result.skipped()) {
                    System.out.println("  " + Ansi.dim("Figma MCP: FIGMA_ACCESS_TOKEN already present in shell profile — skipped."));
                } else {
                    System.out.println("  " + Ansi.green("Figma MCP: access token written to " + result.profileFile()));
                    System.out.println("  " + Ansi.dim("Run `source " + result.profileFile() + "` or open a new terminal for the var to take effect."));
                }
            } catch (RuntimeException e) {
                System.out.println("  " + Ansi.yellow("Figma MCP: could not write to shell profile: " + e.getMessage()));
                System.out.println("  " + Ansi.dim("Add this line manually:"));
                System.out.println("    export FIGMA_ACCESS_TOKEN=\"<your-token>\"");
            }
            System.out.println();
        }
    }

    // ─── Per-tool execution ─────────────────────────────────────────────────

    private static ToolResults executeInstallForTool(
        AgentToolDef tool, String toolKey, List<SkillDescriptor> selectedSkills, String skillsInstallTarget, Path projectPath,
        boolean installCommands, List<CommandDescriptor> availableCommands, List<AgentDescriptor> selectedAgentFiles,
        String agentInstallTarget, boolean installSkillMcps, boolean installAgentMcps, GlobalToolsSelection globalTools,
        boolean installObTickets, List<AzureOrg> azureOrgs, List<GithubAccount> githubAccounts, boolean installSecurityScanner
    ) {
        List<OperationResult> skillResults = List.of();
        String skillsPathStr = null;
        List<OperationResult> commandResults = List.of();
        String commandsPathStr = null;
        List<OperationResult> agentResults = List.of();
        String agentsPathStr = null;
        List<AgentRegistrationResult> configRegs = new ArrayList<>();
        List<OperationResult> templateResults = List.of();
        List<OperationResult> mcpResults = new ArrayList<>();

        if (!selectedSkills.isEmpty()) {
            Path skillsPath = InstallTargetResolver.skillsPath(tool, skillsInstallTarget, projectPath);
            skillsPathStr = skillsPath.toString();
            skillResults = SkillInstaller.install(selectedSkills, skillsPath);
        }

        if (tool.supportsCommands() && installCommands && !availableCommands.isEmpty()) {
            boolean hasAgentCommands = selectedAgentFiles.stream()
                .anyMatch(a -> !CommandRegistry.commandsForAgent(a.name()).isEmpty());
            boolean installGlobal = InstallTargetResolver.commandsInstallGlobal(hasAgentCommands, skillsInstallTarget, selectedSkills.isEmpty());
            Path commandsPath = InstallTargetResolver.commandsPath(tool, installGlobal, projectPath);
            commandsPathStr = commandsPath.toString();
            commandResults = CommandInstaller.install(availableCommands, commandsPath);
        }

        if (tool.supportsAgents() && !selectedAgentFiles.isEmpty()) {
            Path agentsPath = InstallTargetResolver.agentsPath(tool, agentInstallTarget, projectPath);
            agentsPathStr = agentsPath.toString();
            agentResults = AgentInstaller.install(selectedAgentFiles, agentsPath, toolKey);

            if (tool.agentConfigFile() != null) {
                for (AgentDescriptor agentDef : selectedAgentFiles) {
                    try {
                        JsonConfigStore.registerAgentInConfig(tool, agentDef.name(), agentDef.frontmatter())
                            .ifPresent(configRegs::add);
                    } catch (RuntimeException e) {
                        configRegs.add(AgentRegistrationResult.failed(agentDef.name(), tool.agentConfigFile().toString(), e.getMessage()));
                    }
                }
            }

            if (tool.agentsGlobalPath() != null) {
                templateResults = TemplateInstaller.install(tool.agentsGlobalPath());
            }
        }

        if (installSkillMcps) {
            Map<String, Object> skillServers = McpServerMerger.mergeSkillServers(selectedSkills, toolKey);
            if (!skillServers.isEmpty()) {
                mcpResults.addAll(tryInstallMcpServers(skillServers, toolKey));
            }
        }

        if (installAgentMcps) {
            Map<String, Object> agentServers = McpServerMerger.mergeAgentServers(selectedAgentFiles, toolKey);
            if (!agentServers.isEmpty()) {
                mcpResults.addAll(tryInstallMcpServers(agentServers, toolKey));
            }
        }

        if (!globalTools.selectedGlobalTools().isEmpty()) {
            Map<String, Object> globalServers = new LinkedHashMap<>();
            if (globalTools.selectedGlobalTools().contains("engram")) {
                globalServers.put("engram", GlobalMcpConfigRegistry.engram(toolKey));
            }
            if (globalTools.selectedGlobalTools().contains("context7")) {
                globalServers.put("context7", GlobalMcpConfigRegistry.context7(toolKey, globalTools.context7ApiKey()));
            }
            if (globalTools.selectedGlobalTools().contains("figma")) {
                globalServers.put("figma-mcp", GlobalMcpConfigRegistry.figma(toolKey));
            }
            if (!globalServers.isEmpty()) {
                mcpResults.addAll(tryInstallMcpServers(globalServers, toolKey));
            }
        }

        if ("opencode".equals(toolKey) && installObTickets) {
            try {
                IssueTicketsMcpInstaller.install();
                String azureB64 = ShellProfileEnvWriter.encodeAzureAccountsB64(azureOrgs).orElse(null);
                String githubB64 = ShellProfileEnvWriter.encodeGithubAccountsB64(githubAccounts).orElse(null);
                Map<String, Object> cfg = IssueTicketsMcpInstaller.config(toolKey, azureB64, githubB64);
                mcpResults.addAll(JsonConfigStore.installMcpServers(Map.of("issue-tickets", cfg), toolKey));
                if (!azureOrgs.isEmpty() || !githubAccounts.isEmpty()) {
                    var result = ShellProfileEnvWriter.writeObTicketsEnvVars(azureOrgs, githubAccounts);
                    System.out.println("  " + Ansi.dim("Azure/GitHub credentials written to " + result.profileFile()));
                }
            } catch (RuntimeException e) {
                mcpResults.add(OperationResult.failed("issue-tickets", null, e.getMessage()));
            }
        }

        if ("opencode".equals(toolKey) && installSecurityScanner) {
            try {
                SecurityScannerMcpInstaller.install();
                Map<String, Object> cfg = SecurityScannerMcpInstaller.config(toolKey);
                mcpResults.addAll(JsonConfigStore.installMcpServers(Map.of("security-scanner", cfg), toolKey));
            } catch (RuntimeException e) {
                mcpResults.add(OperationResult.failed("security-scanner", null, e.getMessage()));
            }
        }

        if ("claude".equals(toolKey)) {
            McpConfigRegistry.get("claude").ifPresent(cfg -> {
                try {
                    HooksInstaller.installAndRegister(cfg.globalFile());
                } catch (RuntimeException e) {
                    System.out.println("  " + Ansi.yellow("Analytics hooks not registered: " + e.getMessage()));
                }
            });
        }

        return new ToolResults(tool.name(), skillResults, commandResults, agentResults, configRegs, templateResults,
            mcpResults, skillsPathStr, commandsPathStr, agentsPathStr);
    }

    private static List<OperationResult> tryInstallMcpServers(Map<String, Object> servers, String toolKey) {
        try {
            return JsonConfigStore.installMcpServers(servers, toolKey);
        } catch (RuntimeException e) {
            List<OperationResult> failures = new ArrayList<>();
            for (String name : servers.keySet()) {
                failures.add(OperationResult.failed(name, null, e.getMessage()));
            }
            return failures;
        }
    }

    // ─── Prompts ─────────────────────────────────────────────────────────────

    private static List<String> promptToolSelection(Prompter prompter, Map<String, Boolean> detectedTools) {
        Map<String, String> labelsByKey = ToolChoiceBuilder.labelsByKey(detectedTools);
        Set<String> preChecked = ToolChoiceBuilder.preCheckedLabels(labelsByKey, detectedTools);
        while (true) {
            List<String> selected = prompter.checkbox("Which AI tools are you installing for?", new ArrayList<>(labelsByKey.values()), preChecked);
            if (!selected.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (String label : selected) keys.add(ToolChoiceBuilder.keyForLabel(labelsByKey, label));
                return keys;
            }
            System.out.println("  " + Ansi.red("Select at least one AI tool."));
        }
    }

    private static List<SkillDescriptor> promptSkillSelection(Prompter prompter, List<SkillDescriptor> availableSkills) {
        if (availableSkills.isEmpty()) {
            return List.of();
        }
        Map<String, SkillDescriptor> labelsToSkill = new LinkedHashMap<>();
        for (SkillDescriptor s : availableSkills) {
            labelsToSkill.put(Ansi.dim("[" + s.category() + "] ") + Ansi.cyan(s.name()), s);
        }
        List<String> selectedLabels = prompter.checkbox("Select the skills to install:", new ArrayList<>(labelsToSkill.keySet()), Set.of());
        List<SkillDescriptor> selected = new ArrayList<>();
        for (String label : selectedLabels) selected.add(labelsToSkill.get(label));
        return selected;
    }

    private static List<AgentDescriptor> promptAgentSelection(Prompter prompter, List<AgentDescriptor> availableAgentFiles) {
        Map<String, AgentDescriptor> labelsToAgent = new LinkedHashMap<>();
        for (AgentDescriptor a : availableAgentFiles) {
            labelsToAgent.put(Ansi.cyan(a.name()), a);
        }
        List<String> selectedLabels = prompter.checkbox("Select the agents to install:", new ArrayList<>(labelsToAgent.keySet()), Set.of());
        List<AgentDescriptor> selected = new ArrayList<>();
        for (String label : selectedLabels) selected.add(labelsToAgent.get(label));
        return selected;
    }

    private static String promptInstallTarget(Prompter prompter, String question) {
        return promptInstallTarget(prompter, question, false);
    }

    private static String promptInstallTarget(Prompter prompter, String question, boolean defaultProject) {
        List<String> choices = List.of(
            Ansi.cyan("Global") + "  " + Ansi.dim("— AI agent's global config"),
            Ansi.magenta("Project") + " " + Ansi.dim("— specific project directory")
        );
        // Prompter.list() has no notion of a "default" selection (no arrow-key pre-highlight in a
        // numbered-menu reader) — the original's `default:` only mattered for keyboard pre-selection,
        // never changed the outcome of an explicit choice, so it's dropped here without behavior loss.
        String chosen = prompter.list(question, choices);
        return chosen.startsWith(Ansi.cyan("Global")) ? "global" : "project";
    }

    private static Path promptProjectPath(Prompter prompter) {
        while (true) {
            String input = prompter.input("Project path:", System.getProperty("user.dir"));
            Path resolved = Paths.get(input).toAbsolutePath().normalize();
            if (!Files.exists(resolved)) {
                System.out.println("  " + Ansi.red("Path does not exist: " + resolved));
                continue;
            }
            if (!Files.isDirectory(resolved)) {
                System.out.println("  " + Ansi.red("Path must be a directory."));
                continue;
            }
            return resolved;
        }
    }

    record GlobalToolsSelection(List<String> selectedGlobalTools, String context7ApiKey, String figmaAccessToken) {
    }

    private static GlobalToolsSelection promptGlobalTools(Prompter prompter) {
        if (!prompter.confirm("Install global MCP tools (Engram, Context7, Figma)?", true)) {
            return new GlobalToolsSelection(List.of(), null, null);
        }

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("engram", Ansi.cyan("Engram") + "  " + Ansi.dim("— persistent memory for AI agents (binary must be installed)"));
        labels.put("context7", Ansi.cyan("Context7") + " " + Ansi.dim("— up-to-date library docs via MCP"));
        labels.put("figma", Ansi.cyan("Figma") + "    " + Ansi.dim("— Figma design tool integration via MCP (requires FIGMA_ACCESS_TOKEN)"));

        List<String> selectedLabels = prompter.checkbox("Select global tools to install:", new ArrayList<>(labels.values()), Set.of());
        List<String> selectedKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (selectedLabels.contains(entry.getValue())) selectedKeys.add(entry.getKey());
        }

        String context7ApiKey = null;
        if (selectedKeys.contains("context7")) {
            String apiKey = prompter.input("Context7 API key (optional — leave blank for free tier; get one at context7.com/dashboard):", "").trim();
            context7ApiKey = apiKey.isEmpty() ? null : apiKey;
        }

        String figmaAccessToken = null;
        if (selectedKeys.contains("figma")) {
            System.out.println();
            System.out.println("  " + Ansi.dim("Get a Figma token: figma.com → Settings → Security → Personal access tokens"));
            System.out.println();
            String token = prompter.password("Figma access token (blank to skip — set FIGMA_ACCESS_TOKEN manually):").trim();
            figmaAccessToken = token.isEmpty() ? null : token;
        }

        return new GlobalToolsSelection(selectedKeys, context7ApiKey, figmaAccessToken);
    }

    private static boolean promptJavaMcpOptIn(Prompter prompter, boolean alreadyInstalled, String question, String freshDescription) {
        String suffix = alreadyInstalled ? " (already installed — will reinstall)" : " " + freshDescription;
        return prompter.confirm(question + Ansi.dim(suffix), false);
    }

    private interface McpServersForTool {
        Map<String, Object> resolve(String toolKey);
    }

    private static List<String> mcpPreview(List<String> selectedTools, McpServersForTool resolver) {
        List<String> preview = new ArrayList<>();
        for (String toolKey : selectedTools) {
            Map<String, Object> servers = resolver.resolve(toolKey);
            for (String name : servers.keySet()) {
                preview.add(Ansi.cyan(name) + " " + Ansi.dim("(" + AgentToolRegistry.get(toolKey).name() + ")"));
            }
        }
        return preview;
    }

    private static String joinPrefixed(List<CommandDescriptor> commands, String prefix) {
        List<String> names = new ArrayList<>();
        for (CommandDescriptor c : commands) names.add(Ansi.cyan(prefix + c.name()));
        return String.join(", ", names);
    }

    // ─── Summary ─────────────────────────────────────────────────────────────

    private static void printReadySummary(
        List<String> selectedTools, List<SkillDescriptor> selectedSkills, String skillsInstallTarget, Path projectPath,
        boolean installCommands, List<CommandDescriptor> availableCommands, List<AgentDescriptor> selectedAgentFiles,
        String agentInstallTarget, boolean installSkillMcps, boolean installAgentMcps, boolean installObTickets,
        boolean installSecurityScanner, List<String> selectedGlobalTools
    ) {
        System.out.println();
        System.out.println("  " + Ansi.bold("Ready to install"));
        System.out.println("  " + Ansi.dim("─".repeat(40)));

        List<String> toolNames = new ArrayList<>();
        for (String t : selectedTools) toolNames.add(AgentToolRegistry.get(t).name());
        System.out.println("  " + Ansi.dim("Tools    :") + " " + Ansi.yellow(String.join(", ", toolNames)));

        if (!selectedSkills.isEmpty()) {
            String label = "project".equals(skillsInstallTarget) ? "project (" + projectPath + ")" : "global";
            List<String> names = new ArrayList<>();
            for (SkillDescriptor s : selectedSkills) names.add(s.name());
            System.out.println("  " + Ansi.dim("Skills   :") + " " + Ansi.cyan(String.join(", ", names)) + " " + Ansi.dim("[" + label + "]"));
        }
        if (installCommands && !availableCommands.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (CommandDescriptor c : availableCommands) names.add("/" + c.name());
            System.out.println("  " + Ansi.dim("Commands :") + " " + Ansi.cyan(String.join(", ", names)) + " " + Ansi.dim("[OpenCode global]"));
        }
        if (!selectedAgentFiles.isEmpty()) {
            String label = "project".equals(agentInstallTarget) ? "project (" + projectPath + ")" : "global";
            List<String> names = new ArrayList<>();
            for (AgentDescriptor a : selectedAgentFiles) names.add("@" + a.name());
            System.out.println("  " + Ansi.dim("Agents   :") + " " + Ansi.cyan(String.join(", ", names)) + " " + Ansi.dim("[" + label + "]"));
        }
        if (installSkillMcps || installAgentMcps) {
            System.out.println("  " + Ansi.dim("MCPs     :") + " " + Ansi.dim("[global]"));
        }
        if (installObTickets) {
            System.out.println("  " + Ansi.dim("Tickets  :") + " " + Ansi.cyan("issue-tickets") + " " + Ansi.dim("[install to ~/.config/opencode/mcp/]"));
        }
        if (installSecurityScanner) {
            System.out.println("  " + Ansi.dim("Scanner  :") + " " + Ansi.cyan("security-scanner") + " " + Ansi.dim("[install to ~/.config/opencode/mcp/]"));
        }
        if (!selectedGlobalTools.isEmpty()) {
            System.out.println("  " + Ansi.dim("Tools    :") + " " + Ansi.cyan(String.join(", ", selectedGlobalTools)) + " " + Ansi.dim("[global MCP]"));
        }
        System.out.println();
    }
}
