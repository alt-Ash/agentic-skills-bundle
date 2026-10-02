package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.config.AgentRegistrationResult;
import dev.dorrian.agenticskillscli.config.JsonConfigStore;
import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.detect.InstalledAgentDetector;
import dev.dorrian.agenticskillscli.detect.InstalledCommandDetector;
import dev.dorrian.agenticskillscli.detect.InstalledMcpServerDetector;
import dev.dorrian.agenticskillscli.detect.InstalledSkillDetector;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.install.AgentInstaller;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import dev.dorrian.agenticskillscli.install.CommandInstaller;
import dev.dorrian.agenticskillscli.install.HooksInstaller;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import dev.dorrian.agenticskillscli.mcp.local.IssueTicketsMcpInstaller;
import dev.dorrian.agenticskillscli.mcp.local.SecurityScannerMcpInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;
import dev.dorrian.agenticskillscli.registry.CommandRegistry;
import dev.dorrian.agenticskillscli.registry.McpConfigDef;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;
import dev.dorrian.agenticskillscli.ui.Ansi;
import dev.dorrian.agenticskillscli.ui.Prompter;
import dev.dorrian.agenticskillscli.ui.SummaryPrinter;
import dev.dorrian.agenticskillscli.ui.ToolResults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code bin/install.js}'s {@code runUninstall()} (source read
 * directly, lines 1852-2324, on 2026-09-30): detects what is actually
 * installed across the selected AI tools and removes only what the user
 * explicitly selects. Reached from both the top-level "Uninstall" menu
 * choice and the {@code --uninstall} CLI flag (both set the same {@code
 * mode}, in {@link dev.dorrian.agenticskillscli.App}).
 *
 * <p>Preserves two faithful-but-surprising quirks of the original rather
 * than "fixing" them:
 * <ul>
 *   <li>Agent removal always targets {@code tool.agentsGlobalPath()} — unlike
 *       install, there is no global/project target prompt for agents at
 *       uninstall time, so a project-installed agent cannot be removed via
 *       this wizard. The original has no such prompt either.</li>
 *   <li>The per-tool MCP detection list passed to {@code
 *       detectInstalledMcpServers} never includes {@code "security-scanner"}
 *       (only {@code engram}/{@code context7}/{@code figma-mcp}/{@code
 *       issue-tickets} are checked) — so {@code
 *       securityScannerMcpConfigInstalled} is always {@code false} in the
 *       original, and the security-scanner removal confirm is only ever
 *       triggered by the jar-file-exists check, never by a detected config
 *       entry. This looks like a pre-existing omission in {@code
 *       bin/install.js}, not a deliberate design choice, but it is preserved
 *       exactly here rather than silently corrected.</li>
 * </ul>
 */
public final class UninstallWizard {

    private UninstallWizard() {
    }

