package dev.dorrian.agenticskillscli.registry;

import java.util.List;

/**
 * NEW registry — there is no TypeScript equivalent. {@code bin/install.js}
 * has never had any code path to register {@code hooks/*} into a consuming
 * tool's config (see CLAUDE.md's "Known gap" note); this is a from-scratch
 * design, not a port.
 *
 * <p>One entry per hook type built into {@code hooks/agentic-skills-hooks}
 * ({@code java -jar agentic-skills-hooks.jar <hookType>}), mapped to the
 * Claude Code {@code settings.json} hook event name(s) it should be
 * registered under. Scoped to Claude Code only for v1 — the schema for
 * other tools' hook configuration (if any exists) is unresearched; do not
 * invent it here. {@code session} is the one hook that fires on two
 * distinct events (SessionStart and SessionEnd), matching how {@code
 * hooks/agentic-skills-hooks}'s {@code SessionHook} class discriminates
 * between them internally via the stdin payload's {@code hook_event_name}.
 */
public final class HooksRegistry {

    public static final List<HookDescriptor> ALL = List.of(
        new HookDescriptor("post-tool-use", List.of("PostToolUse")),
        new HookDescriptor("post-tool-use-failure", List.of("PostToolUseFailure")),
        new HookDescriptor("user-prompt-submit", List.of("UserPromptSubmit")),
        new HookDescriptor("stop", List.of("Stop")),
        new HookDescriptor("session", List.of("SessionStart", "SessionEnd"))
    );

    /**
     * Opt-in guardrail: the only hook that can block (exit 2). Kept out of {@link #ALL} so a
     * plain install stays observation-only; registered only when the user asks for it.
     */
    public static final HookDescriptor GUARD = new HookDescriptor(
        "guard", List.of("PreToolUse"), "Bash|Read|Edit|Write|MultiEdit|NotebookEdit", 10);

    private HooksRegistry() {
    }
}
