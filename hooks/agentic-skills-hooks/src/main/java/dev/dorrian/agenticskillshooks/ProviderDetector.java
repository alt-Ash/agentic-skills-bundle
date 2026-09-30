package dev.dorrian.agenticskillshooks;

import java.util.Set;

/**
 * Java port of hooks/lib/event-log.ts's detectProvider. Order matters: env override first,
 * then Gemini (event name), then Cursor (model + user_email key-presence or truthy
 * conversation_id), then Codex (any model), else Claude (never carries `model` at all).
 */
public final class ProviderDetector {
    private static final Set<String> VALID = Set.of("claude", "gemini", "cursor", "codex", "copilot");

    private ProviderDetector() {
    }

    public static String detect(HookInput input) {
        String envProvider = System.getenv("AI_PROVIDER");
        if (envProvider != null && VALID.contains(envProvider)) {
            return envProvider;
        }

        if ("AfterTool".equals(input.hookEventName())) return "gemini";

        String model = input.model();
        boolean hasModel = model != null && !model.isEmpty();
        if (hasModel && (input.hasKey("user_email") || isTruthy(input.conversationId()))) {
            return "cursor";
        }
        if (hasModel) return "codex";
        return "claude";
    }

    private static boolean isTruthy(String s) {
        return s != null && !s.isEmpty();
    }
}
