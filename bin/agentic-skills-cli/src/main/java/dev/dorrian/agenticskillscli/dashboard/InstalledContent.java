package dev.dorrian.agenticskillscli.dashboard;

import dev.dorrian.agenticskillscli.BundleExtractor;
import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.AgentDiscovery;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDiscovery;
import dev.dorrian.agenticskillscli.registry.CommandRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The skill, agent and slash-command names the bundle ships, used to tell "installed but never
 * used" from "used". Read from a package root laid out like the jar's {@code bundle/}. Does not
 * touch any tool's install target.
 */
record InstalledContent(List<String> skills, List<String> agents, List<String> commands, String source) {

    /** Scans a package root; commands are the ones the installer actually distributes (CommandRegistry). */
    static InstalledContent load(Path root) {
        List<String> skills = SkillDiscovery.discover(root.resolve("skills")).stream()
            .map(SkillDescriptor::name).distinct().sorted().toList();
        List<String> agents = AgentDiscovery.discover(root.resolve("agents")).stream()
            .map(AgentDescriptor::name).distinct().sorted().toList();
        Path commandsDir = root.resolve(".opencode").resolve("commands");
        TreeSet<String> commands = new TreeSet<>();
        CommandRegistry.SKILL_COMMANDS.values().forEach(commands::addAll);
        CommandRegistry.AGENT_COMMANDS.values().forEach(commands::addAll);
        commands.removeIf(c -> !Files.isRegularFile(commandsDir.resolve(c + ".md")));
        return new InstalledContent(skills, agents, List.copyOf(commands), root.toAbsolutePath().normalize().toString());
    }

    /**
     * The already-extracted bundle for this version ({@code ~/.agentic-skills/dist/<version>/}),
     * if one exists. Never extracts anything itself.
     */
    static Optional<InstalledContent> fromExtractedBundle() {
        try {
            Path dist = BundleExtractor.distDir(HomeDir.resolve(), BundleExtractor.runningVersion());
            if (Files.isRegularFile(dist.resolve(BundleExtractor.COMPLETE_MARKER))) {
                return Optional.of(load(dist));
            }
        } catch (RuntimeException e) {
            // an unreadable bundle just means no installed list
        }
        return Optional.empty();
    }
}
