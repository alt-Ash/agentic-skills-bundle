package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;

import java.util.Optional;

/**
 * Opt-in {@code SessionStart} context injector (including after compaction): the text it returns is
 * printed to stdout, which Claude Code adds to the session context. Always exit 0. Owned by the
 * verify/context slice; injects nothing until implemented.
 */
public final class ContextHook {

    private ContextHook() {
    }

    /** Returns the context to inject, or empty for none. */
    public static Optional<String> contextFor(HookInput input) {
        return Optional.empty();
    }
}
