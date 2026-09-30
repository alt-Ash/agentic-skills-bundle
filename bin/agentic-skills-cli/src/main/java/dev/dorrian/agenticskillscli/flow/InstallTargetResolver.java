package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.registry.AgentToolDef;

import java.nio.file.Path;

/**
 * Pure path-resolution logic factored out of the Full-install flow's
 * install-target decisions (bin/install.js lines ~3159-3199) so it can be
 * unit-tested independently of the interactive wizard.
 */
public final class InstallTargetResolver {

    private InstallTargetResolver() {
    }

    public static Path skillsPath(AgentToolDef tool, String skillsInstallTarget, Path projectPath) {
        return "global".equals(skillsInstallTarget) ? tool.globalPath() : projectPath.resolve(tool.projectFolder());
    }

    /**
     * Port of the original's inline {@code installGlobal} decision: commands
     * install globally if any selected agent contributes a command, or if
     * skills are (or would be, absent any selected skills) global.
     */
    public static boolean commandsInstallGlobal(boolean hasAgentCommands, String skillsInstallTarget, boolean noSelectedSkills) {
        return hasAgentCommands || "global".equals(skillsInstallTarget) || noSelectedSkills;
    }

    public static Path commandsPath(AgentToolDef tool, boolean installGlobal, Path projectPath) {
        return installGlobal ? tool.commandsGlobalPath() : projectPath.resolve(tool.commandsProjectFolder());
    }

    public static Path agentsPath(AgentToolDef tool, String agentInstallTarget, Path projectPath) {
        if ("project".equals(agentInstallTarget)) {
            String projectFolder = tool.agentsProjectFolder() != null ? tool.agentsProjectFolder() : "agents";
            return projectPath.resolve(projectFolder);
        }
        return tool.agentsGlobalPath();
    }
}
