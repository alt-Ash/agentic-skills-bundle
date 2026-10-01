package dev.dorrian.agenticskillscli.detect;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.frontmatter.AgentFileNaming;
import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import dev.dorrian.agenticskillscli.registry.AgentToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java port of {@code bin/install.js}'s {@code detectInstalledAgents()} —
 * for each agent, true if it exists in ANY of the given tools' global
 * agents paths.
 */
public final class InstalledAgentDetector {

    private InstalledAgentDetector() {
    }

    public static Set<String> detect(List<AgentDescriptor> agents, List<String> toolKeys) {
        Set<String> installed = new LinkedHashSet<>();
        for (AgentDescriptor agent : agents) {
            for (String toolKey : toolKeys) {
                AgentToolDef tool = AgentToolRegistry.get(toolKey);
                Path agentsPath = tool.agentsGlobalPath();
                if (agentsPath == null) continue;
                String destFile = AgentFileNaming.fileName(agent.name(), toolKey);
                boolean found = Files.exists(agentsPath.resolve(destFile))
                    || AgentFileNaming.legacyFileNames(agent.name(), toolKey).stream()
                        .anyMatch(f -> Files.exists(agentsPath.resolve(f)));
                if (found) {
                    installed.add(agent.name());
                    break;
                }
            }
        }
        return installed;
    }
}