    public static void run(
        Prompter prompter,
        List<SkillDescriptor> availableSkills,
        List<AgentDescriptor> availableAgentFiles,
        Map<String, Boolean> detectedTools
    ) {
        System.out.println();
        System.out.println("  " + Ansi.boldRed("Uninstaller"));
        System.out.println("  " + Ansi.dim("Detects what is installed and removes only what you select."));
        System.out.println();

        // 1. Select AI tools (only detected ones shown pre-checked)
        List<String> selectedTools = promptToolSelection(prompter, detectedTools);

        // 2. Detect what is actually installed across selected tools
        System.out.println("  " + Ansi.dim("Detecting installed items…"));

        List<String> allCommandNames = collectAllCommandNames(availableSkills, availableAgentFiles);

        Set<String> installedSkillNames = InstalledSkillDetector.detect(availableSkills, selectedTools);
        Set<String> installedAgentNames = InstalledAgentDetector.detect(availableAgentFiles, selectedTools);
        Set<String> installedCommandNames = InstalledCommandDetector.detect(allCommandNames, selectedTools);

        Map<String, Set<String>> installedMcpsByTool = new LinkedHashMap<>();
        for (String toolKey : selectedTools) {
            List<String> names = new ArrayList<>();
            names.addAll(McpServerMerger.mergeSkillServers(availableSkills, toolKey).keySet());
            names.addAll(McpServerMerger.mergeAgentServers(availableAgentFiles, toolKey).keySet());
            names.add("engram");
            names.add("context7");
            names.add("figma-mcp");
            names.add("issue-tickets");
            installedMcpsByTool.put(toolKey, InstalledMcpServerDetector.detect(names, toolKey));
        }

        // 3. Select skills to remove — only show installed ones, pre-checked
        List<SkillDescriptor> selectedSkills = promptSkillsToRemove(prompter, availableSkills, installedSkillNames);

        // 4. Skills removal target (only asked if skills selected)
        String skillsInstallTarget = "global";
        Path projectPath = null;
        if (!selectedSkills.isEmpty()) {
            skillsInstallTarget = promptRemoveTarget(prompter);
            if ("project".equals(skillsInstallTarget)) {
                projectPath = ProjectPathInput.prompt(prompter);
            }
        }

        // 5. Select agents to remove — only show installed ones, pre-checked
        List<String> toolsSupportingAgents = new ArrayList<>();
        for (String toolKey : selectedTools) {
            if (AgentToolRegistry.get(toolKey).supportsAgents()) toolsSupportingAgents.add(toolKey);
        }
        List<AgentDescriptor> selectedAgentFiles = List.of();
        if (!toolsSupportingAgents.isEmpty()) {
            selectedAgentFiles = promptAgentsToRemove(prompter, availableAgentFiles, installedAgentNames);
        }

        // 6. Slash commands — only show if installed, pre-checked-by-confirming
        boolean removeCommands = false;
        List<CommandDescriptor> commandsToRemove = List.of();
        boolean anySupportsCommands = false;
        for (String toolKey : selectedTools) {
            if (AgentToolRegistry.get(toolKey).supportsCommands()) {
                anySupportsCommands = true;
                break;
            }
        }
        if (anySupportsCommands) {
            List<CommandDescriptor> skillCmds = selectedSkills.isEmpty() ? List.of() : CommandInstaller.resolveForSkills(selectedSkills);
            List<CommandDescriptor> agentCmds = selectedAgentFiles.isEmpty() ? List.of() : CommandInstaller.resolveForAgents(selectedAgentFiles);
            List<CommandDescriptor> candidates = QuickInstallFlow.dedupedCommands(skillCmds, agentCmds);
            List<CommandDescriptor> installedCandidates = new ArrayList<>();
            for (CommandDescriptor c : candidates) {
                if (installedCommandNames.contains(c.name())) installedCandidates.add(c);
            }
            commandsToRemove = installedCandidates;

            if (!commandsToRemove.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (CommandDescriptor c : commandsToRemove) names.add(Ansi.cyan("/" + c.name()));
                removeCommands = prompter.confirm(
                    "Also remove slash commands? " + Ansi.dim("(" + String.join(", ", names) + ")"), true);
            }
        }

        // 7. MCP servers — only show installed ones, pre-checked-by-confirming
        List<AgentDescriptor> selectedAgentFilesFinal = selectedAgentFiles;
        List<String> installedSkillMcpPreview = installedMcpPreview(
            selectedTools, installedMcpsByTool, toolKey -> McpServerMerger.mergeSkillServers(selectedSkills, toolKey));
        boolean removeSkillMcps = false;
        if (!installedSkillMcpPreview.isEmpty()) {
            removeSkillMcps = prompter.confirm(
                "Remove skill-linked MCP servers? " + Ansi.dim("(" + String.join(", ", installedSkillMcpPreview) + ")"), true);
        }

        List<String> installedAgentMcpPreview = installedMcpPreview(
            selectedTools, installedMcpsByTool, toolKey -> McpServerMerger.mergeAgentServers(selectedAgentFilesFinal, toolKey));
        boolean removeAgentMcps = false;
        if (!installedAgentMcpPreview.isEmpty()) {
            removeAgentMcps = prompter.confirm(
                "Remove agent-linked MCP servers? " + Ansi.dim("(" + String.join(", ", installedAgentMcpPreview) + ")"), true);
        }

        // 8. Global tools (Engram + Context7 + Figma + issue-tickets/security-scanner)
        boolean obTicketsInstalled = Files.exists(IssueTicketsMcpInstaller.DEFAULT_INSTALL_DIR.resolve("issue-tickets.jar"));
        boolean securityScannerInstalled = Files.exists(SecurityScannerMcpInstaller.DEFAULT_INSTALL_DIR.resolve("security-scanner.jar"));

        List<String> installedGlobalChoices = new ArrayList<>();
        for (String name : List.of("engram", "context7", "figma-mcp")) {
            boolean anyToolHasIt = false;
            for (String toolKey : selectedTools) {
                if (installedMcpsByTool.getOrDefault(toolKey, Set.of()).contains(name)) {
                    anyToolHasIt = true;
                    break;
                }
            }
            if (anyToolHasIt) installedGlobalChoices.add(name);
        }

        boolean obTicketsMcpConfigInstalled = false;
        boolean securityScannerMcpConfigInstalled = false;
        for (String toolKey : selectedTools) {
            Set<String> installed = installedMcpsByTool.getOrDefault(toolKey, Set.of());
            if (installed.contains("issue-tickets")) obTicketsMcpConfigInstalled = true;
            if (installed.contains("security-scanner")) securityScannerMcpConfigInstalled = true;
        }

        List<String> globalMcpsToRemove = new ArrayList<>();
        if (!installedGlobalChoices.isEmpty()) {
            Map<String, String> labels = new LinkedHashMap<>();
            for (String name : installedGlobalChoices) {
                labels.put(name, Ansi.cyan(capitalize(name)));
            }
            List<String> selectedLabels = prompter.checkbox(
                "Remove global MCP tools from config?", new ArrayList<>(labels.values()), new LinkedHashSet<>(labels.values()));
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                if (selectedLabels.contains(entry.getValue())) globalMcpsToRemove.add(entry.getKey());
            }
        }

