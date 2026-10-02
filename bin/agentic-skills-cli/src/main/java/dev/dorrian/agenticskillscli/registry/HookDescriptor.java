package dev.dorrian.agenticskillscli.registry;

import java.util.List;

/**
 * One hook type built into {@code hooks/agentic-skills-hooks}, and the
 * Claude Code {@code settings.json} event name(s) it should be registered
 * under. See {@link HooksRegistry} for the full list and rationale.
 *
 * <p>{@code matcher} is the Claude Code tool-name matcher ({@code ""} matches everything);
 * {@code timeoutSeconds} caps one firing so a hung JVM can't stall a turn.
 */
public record HookDescriptor(String hookType, List<String> claudeEventNames, String matcher, int timeoutSeconds) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public HookDescriptor(String hookType, List<String> claudeEventNames) {
        this(hookType, claudeEventNames, "", DEFAULT_TIMEOUT_SECONDS);
    }
}
