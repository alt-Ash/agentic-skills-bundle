package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.usagestore.UsageEvent;

/**
 * Attributes a tool call to the skill or agent it invoked (the {@code Skill} and {@code Agent}/{@code Task}
 * tools), filling {@code event.skillName} / {@code event.agentName}. Only the identifying name is read;
 * prompt/description text is never touched.
 */
public final class ToolAttribution {

    private ToolAttribution() {
    }

    public static void apply(HookInput input, UsageEvent event) {
        if (input == null || event == null) return;
        String tool = input.toolName();
        if (tool == null) return;
        switch (tool) {
            case "Skill" -> event.skillName = nonBlank(input.toolInputText("skill"));
            case "Task", "Agent" -> event.agentName = nonBlank(input.toolInputText("subagent_type"));
            default -> { }
        }
    }

    private static String nonBlank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
