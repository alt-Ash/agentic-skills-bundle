package dev.dorrian.agenticskillshooks;

import java.util.Set;

/**
 * Java port of hooks/lib/event-log.ts's detectProvider. Order matters: env override first,
 * then an explicit `agentic_skills_provider` field (OpenCode plugin), then Gemini (event name), then Cursor (model + user_email key-presence or truthy
 * conversation_id), then Codex (any model), else Claude (never carries `model` at all).
 */
public final class ProviderDetector {
    private static final Set<String> VALID = Set.of("claude", "gemini", "cursor", "codex", "copilot", "opencode", "antigravity");

    private ProviderDetector() {
    }

    public static String detect(HookInput input) {
        String envProvider = System.getenv("AI_PROVIDER");
        if (envProvider != null && VALID.contains(envProvider)) {
            return envProvider;
        }

        // Tools we forward events for through our own shim (the OpenCode plugin) say so explicitly.
        String declared = input.stringField("agentic_skills_provider");
        if (declared != null && VALID.contains(declared)) return declared;

        if (isGeminiEvent(input)) return "gemini";

        String model = input.model();
        boolean hasModel = model != null && !model.isEmpty();
        if (hasModel && (input.hasKey("user_email") || isTruthy(input.conversationId()))) {
            return "cursor";
        }
        if (hasModel) return "codex";
        return "claude";
    }

    /**
     * Gemini CLI event names that Claude Code never emits (AfterTool, BeforeAgent, AfterAgent), plus
     * SessionStart/SessionEnd, which both tools share but only Gemini stamps with a top-level
     * {@code timestamp}.
     */
    private static boolean isGeminiEvent(HookInput input) {
        String event = input.hookEventName();
        if (event == null) return false;
        return switch (event) {
            case "AfterTool", "BeforeAgent", "AfterAgent" -> true;
            case "SessionStart", "SessionEnd" -> input.hasKey("timestamp");
            default -> false;
        };
    }

    private static boolean isTruthy(String s) {
        return s != null && !s.isEmpty();
    }
}
