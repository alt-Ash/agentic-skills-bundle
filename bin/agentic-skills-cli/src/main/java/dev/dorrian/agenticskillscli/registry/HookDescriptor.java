package dev.dorrian.agenticskillscli.registry;

import java.util.List;

/**
 * One hook type built into {@code hooks/agentic-skills-hooks}, and the
 * Claude Code {@code settings.json} event name(s) it should be registered
 * under. See {@link HooksRegistry} for the full list and rationale.
 */
public record HookDescriptor(String hookType, List<String> claudeEventNames) {
}