        if (obTicketsMcpConfigInstalled || obTicketsInstalled) {
            if (prompter.confirm("Remove issue-tickets from MCP config? " + Ansi.dim("(installed files kept)"), true)) {
                globalMcpsToRemove.add("issue-tickets");
            }
        }

        if (securityScannerMcpConfigInstalled || securityScannerInstalled) {
            if (prompter.confirm("Remove security-scanner from MCP config? " + Ansi.dim("(installed files kept)"), true)) {
                globalMcpsToRemove.add("security-scanner");
            }
        }

        // Analytics hooks — registered in Claude Code's settings.json by every Claude install,
        // so they're offered here whenever Claude Code is selected and they're present.
        Path claudeSettings = selectedTools.contains("claude")
            ? McpConfigRegistry.get("claude").map(McpConfigDef::globalFile).orElse(null)
            : null;
        boolean removeHooks = claudeSettings != null && HooksInstaller.isInstalled(claudeSettings)
            && prompter.confirm("Remove the analytics hooks from Claude Code? "
                + Ansi.dim("(your usage database is kept)"), true);

        if (selectedSkills.isEmpty() && selectedAgentFiles.isEmpty() && !removeCommands
            && !removeSkillMcps && !removeAgentMcps && globalMcpsToRemove.isEmpty() && !removeHooks) {
            System.out.println();
            System.out.println("  " + Ansi.dim("Nothing selected. Uninstall cancelled."));
            System.out.println();
            return;
        }

        // 8. Confirm
        printReadyToRemove(selectedTools, selectedSkills, skillsInstallTarget, projectPath, removeCommands,
            commandsToRemove, selectedAgentFiles, removeSkillMcps, removeAgentMcps, globalMcpsToRemove, removeHooks);

        if (!prompter.confirm(Ansi.boldRed("Proceed with uninstall?"), false)) {
            System.out.println();
            System.out.println("  " + Ansi.dim("Uninstall cancelled."));
            System.out.println();
            return;
        }

        // 9. Execute removal
        Map<String, ToolResults> resultsByTool = SummaryPrinter.newResultsMap();
        Path finalProjectPath = projectPath;
        String finalSkillsInstallTarget = skillsInstallTarget;
        List<SkillDescriptor> finalSelectedSkills = selectedSkills;
        List<AgentDescriptor> finalSelectedAgentFiles = selectedAgentFiles;
        boolean finalRemoveCommands = removeCommands;
        List<CommandDescriptor> finalCommandsToRemove = commandsToRemove;
        boolean finalRemoveSkillMcps = removeSkillMcps;
        boolean finalRemoveAgentMcps = removeAgentMcps;
        List<String> finalGlobalMcpsToRemove = globalMcpsToRemove;

