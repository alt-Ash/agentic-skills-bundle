package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.AgentDiscovery;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDiscovery;
import dev.dorrian.agenticskillscli.frontmatter.AgentFileNaming;
import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.NewItem;
import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.Outcome;
import dev.dorrian.agenticskillscli.install.AgentInstaller;
import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import dev.dorrian.agenticskillscli.install.CommandInstaller;
import dev.dorrian.agenticskillscli.install.SkillInstaller;
import dev.dorrian.agenticskillscli.install.TemplateInstaller;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.CommandRegistry;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.state.BundleCatalog;
import dev.dorrian.agenticskillscli.state.ContentHash;
import dev.dorrian.agenticskillscli.state.InstallDetector;
import dev.dorrian.agenticskillscli.state.InstallManifest;
import dev.dorrian.agenticskillscli.state.ItemKind;
import dev.dorrian.agenticskillscli.state.ItemState;
import dev.dorrian.agenticskillscli.state.ManifestRecorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the diff-aware plan for {@code agentic-skills upgrade}: every bundled item is classified by
 * one {@link InstallDetector}; only UPDATE_AVAILABLE items get a write action. Nothing is ever newly
 * installed, MCP config entries are never touched, and stale files in skill directories are left alone.
 */
public final class UpgradePlanner {

    /** The planned steps, the not-installed new items, and the manifest the steps record into. */
    public record Plan(List<UpgradeOp> ops, List<NewItem> newItems, boolean baselineMissing,
                       InstallManifest manifest, List<String> bundleItems,
                       List<String> installedEntries, List<Runnable> backfills) {
    }

    private UpgradePlanner() {
    }

    public static Plan plan(UpgradeEnvironment env, List<Path> projects) {
        InstallManifest manifest = InstallManifest.load(env.manifestFile());
        List<SkillDescriptor> skills = SkillDiscovery.discover(env.skillsDir());
        List<AgentDescriptor> agents = AgentDiscovery.discover(env.agentsDir());
        List<CommandDescriptor> commands = candidateCommands(env.commandsDir());

        Ctx ctx = new Ctx(env, manifest, skills, agents, commands);
        for (AgentToolDef tool : env.tools().values()) {
            if (!env.detectedTools().contains(tool.key())) continue;
            addContent(ctx, tool, "global", tool.key(),
                tool.globalPath(), tool.agentsGlobalPath(), tool.commandsGlobalPath());
            addTemplates(ctx, tool);
            addHooks(ctx, tool);
        }
        for (Path project : projects) {
            String scope = project.toString();
            for (AgentToolDef tool : env.tools().values()) {
                addContent(ctx, tool, scope, tool.key() + " [project " + project + "]",
                    tool.projectFolder() == null ? null : project.resolve(tool.projectFolder()),
                    tool.supportsAgents() && tool.agentsProjectFolder() != null
                        ? project.resolve(tool.agentsProjectFolder()) : null,
                    tool.commandsProjectFolder() == null ? null : project.resolve(tool.commandsProjectFolder()));
            }
        }
        addMcps(ctx);

        boolean haveBaseline = !manifest.bundleItems().isEmpty();
        List<NewItem> newItems = new ArrayList<>();
        if (haveBaseline) {
            for (Map.Entry<String, NewItem> e : ctx.candidates.entrySet()) {
                if (!ctx.installedEntries.contains(e.getKey())) newItems.add(e.getValue());
            }
        }
        List<String> bundleItems = BundleCatalog.items(
            skills.stream().map(SkillDescriptor::name).toList(),
            agents.stream().map(AgentDescriptor::name).toList(),
            commands.stream().map(CommandDescriptor::name).toList(),
            env.mcps().stream().map(UpgradeEnvironment.McpJar::name).toList());
        return new Plan(ctx.ops, newItems, !haveBaseline, manifest, bundleItems,
            ctx.installedEntries.stream().sorted().toList(), ctx.backfills);
    }

    private static final class Ctx {
        final UpgradeEnvironment env;
        final InstallManifest manifest;
        final List<SkillDescriptor> skills;
        final List<AgentDescriptor> agents;
        final List<CommandDescriptor> commands;
        final List<UpgradeOp> ops = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final Set<String> installedEntries = new HashSet<>();
        final List<Runnable> backfills = new ArrayList<>();
        final Map<String, NewItem> candidates = new LinkedHashMap<>();

        Ctx(UpgradeEnvironment env, InstallManifest manifest, List<SkillDescriptor> skills,
            List<AgentDescriptor> agents, List<CommandDescriptor> commands) {
            this.env = env;
            this.manifest = manifest;
            this.skills = skills;
            this.agents = agents;
            this.commands = commands;
        }

        /** Turns the detector's answer into a planned op (or a "new item" candidate). */
        void handle(ItemState state, ItemKind kind, String name, String label, UpgradeOp.Action refresh,
                    UpgradeOp.Action backfill) {
            String kindLabel = kindLabel(kind);
            String entry = BundleCatalog.entry(kind, name);
            switch (state.status()) {
                case NOT_INSTALLED -> {
                    // Templates are not in the bundle catalog, so a missing install is never a "new item".
                    if (kind != ItemKind.TEMPLATE
                        && !manifest.bundleItems().isEmpty() && !manifest.bundleItems().contains(entry)) {
                        candidates.putIfAbsent(entry, new NewItem(kindLabel, name, env.bundleVersion()));
                    }
                }
                case UP_TO_DATE -> {
                    installedEntries.add(entry);
                    ops.add(new UpgradeOp(label, kindLabel, name, Outcome.UNCHANGED, null));
                    if (state.comparedByContent() && backfill != null) {
                        // Up to date but never recorded: remember it so a later local edit is detected.
                        backfills.add(() -> {
                            try {
                                backfill.run();
                            } catch (Exception ignored) {
                                // recording must never fail an upgrade
                            }
                        });
                    }
                }
                case MODIFIED -> {
                    installedEntries.add(entry);
                    ops.add(new UpgradeOp(label, kindLabel, name, Outcome.SKIPPED_MODIFIED, null));
                }
                case UPDATE_AVAILABLE -> {
                    installedEntries.add(entry);
                    ops.add(new UpgradeOp(label, kindLabel, name, Outcome.REFRESHED, refresh));
                }
            }
        }
    }

