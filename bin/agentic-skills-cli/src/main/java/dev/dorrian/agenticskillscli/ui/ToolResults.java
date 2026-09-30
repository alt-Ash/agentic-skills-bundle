package dev.dorrian.agenticskillscli.ui;

import dev.dorrian.agenticskillscli.config.AgentRegistrationResult;
import dev.dorrian.agenticskillscli.config.OperationResult;

import java.util.List;

/**
 * Java equivalent of one {@code resultsByTool[toolKey]} entry in {@code
 * bin/install.js}: {@code { skills, commands, agents, configRegs, templates,
 * mcps, skillsPath, commandsPath, agentsPath } }. Used for both the install
 * and uninstall summaries — {@code templates} is always empty for an
 * uninstall run, matching the original (templates are never removed).
 */
public record ToolResults(
    String toolName,
    List<OperationResult> skills,
    List<OperationResult> commands,
    List<OperationResult> agents,
    List<AgentRegistrationResult> configRegs,
    List<OperationResult> templates,
    List<OperationResult> mcps,
    String skillsPath,
    String commandsPath,
    String agentsPath
) {

    public static ToolResults empty(String toolName) {
        return new ToolResults(toolName, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null);
    }
}
