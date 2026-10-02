package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;

/**
 * Records {@code SubagentStart} / {@code SubagentStop} (discriminated by the payload's
 * {@code hook_event_name}) as usage events, like the other analytics hooks: never blocks, always exit 0.
 * Owned by the skill/agent usage slice; a no-op until implemented.
 */
public final class SubagentHook {

    private SubagentHook() {
    }

    public static void run(HookInput input) {
        // intentionally empty: filled in by the skill/agent usage slice
    }
}
