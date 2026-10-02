package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.usagestore.UsageEvent;

/**
 * Attributes a tool call to the skill or agent it invoked (the {@code Skill} and {@code Agent}/{@code Task}
 * tools), filling {@code event.skillName} / {@code event.agentName}. Called from PostToolUseHook.
 * Owned by the skill/agent usage slice; a no-op until implemented.
 */
public final class ToolAttribution {

    private ToolAttribution() {
    }

    public static void apply(HookInput input, UsageEvent event) {
        // intentionally empty: filled in by the skill/agent usage slice
    }
}
