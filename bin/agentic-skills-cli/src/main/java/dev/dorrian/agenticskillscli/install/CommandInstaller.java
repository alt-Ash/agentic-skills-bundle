package dev.dorrian.agenticskillscli.install;

import dev.dorrian.agenticskillscli.PackageRoot;
import dev.dorrian.agenticskillscli.config.OperationResult;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.registry.CommandRegistry;
import dev.dorrian.agenticskillscli.state.ContentHash;
import dev.dorrian.agenticskillscli.state.InstallRecorder;
import dev.dorrian.agenticskillscli.state.ItemKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Java port of {@code bin/install.js}'s {@code resolveCommands()}, {@code
 * resolveAgentCommands()}, and {@code installCommands()}, plus the inline
 * command-removal logic from {@code runUninstall()} (no separate {@code
 * uninstallCommands} function exists in the original).
 */
public final class CommandInstaller {

    private CommandInstaller() {
    }

    /** Port of {@code resolveCommands(selectedSkills)}, using the real package's commands dir. */
    public static List<CommandDescriptor> resolveForSkills(List<SkillDescriptor> selectedSkills) {
        return resolveForSkills(selectedSkills, PackageRoot.commandsDir());
    }

    public static List<CommandDescriptor> resolveForSkills(List<SkillDescriptor> selectedSkills, Path commandsDir) {
        List<CommandDescriptor> commands = new ArrayList<>();
        for (SkillDescriptor skill : selectedSkills) {
            for (String cmdName : CommandRegistry.commandsForSkill(skill.name())) {
                Path srcFile = commandsDir.resolve(cmdName + ".md");
                if (Files.exists(srcFile)) {
                    commands.add(new CommandDescriptor(cmdName, srcFile));
                }
            }
        }
        return commands;
    }

    /** Port of {@code resolveAgentCommands(selectedAgentFiles)}, using the real package's commands dir. */
    public static List<CommandDescriptor> resolveForAgents(List<AgentDescriptor> selectedAgentFiles) {
        return resolveForAgents(selectedAgentFiles, PackageRoot.commandsDir());
    }

    public static List<CommandDescriptor> resolveForAgents(List<AgentDescriptor> selectedAgentFiles, Path commandsDir) {
        List<CommandDescriptor> commands = new ArrayList<>();
        for (AgentDescriptor agent : selectedAgentFiles) {
            for (String cmdName : CommandRegistry.commandsForAgent(agent.name())) {
                Path srcFile = commandsDir.resolve(cmdName + ".md");
                if (Files.exists(srcFile)) {
                    commands.add(new CommandDescriptor(cmdName, srcFile));
                }
            }
        }
        return commands;
    }

    public static List<OperationResult> install(List<CommandDescriptor> commands, Path targetPath) {
        return install(commands, targetPath, InstallRecorder.NOOP);
    }

    public static List<OperationResult> install(List<CommandDescriptor> commands, Path targetPath, InstallRecorder recorder) {
        ensureDir(targetPath);
        List<OperationResult> results = new ArrayList<>();
        for (CommandDescriptor cmd : commands) {
            Path dest = targetPath.resolve(cmd.name() + ".md");
            try {
                Files.copy(cmd.srcFile(), dest, StandardCopyOption.REPLACE_EXISTING);
                try {
                    recorder.record(ItemKind.COMMAND, cmd.name(), ContentHash.ofFile(dest));
                } catch (IOException | RuntimeException ignored) {
                    // recording must never fail an install
                }
                results.add(OperationResult.ok(cmd.name(), false, null));
            } catch (IOException e) {
                results.add(OperationResult.failed(cmd.name(), null, e.getMessage()));
            }
        }
        return results;
    }

    /** Removes an installed command file if present; skipped=true if it was already absent. */
    public static OperationResult remove(String commandName, Path targetPath) {
        Path dest = targetPath.resolve(commandName + ".md");
        try {
            if (!Files.exists(dest)) {
                return OperationResult.ok(commandName, true, null);
            }
            Files.delete(dest);
            return OperationResult.ok(commandName, false, null);
        } catch (IOException e) {
            return OperationResult.failed(commandName, null, e.getMessage());
        }
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
