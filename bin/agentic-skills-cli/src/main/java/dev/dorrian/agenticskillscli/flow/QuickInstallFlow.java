package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.config.JsonConfigStore;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.install.AgentInstaller;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import dev.dorrian.agenticskillscli.install.CommandInstaller;
import dev.dorrian.agenticskillscli.install.HooksInstaller;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HookToolSupport;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import dev.dorrian.agenticskillscli.mcp.local.IssueTicketsMcpInstaller;
import dev.dorrian.agenticskillscli.mcp.local.SecurityScannerMcpInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;
import dev.dorrian.agenticskillscli.registry.GlobalMcpConfigRegistry;
import dev.dorrian.agenticskillscli.registry.McpConfigRegistry;
import dev.dorrian.agenticskillscli.shellprofile.AzureOrg;
import dev.dorrian.agenticskillscli.shellprofile.GithubAccount;
import dev.dorrian.agenticskillscli.shellprofile.ShellProfileEnvWriter;
import dev.dorrian.agenticskillscli.ui.Ansi;
import dev.dorrian.agenticskillscli.ui.Prompter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code bin/install.js}'s Quick-install branch (source read
 * directly, lines ~2388-2659, on 2026-09-30): a single tool checkbox,
 * optional Azure/GitHub credential collection, then an unconditional
 * install of skills/commands/agents/global MCPs for every selected tool,
 * followed by an unconditional install (copy of the bundled prebuilt jars) of BOTH the issue-tickets and
 * security-scanner Java MCPs for every selected tool — no per-tool opt-in.
 * This is the documented asymmetry vs. {@link FullInstallFlow}, which gates
 * the two Java MCPs behind an explicit confirm and restricts registration
 * to {@code opencode} only; preserved here faithfully, not "fixed."
 *
 * <p>Unlike {@link FullInstallFlow}, the original Quick-install branch never
 * calls {@code printSummary} — it only prints a single completion line, so
 * no {@code ToolResults}/{@code SummaryPrinter} bookkeeping is built here.
 */
public final class QuickInstallFlow {

    private QuickInstallFlow() {
    }

