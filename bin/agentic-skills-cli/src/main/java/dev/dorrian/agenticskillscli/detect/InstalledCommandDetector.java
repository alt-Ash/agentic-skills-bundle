package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java port of {@code bin/install.js}'s {@code detectInstalledCommands()} —
 * not explicitly enumerated in this task's file list, but present in the
 * original source alongside the other detection helpers and ported here for
 * completeness (it is used by the uninstall wizard, built in a later pass).
 */
public final class InstalledCommandDetector {

    private InstalledCommandDetector() {
    }

    public static Set<String> detect(List<String> commandNames, List<String> toolKeys) {
        Set<String> installed = new LinkedHashSet<>();
        for (String name : commandNames) {
            for (String toolKey : toolKeys) {
                AgentToolDef tool = AgentToolRegistry.get(toolKey);
                Path commandsPath = tool.commandsGlobalPath();
                if (commandsPath == null) continue;
                if (Files.exists(commandsPath.resolve(name + ".md"))) {
                    installed.add(name);
                    break;
                }
            }
        }
        return installed;
    }
}
