package dev.dorrian.agenticskillscli.registry;

import java.util.List;
import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code SKILL_COMMANDS} and {@code
 * AGENT_COMMANDS} objects — maps a skill/agent name to the (extensionless)
 * command file basenames that companion it. Ported field-for-field from the
 * source read directly on 2026-09-30.
 */
public final class CommandRegistry {

    public static final Map<String, List<String>> SKILL_COMMANDS = Map.of(
        "nodejs-version-migrator", List.of("migrate-node"),
        "cra-to-vite", List.of("cra-to-vite"),
        "vite-version-migrator", List.of("migrate-vite"),
        "mui-migration", List.of("migrate-mui"),
        "react-migration", List.of("migrate-react"),
        "secure-feature-gate", List.of("security-gate")
    );

    public static final Map<String, List<String>> AGENT_COMMANDS = Map.of(
        "issue-architect", List.of("new-issue"),
        "issue-implementer", List.of("implement-issue"),
        "pr-reviewer", List.of("pr-check")
    );

    private CommandRegistry() {
    }

    public static List<String> commandsForSkill(String skillName) {
        return SKILL_COMMANDS.getOrDefault(skillName, List.of());
    }

    public static List<String> commandsForAgent(String agentName) {
        return AGENT_COMMANDS.getOrDefault(agentName, List.of());
    }
}