    public static void run(
        Prompter prompter,
        List<SkillDescriptor> availableSkills,
        List<AgentDescriptor> availableAgentFiles,
        Map<String, Boolean> detectedTools
    ) {
        Map<String, String> labelsByKey = ToolChoiceBuilder.labelsByKey(detectedTools);
        Set<String> preChecked = ToolChoiceBuilder.preCheckedLabels(labelsByKey, detectedTools);

        List<String> selectedLabels;
        while (true) {
            selectedLabels = prompter.checkbox("Which AI tools are you installing for?", new ArrayList<>(labelsByKey.values()), preChecked);
            if (!selectedLabels.isEmpty()) break;
            System.out.println("  " + Ansi.red("Select at least one AI tool."));
        }
        List<String> quickTools = new ArrayList<>();
        for (String label : selectedLabels) {
            quickTools.add(ToolChoiceBuilder.keyForLabel(labelsByKey, label));
        }

        HookInstallOptions hookOptions = HookOptionsPrompt.ask(prompter, quickTools);

        List<AzureOrg> azureOrgs = CredentialPrompts.collectAzureOrgs(prompter);
        List<GithubAccount> githubAccounts = CredentialPrompts.collectGithubAccounts(prompter);

        List<CommandDescriptor> quickCommands = dedupedCommands(
            CommandInstaller.resolveForSkills(availableSkills),
            CommandInstaller.resolveForAgents(availableAgentFiles)
        );

        printQuickSummary(quickTools, availableSkills, quickCommands, availableAgentFiles, azureOrgs, githubAccounts);

        if (!prompter.confirm("Proceed with installation?", true)) {
            System.out.println(Ansi.dim("\n  Installation cancelled.\n"));
            return;
        }

        for (String toolKey : quickTools) {
            AgentToolDef tool = AgentToolRegistry.get(toolKey);
            Path skillsPath = tool.globalPath();

            SkillInstaller.install(availableSkills, skillsPath);

            if (tool.supportsCommands() && !quickCommands.isEmpty()) {
                CommandInstaller.install(quickCommands, tool.commandsGlobalPath());
            }

            if (tool.supportsAgents() && !availableAgentFiles.isEmpty()) {
                AgentInstaller.install(availableAgentFiles, tool.agentsGlobalPath(), toolKey);
                for (AgentDescriptor agentDef : availableAgentFiles) {
                    JsonConfigStore.registerAgentInConfig(tool, agentDef.name(), agentDef.frontmatter());
                }
            }

            Map<String, Object> globalServers = new LinkedHashMap<>();
            globalServers.put("engram", GlobalMcpConfigRegistry.engram(toolKey));
            globalServers.put("context7", GlobalMcpConfigRegistry.context7(toolKey, null));
            globalServers.put("figma-mcp", GlobalMcpConfigRegistry.figma(toolKey));
            JsonConfigStore.migrateLegacyNpxServers(globalServers, toolKey);
            JsonConfigStore.installMcpServers(globalServers, toolKey);

            Map<String, Object> linkedMcps = new LinkedHashMap<>();
            linkedMcps.putAll(McpServerMerger.mergeSkillServers(availableSkills, toolKey));
            linkedMcps.putAll(McpServerMerger.mergeAgentServers(availableAgentFiles, toolKey));
            if (!linkedMcps.isEmpty()) {
                JsonConfigStore.installMcpServers(linkedMcps, toolKey);
            }

            if (HookToolSupport.supports(toolKey)) {
                try {
                    HooksInstaller.installForTool(toolKey, hookOptions);
                    System.out.println(Ansi.dim("  Hooks installed and registered for " + tool.name() + "."));
                } catch (RuntimeException e) {
                    System.out.println(Ansi.yellow("  Hooks not registered for " + tool.name() + ": " + e.getMessage()));
                }
            }
        }

        try {
            IssueTicketsMcpInstaller.install();
            System.out.println(Ansi.dim("  issue-tickets installed."));
            String azureB64 = ShellProfileEnvWriter.encodeAzureAccountsB64(azureOrgs).orElse(null);
            String githubB64 = ShellProfileEnvWriter.encodeGithubAccountsB64(githubAccounts).orElse(null);
            for (String toolKey : quickTools) {
                Map<String, Object> cfg = IssueTicketsMcpInstaller.config(toolKey, azureB64, githubB64);
                JsonConfigStore.installMcpServers(Map.of("issue-tickets", cfg), toolKey);
            }
            if (!azureOrgs.isEmpty() || !githubAccounts.isEmpty()) {
                var result = ShellProfileEnvWriter.writeObTicketsEnvVars(azureOrgs, githubAccounts);
                System.out.println(Ansi.dim("  Azure/GitHub credentials written to " + result.profileFile()));
            }
        } catch (RuntimeException e) {
            System.out.println(Ansi.red("  issue-tickets install failed: " + e.getMessage()));
        }

        try {
            SecurityScannerMcpInstaller.install();
            System.out.println(Ansi.dim("  security-scanner installed."));
            for (String toolKey : quickTools) {
                Map<String, Object> cfg = SecurityScannerMcpInstaller.config(toolKey);
                JsonConfigStore.installMcpServers(Map.of("security-scanner", cfg), toolKey);
            }
        } catch (RuntimeException e) {
            System.out.println(Ansi.red("  security-scanner install failed: " + e.getMessage()));
        }

        System.out.println();
        System.out.println(Ansi.boldGreen("  ✔ Quick install complete!"));
        System.out.println();
    }