    private static String kindLabel(ItemKind kind) {
        return kind == ItemKind.MCP_JAR ? "mcp" : kind.name().toLowerCase();
    }

    private static void addContent(Ctx c, AgentToolDef tool, String scope, String label,
                                   Path skillsPath, Path agentsPath, Path commandsPath) {
        String key = tool.key();
        InstallDetector detector = new InstallDetector(c.manifest, scope);
        ManifestRecorder recorder = new ManifestRecorder(c.manifest, scope, key, c.env.bundleVersion());

        if (skillsPath != null) {
            for (SkillDescriptor skill : c.skills) {
                if (!c.seen.add("skill:" + skillsPath.resolve(skill.name()))) continue;
                c.handle(detector.skill(skill, skillsPath, key), ItemKind.SKILL, skill.name(), label,
                    () -> check(SkillInstaller.install(List.of(skill), skillsPath, recorder)),
                    () -> {
                        Path live = skillsPath.resolve(skill.name());
                        recorder.record(ItemKind.SKILL, skill.name(),
                            ContentHash.ofTreeLimitedTo(live, skill.sourcePath()),
                            ContentHash.treeFiles(skill.sourcePath()));
                    });
            }
        }
        if (tool.supportsAgents() && agentsPath != null) {
            for (AgentDescriptor agent : c.agents) {
                if (!c.seen.add("agent:" + key + ":" + agentsPath.resolve(agent.name()))) continue;
                // Only the agent file is refreshed; config entries (opencode.json) are never re-registered.
                c.handle(detector.agent(agent, agentsPath, key), ItemKind.AGENT, agent.name(), label,
                    () -> check(AgentInstaller.install(List.of(agent), agentsPath, key, c.env.agentsDir(), recorder)),
                    () -> recorder.record(ItemKind.AGENT, agent.name(), ContentHash.ofFile(
                        agentsPath.resolve(AgentFileNaming.fileName(agent.name(), key)))));
            }
        }
        if (tool.supportsCommands() && commandsPath != null) {
            for (CommandDescriptor command : c.commands) {
                if (!c.seen.add("command:" + commandsPath.resolve(command.name() + ".md"))) continue;
                c.handle(detector.command(command, commandsPath, key), ItemKind.COMMAND, command.name(), label,
                    () -> check(CommandInstaller.install(List.of(command), commandsPath, recorder)),
                    () -> recorder.record(ItemKind.COMMAND, command.name(),
                        ContentHash.ofFile(commandsPath.resolve(command.name() + ".md"))));
            }
        }
    }

    private static void addTemplates(Ctx c, AgentToolDef tool) {
        Path agentsPath = tool.agentsGlobalPath();
        if (!tool.supportsAgents() || agentsPath == null) return;
        String key = tool.key();
        InstallDetector detector = new InstallDetector(c.manifest, "global");
        ManifestRecorder recorder = new ManifestRecorder(c.manifest, "global", key, c.env.bundleVersion());
        Path live = agentsPath.resolve("templates");
        c.handle(detector.template(live, c.env.templatesDir(), key),
            ItemKind.TEMPLATE, "templates", key,
            () -> check(TemplateInstaller.install(agentsPath, c.env.templatesDir(), recorder)),
            () -> recorder.record(ItemKind.TEMPLATE, "templates",
                ContentHash.ofTreeLimitedTo(live, c.env.templatesDir()),
                ContentHash.treeFiles(c.env.templatesDir())));
    }

    /**
     * Hooks: refreshed only when already registered and the installed jar differs from the bundled one;
     * opt-ins are read back and kept.
     */
    private static void addHooks(Ctx c, AgentToolDef tool) {
        String key = tool.key();
        UpgradeEnvironment.Hooks hooks = c.env.hooks();
        if (!hooks.supports(key) || !hooks.isInstalled(key)) return;
        if (hooks.isCurrent(key)) {
            c.ops.add(new UpgradeOp(key, "hooks", "analytics", Outcome.UNCHANGED, null));
            return;
        }
        c.ops.add(new UpgradeOp(key, "hooks", "analytics", Outcome.REFRESHED, () -> {
            HookInstallOptions options = hooks.currentOptions(key);
            hooks.install(key, options);
        }));
    }

    private static void addMcps(Ctx c) {
        InstallDetector detector = new InstallDetector(c.manifest, "global");
        ManifestRecorder recorder = new ManifestRecorder(c.manifest, "global", "-", c.env.bundleVersion());
        for (UpgradeEnvironment.McpJar mcp : c.env.mcps()) {
            c.handle(detector.mcpJar(mcp.name(), mcp.installedJar(), mcp.bundledJar()),
                ItemKind.MCP_JAR, mcp.name(), "MCP jars", () -> {
                    mcp.installer().install(mcp.bundledJar(), mcp.installDir());
                    // The MCP installers have no recorder overload, so record what landed on disk.
                    recorder.record(ItemKind.MCP_JAR, mcp.name(), ContentHash.ofFile(mcp.installedJar()));
                }, () -> recorder.record(ItemKind.MCP_JAR, mcp.name(), ContentHash.ofFile(mcp.installedJar())));
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