        for (String toolKey : selectedTools) {
            resultsByTool.put(toolKey, executeRemovalForTool(
                toolKey, finalSelectedSkills, finalSkillsInstallTarget, finalProjectPath, finalRemoveCommands,
                finalCommandsToRemove, finalSelectedAgentFiles, finalRemoveSkillMcps, finalRemoveAgentMcps,
                finalGlobalMcpsToRemove
            ));
        }

        SummaryPrinter.printUninstallSummary(resultsByTool);

        if (removeHooks) {
            try {
                int removed = HooksInstaller.uninstall(claudeSettings);
                System.out.println("  " + Ansi.green("Analytics hooks removed")
                    + Ansi.dim(" (" + removed + " hook entries from " + claudeSettings + ")"));
                System.out.println("  " + Ansi.dim("Usage data kept at " + dev.dorrian.usagestore.UsageDb.defaultPath()
                    + " — delete that file if you no longer want it."));
            } catch (RuntimeException e) {
                System.out.println("  " + Ansi.yellow("Analytics hooks: could not remove — " + e.getMessage()));
                System.out.println("  " + Ansi.dim("Remove the agentic-skills-hooks.jar entries from " + claudeSettings + " manually."));
            }
            System.out.println();
        }
    }

    // ─── Per-tool execution ─────────────────────────────────────────────────

    private static ToolResults executeRemovalForTool(
        String toolKey, List<SkillDescriptor> selectedSkills, String skillsInstallTarget, Path projectPath,
        boolean removeCommands, List<CommandDescriptor> commandsToRemove, List<AgentDescriptor> selectedAgentFiles,
        boolean removeSkillMcps, boolean removeAgentMcps, List<String> globalMcpsToRemove
    ) {
        AgentToolDef tool = AgentToolRegistry.get(toolKey);

        List<OperationResult> skillResults = new ArrayList<>();
        String skillsPathStr = null;
        if (!selectedSkills.isEmpty()) {
            Path skillsPath = InstallTargetResolver.skillsPath(tool, skillsInstallTarget, projectPath);
            skillsPathStr = skillsPath.toString();
            for (SkillDescriptor skill : selectedSkills) {
                skillResults.add(SkillInstaller.remove(skill.name(), skillsPath));
            }
        }

        List<OperationResult> commandResults = new ArrayList<>();
        String commandsPathStr = null;
        if (tool.supportsCommands() && removeCommands && !commandsToRemove.isEmpty()) {
            boolean hasAgentCommands = selectedAgentFiles.stream()
                .anyMatch(a -> !CommandRegistry.commandsForAgent(a.name()).isEmpty());
            boolean isGlobal = InstallTargetResolver.commandsInstallGlobal(hasAgentCommands, skillsInstallTarget, selectedSkills.isEmpty());
            Path commandsPath = InstallTargetResolver.commandsPath(tool, isGlobal, projectPath);
            commandsPathStr = commandsPath.toString();
            for (CommandDescriptor cmd : commandsToRemove) {
                commandResults.add(CommandInstaller.remove(cmd.name(), commandsPath));
            }
        }

        List<OperationResult> agentResults = new ArrayList<>();
        String agentsPathStr = null;
        List<AgentRegistrationResult> configRegs = new ArrayList<>();
        if (tool.supportsAgents() && !selectedAgentFiles.isEmpty()) {
            // Uninstall always targets the global agents path — unlike install, there is no
            // global/project target prompt for agent removal in the original either.
            Path agentsPath = tool.agentsGlobalPath();
            agentsPathStr = agentsPath.toString();
            for (AgentDescriptor agentDef : selectedAgentFiles) {
                agentResults.add(AgentInstaller.remove(agentDef, agentsPath, toolKey));
            }

            if (tool.agentConfigFile() != null) {
                for (AgentDescriptor agentDef : selectedAgentFiles) {
                    try {
                        JsonConfigStore.unregisterAgentFromConfig(tool, agentDef.name())
                            .ifPresent(r -> configRegs.add(new AgentRegistrationResult(
                                r.name(), r.success(), r.skipped(), false, r.configFile(), r.error())));
                    } catch (RuntimeException e) {
                        configRegs.add(AgentRegistrationResult.failed(agentDef.name(), tool.agentConfigFile().toString(), e.getMessage()));
                    }
                }
            }
        }

        List<OperationResult> mcpResults = new ArrayList<>();

        if (removeSkillMcps) {
            Map<String, Object> servers = McpServerMerger.mergeSkillServers(selectedSkills, toolKey);
            if (!servers.isEmpty()) {
                mcpResults.addAll(tryUninstallMcpServers(new ArrayList<>(servers.keySet()), toolKey));
            }
        }

        if (removeAgentMcps) {
            Map<String, Object> servers = McpServerMerger.mergeAgentServers(selectedAgentFiles, toolKey);
            if (!servers.isEmpty()) {
                mcpResults.addAll(tryUninstallMcpServers(new ArrayList<>(servers.keySet()), toolKey));
            }
        }

        if (!globalMcpsToRemove.isEmpty()) {
            mcpResults.addAll(tryUninstallMcpServers(globalMcpsToRemove, toolKey));
        }

        return new ToolResults(tool.name(), skillResults, commandResults, agentResults, configRegs,
            List.of(), mcpResults, skillsPathStr, commandsPathStr, agentsPathStr);
    }

    private static List<OperationResult> tryUninstallMcpServers(List<String> names, String toolKey) {
        try {
            return JsonConfigStore.uninstallMcpServers(names, toolKey);
        } catch (RuntimeException e) {
            List<OperationResult> failures = new ArrayList<>();
            for (String name : names) failures.add(OperationResult.failed(name, null, e.getMessage()));
            return failures;
        }
    }

    // ─── Prompts ─────────────────────────────────────────────────────────────

    private static List<String> promptToolSelection(Prompter prompter, Map<String, Boolean> detectedTools) {
        Map<String, String> labelsByKey = ToolChoiceBuilder.labelsByKey(detectedTools);
        Set<String> preChecked = ToolChoiceBuilder.preCheckedLabels(labelsByKey, detectedTools);
        while (true) {
            List<String> selected = prompter.checkbox(
                "Which AI tools do you want to uninstall from?", new ArrayList<>(labelsByKey.values()), preChecked);
            if (!selected.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (String label : selected) keys.add(ToolChoiceBuilder.keyForLabel(labelsByKey, label));
                return keys;
            }
            System.out.println("  " + Ansi.red("Select at least one AI tool."));
        }
    }

    private static List<SkillDescriptor> promptSkillsToRemove(
        Prompter prompter, List<SkillDescriptor> availableSkills, Set<String> installedSkillNames
    ) {
        List<SkillDescriptor> installedChoices = new ArrayList<>();
        for (SkillDescriptor s : availableSkills) {
            if (installedSkillNames.contains(s.name())) installedChoices.add(s);
        }
        if (installedChoices.isEmpty()) {
            System.out.println("  " + Ansi.dim("No installed skills detected — skipping."));
            return List.of();
        }

        Map<String, SkillDescriptor> labelsToSkill = new LinkedHashMap<>();
        for (SkillDescriptor s : installedChoices) {
            labelsToSkill.put(Ansi.dim("[" + s.category() + "] ") + Ansi.cyan(s.name()), s);
        }
        List<String> selectedLabels = prompter.checkbox(
            "Select skills to remove:", new ArrayList<>(labelsToSkill.keySet()), new LinkedHashSet<>(labelsToSkill.keySet()));
        List<SkillDescriptor> selected = new ArrayList<>();
        for (String label : selectedLabels) selected.add(labelsToSkill.get(label));
        return selected;
    }

    private static List<AgentDescriptor> promptAgentsToRemove(
        Prompter prompter, List<AgentDescriptor> availableAgentFiles, Set<String> installedAgentNames
    ) {
        List<AgentDescriptor> installedChoices = new ArrayList<>();
        for (AgentDescriptor a : availableAgentFiles) {
            if (installedAgentNames.contains(a.name())) installedChoices.add(a);
        }
        if (installedChoices.isEmpty()) {
            System.out.println("  " + Ansi.dim("No installed agents detected — skipping."));
            return List.of();
        }

        Map<String, AgentDescriptor> labelsToAgent = new LinkedHashMap<>();
        for (AgentDescriptor a : installedChoices) {
            labelsToAgent.put(Ansi.cyan(a.name()), a);
        }
        List<String> selectedLabels = prompter.checkbox(
            "Select agents to remove:", new ArrayList<>(labelsToAgent.keySet()), new LinkedHashSet<>(labelsToAgent.keySet()));
        List<AgentDescriptor> selected = new ArrayList<>();
        for (String label : selectedLabels) selected.add(labelsToAgent.get(label));
        return selected;
    }

    private static String promptRemoveTarget(Prompter prompter) {
        List<String> choices = List.of(
            Ansi.cyan("Global") + "  " + Ansi.dim("— AI agent's global config"),
            Ansi.magenta("Project") + " " + Ansi.dim("— specific project directory")
        );
        String chosen = prompter.list("Remove from:", choices);
        return chosen.startsWith(Ansi.cyan("Global")) ? "global" : "project";
    }

    private interface McpServersForTool {
        Map<String, Object> resolve(String toolKey);
    }

    private static List<String> installedMcpPreview(
        List<String> selectedTools, Map<String, Set<String>> installedMcpsByTool, McpServersForTool resolver
    ) {
        List<String> preview = new ArrayList<>();
        for (String toolKey : selectedTools) {
            Map<String, Object> servers = resolver.resolve(toolKey);
            Set<String> installed = installedMcpsByTool.getOrDefault(toolKey, Set.of());
            for (String name : servers.keySet()) {
                if (installed.contains(name)) {
                    preview.add(Ansi.cyan(name) + " " + Ansi.dim("(" + AgentToolRegistry.get(toolKey).name() + ")"));
                }
            }
        }
        return preview;
    }

    private static List<String> collectAllCommandNames(List<SkillDescriptor> availableSkills, List<AgentDescriptor> availableAgentFiles) {
        Set<String> names = new LinkedHashSet<>();
        for (SkillDescriptor s : availableSkills) names.addAll(CommandRegistry.commandsForSkill(s.name()));
        for (AgentDescriptor a : availableAgentFiles) names.addAll(CommandRegistry.commandsForAgent(a.name()));
        return new ArrayList<>(names);
    }

    /** Port of {@code name.charAt(0).toUpperCase() + name.slice(1)} — first character only, not title-case. */
    static String capitalize(String name) {
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ─── Summary ─────────────────────────────────────────────────────────────

    private static void printReadyToRemove(
        List<String> selectedTools, List<SkillDescriptor> selectedSkills, String skillsInstallTarget, Path projectPath,
        boolean removeCommands, List<CommandDescriptor> commandsToRemove, List<AgentDescriptor> selectedAgentFiles,
        boolean removeSkillMcps, boolean removeAgentMcps, List<String> globalMcpsToRemove, boolean removeHooks
    ) {
        System.out.println();
        System.out.println("  " + Ansi.bold("Ready to remove"));
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
        if (removeCommands && !commandsToRemove.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (CommandDescriptor c : commandsToRemove) names.add("/" + c.name());
            System.out.println("  " + Ansi.dim("Commands :") + " " + Ansi.cyan(String.join(", ", names)) + " " + Ansi.dim("[global]"));
        }
        if (!selectedAgentFiles.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (AgentDescriptor a : selectedAgentFiles) names.add("@" + a.name());
            System.out.println("  " + Ansi.dim("Agents   :") + " " + Ansi.cyan(String.join(", ", names)) + " " + Ansi.dim("[global]"));
        }
        if (removeSkillMcps || removeAgentMcps || !globalMcpsToRemove.isEmpty()) {
            System.out.println("  " + Ansi.dim("MCPs     :") + " " + Ansi.dim("[global config]"));
        }
        if (removeHooks) {
            System.out.println("  " + Ansi.dim("Hooks    :") + " " + Ansi.cyan("Claude Code analytics hooks") + " " + Ansi.dim("[global]"));
        }
        System.out.println();
    }
}