    /** Skills' commands take priority over agents' on a name collision, matching the original's filter order. */
    static List<CommandDescriptor> dedupedCommands(List<CommandDescriptor> skillCmds, List<CommandDescriptor> agentCmds) {
        Set<String> seen = new LinkedHashSet<>();
        List<CommandDescriptor> result = new ArrayList<>();
        for (CommandDescriptor c : skillCmds) {
            if (seen.add(c.name())) result.add(c);
        }
        for (CommandDescriptor c : agentCmds) {
            if (seen.add(c.name())) result.add(c);
        }
        return result;
    }

    private static void printQuickSummary(
        List<String> quickTools, List<SkillDescriptor> availableSkills, List<CommandDescriptor> quickCommands,
        List<AgentDescriptor> availableAgentFiles, List<AzureOrg> azureOrgs, List<GithubAccount> githubAccounts
    ) {
        System.out.println();
        System.out.println("  " + Ansi.bold("Ready to install"));
        System.out.println("  " + Ansi.dim("─".repeat(40)));
        System.out.println("  " + Ansi.dim("Tools    :") + " " + Ansi.yellow(joinToolNames(quickTools)));
        System.out.println("  " + Ansi.dim("Skills   :") + " " + Ansi.cyan(joinSkillNames(availableSkills)) + " " + Ansi.dim("[global]"));
        if (!quickCommands.isEmpty()) {
            System.out.println("  " + Ansi.dim("Commands :") + " " + Ansi.cyan(joinCommandNames(quickCommands)) + " " + Ansi.dim("[global]"));
        }
        System.out.println("  " + Ansi.dim("Agents   :") + " " + Ansi.cyan(joinAgentNames(availableAgentFiles)) + " " + Ansi.dim("[global]"));
        System.out.println("  " + Ansi.dim("MCPs     :") + " " + Ansi.cyan("engram, context7, figma-mcp, issue-tickets, security-scanner") + " " + Ansi.dim("[global]"));
        if (azureOrgs.isEmpty()) {
            System.out.println("            " + Ansi.yellow("⚠ Azure credentials not provided — set AZURE_DEVOPS_ACCOUNTS in your shell profile manually."));
        } else {
            System.out.println("  " + Ansi.dim("Azure    :") + " " + Ansi.cyan(joinOrgNames(azureOrgs)) + " " + Ansi.dim("[configured]"));
        }
        if (githubAccounts.isEmpty()) {
            System.out.println("            " + Ansi.yellow("⚠ GitHub token not provided — set GITHUB_ACCOUNTS in your shell profile manually."));
        } else {
            System.out.println("  " + Ansi.dim("GitHub   :") + " " + Ansi.cyan(joinAccountNames(githubAccounts)) + " " + Ansi.dim("[configured]"));
        }
        System.out.println("  " + Ansi.dim("Figma    :") + " " + Ansi.dim(GlobalMcpConfigRegistry.FIGMA_SETUP_NOTE));
        System.out.println();
    }

    private static String joinToolNames(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : keys) names.add(AgentToolRegistry.get(key).name());
        return String.join(", ", names);
    }

    private static String joinSkillNames(List<SkillDescriptor> skills) {
        List<String> names = new ArrayList<>();
        for (SkillDescriptor s : skills) names.add(s.name());
        return String.join(", ", names);
    }

    private static String joinCommandNames(List<CommandDescriptor> commands) {
        List<String> names = new ArrayList<>();
        for (CommandDescriptor c : commands) names.add("/" + c.name());
        return String.join(", ", names);
    }

    private static String joinAgentNames(List<AgentDescriptor> agents) {
        List<String> names = new ArrayList<>();
        for (AgentDescriptor a : agents) names.add("@" + a.name());
        return String.join(", ", names);
    }

    private static String joinOrgNames(List<AzureOrg> orgs) {
        List<String> names = new ArrayList<>();
        for (AzureOrg o : orgs) names.add(o.name());
        return String.join(", ", names);
    }

    private static String joinAccountNames(List<GithubAccount> accounts) {
        List<String> names = new ArrayList<>();
        for (GithubAccount a : accounts) names.add(a.name());
        return String.join(", ", names);
    }
}
