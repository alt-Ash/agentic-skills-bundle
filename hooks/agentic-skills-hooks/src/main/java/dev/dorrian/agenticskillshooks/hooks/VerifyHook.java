package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;

import java.util.Optional;

/**
 * Opt-in {@code Stop} gate: runs the project's verification gates and, if they fail, blocks Claude
 * from stopping. HookDispatcher turns a non-empty result into exit 2 with the text on stderr (the
 * same blocking contract as the guard) and fails open on any error. Owned by the verify/context
 * slice; allows everything until implemented.
 */
public final class VerifyHook {

    private VerifyHook() {
    }

    /** Returns the reason to keep Claude working, or empty to let it stop. */
    public static Optional<String> evaluate(HookInput input) {
        return Optional.empty();
    }
}
