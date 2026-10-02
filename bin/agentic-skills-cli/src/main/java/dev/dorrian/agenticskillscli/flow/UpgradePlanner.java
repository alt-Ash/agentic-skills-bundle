package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.detect.InstalledAgentDetector;
import dev.dorrian.agenticskillscli.detect.InstalledCommandDetector;
import dev.dorrian.agenticskillscli.detect.InstalledSkillDetector;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.AgentDiscovery;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDiscovery;
import dev.dorrian.agenticskillscli.install.AgentInstaller;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import dev.dorrian.agenticskillscli.install.CommandInstaller;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import dev.dorrian.agenticskillscli.install.TemplateInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.CommandRegistry;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the list of {@link UpgradeOp}s for {@code agentic-skills upgrade}: only what is already
 * installed is refreshed, using the existing overwrite installers. MCP config entries are never
 * touched (credentials, user edits); stale files inside skill directories are left alone.
 */
public final class UpgradePlanner {

    private UpgradePlanner() {
    }

    public static List<UpgradeOp> plan(UpgradeEnvironment env, List<Path> projects) {
        List<SkillDescriptor> skills = SkillDiscovery.discover(env.skillsDir());
        List<AgentDescriptor> agents = AgentDiscovery.discover(env.agentsDir());
        List<CommandDescriptor> commands = candidateCommands(env.commandsDir());

        List<UpgradeOp> ops = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (AgentToolDef tool : env.tools().values()) {
            if (!env.detectedTools().contains(tool.key())) continue;
            addContent(ops, env, tool, "", skills, agents, commands,
                tool.globalPath(), tool.agentsGlobalPath(), tool.commandsGlobalPath(), seen);
            addTemplates(ops, env, tool);
            addHooks(ops, env, tool);
        }
        for (Path project : projects) {
            for (AgentToolDef tool : env.tools().values()) {
                addContent(ops, env, tool, " [project " + project + "]", skills, agents, commands,
                    tool.projectFolder() == null ? null : project.resolve(tool.projectFolder()),
                    tool.supportsAgents() && tool.agentsProjectFolder() != null
                        ? project.resolve(tool.agentsProjectFolder()) : null,
                    tool.commandsProjectFolder() == null ? null : project.resolve(tool.commandsProjectFolder()),
                    seen);
            }
        }
        addMcps(ops, env);
        return ops;
    }

    private static void addContent(List<UpgradeOp> ops, UpgradeEnvironment env, AgentToolDef tool, String suffix,
                                   List<SkillDescriptor> skills, List<AgentDescriptor> agents,
                                   List<CommandDescriptor> commands,
                                   Path skillsPath, Path agentsPath, Path commandsPath, Set<String> seen) {
        String key = tool.key();
        Set<String> installedSkills = InstalledSkillDetector.detect(skills, skillsPath);
        for (SkillDescriptor skill : skills) {
            // Legacy-only copies are not refreshed: that would leave a duplicate beside the stale one.
            if (!installedSkills.contains(skill.name()) || !Files.exists(skillsPath.resolve(skill.name()))) continue;
            if (!seen.add("skill:" + skillsPath.resolve(skill.name()))) continue;
            ops.add(new UpgradeOp("refresh skill " + skill.name() + " for " + key + suffix,
                () -> check(SkillInstaller.install(List.of(skill), skillsPath))));
        }

        if (tool.supportsAgents() && agentsPath != null) {
            Set<String> installedAgents = InstalledAgentDetector.detect(agents, agentsPath, key);
            for (AgentDescriptor agent : agents) {
                if (!installedAgents.contains(agent.name())) continue;
                if (!seen.add("agent:" + key + ":" + agentsPath.resolve(agent.name()))) continue;
                // Only the agent file is refreshed; config entries (opencode.json) are never re-registered.
                ops.add(new UpgradeOp("refresh agent " + agent.name() + " for " + key + suffix,
                    () -> check(AgentInstaller.install(List.of(agent), agentsPath, key, env.agentsDir()))));
            }
        }

        if (tool.supportsCommands() && commandsPath != null) {
            Set<String> installedCommands = InstalledCommandDetector.detect(
                commands.stream().map(CommandDescriptor::name).toList(), commandsPath);
            for (CommandDescriptor command : commands) {
                if (!installedCommands.contains(command.name())) continue;
                if (!seen.add("command:" + commandsPath.resolve(command.name() + ".md"))) continue;
                ops.add(new UpgradeOp("refresh command " + command.name() + " for " + key + suffix,
                    () -> check(CommandInstaller.install(List.of(command), commandsPath))));
            }
        }
    }

    private static void addTemplates(List<UpgradeOp> ops, UpgradeEnvironment env, AgentToolDef tool) {
        Path agentsPath = tool.agentsGlobalPath();
        if (!tool.supportsAgents() || agentsPath == null || !Files.isDirectory(agentsPath.resolve("templates"))) return;
        ops.add(new UpgradeOp("refresh templates for " + tool.key(),
            () -> check(TemplateInstaller.install(agentsPath, env.templatesDir()))));
    }

    private static void addHooks(List<UpgradeOp> ops, UpgradeEnvironment env, AgentToolDef tool) {
        String key = tool.key();
        if (!env.hooks().supports(key) || !env.hooks().isInstalled(key)) return;
        ops.add(new UpgradeOp("refresh hooks for " + key, () -> {
            HookInstallOptions options = env.hooks().currentOptions(key);
            env.hooks().install(key, options);
        }));
    }

    private static void addMcps(List<UpgradeOp> ops, UpgradeEnvironment env) {
        for (UpgradeEnvironment.McpJar mcp : env.mcps()) {
            if (!Files.isRegularFile(mcp.installedJar())) continue;
            ops.add(new UpgradeOp("refresh MCP jar " + mcp.name(),
                () -> mcp.installer().install(mcp.bundledJar(), mcp.installDir())));
        }
    }

    /** Every companion command that has a source file, once per name. */
    private static List<CommandDescriptor> candidateCommands(Path commandsDir) {
        Map<String, CommandDescriptor> byName = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        CommandRegistry.SKILL_COMMANDS.values().forEach(names::addAll);
        CommandRegistry.AGENT_COMMANDS.values().forEach(names::addAll);
        for (String name : names.stream().sorted().toList()) {
            Path src = commandsDir.resolve(name + ".md");
            if (Files.exists(src)) byName.putIfAbsent(name, new CommandDescriptor(name, src));
        }
        return List.copyOf(byName.values());
    }

    private static void check(List<OperationResult> results) {
        String failures = results.stream().filter(r -> !r.success())
            .map(r -> r.name() + ": " + r.error()).collect(Collectors.joining("; "));
        if (!failures.isEmpty()) throw new IllegalStateException(failures);
    }
}
