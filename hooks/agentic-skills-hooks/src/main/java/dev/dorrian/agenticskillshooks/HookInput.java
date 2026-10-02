package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.SecretRedactor;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Raw hook payload from stdin. Every field is optional and accessed defensively — a
 * faithful port of hooks/lib/event-log.ts's HookInput interface, backed by a JsonNode
 * so key-presence checks (e.g. detectProvider's `user_email !== undefined`) are exact,
 * not approximated by a null-value check.
 */
public final class HookInput {
    private final JsonNode raw;

    private HookInput(JsonNode raw) {
        this.raw = raw;
    }

    public static HookInput parse(String rawText) {
        try {
            JsonNode node = JsonSupport.MAPPER.readTree(rawText);
            if (node != null && node.isObject()) {
                return new HookInput(node);
            }
        } catch (Exception ignored) {
            // fall through to empty input
        }
        return new HookInput(JsonSupport.MAPPER.createObjectNode());
    }

    /** Key-presence check — matches TS `input.field !== undefined`, true even if the value is null. */
    public boolean hasKey(String key) {
        return raw.has(key);
    }

    private String textOrNull(String key) {
        JsonNode n = raw.get(key);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }

    public String hookEventName() {
        return textOrNull("hook_event_name");
    }

    public String transcriptPath() {
        return textOrNull("transcript_path");
    }

    public String conversationId() {
        return textOrNull("conversation_id");
    }

    public String cwd() {
        return textOrNull("cwd");
    }

    public String model() {
        return textOrNull("model");
    }

    public String userEmail() {
        return textOrNull("user_email");
    }

    public String source() {
        return textOrNull("source");
    }

    public String reason() {
        return textOrNull("reason");
    }

    public String prompt() {
        return textOrNull("prompt");
    }

    public String permissionMode() {
        return textOrNull("permission_mode");
    }

    public String promptId() {
        return textOrNull("prompt_id");
    }

    public String toolName() {
        return textOrNull("tool_name");
    }

    public String toolUseId() {
        return textOrNull("tool_use_id");
    }

    /** Sub-agent id ({@code agent_id}) on SubagentStart/Stop and in-subagent tool events, or null. */
    public String agentId() {
        return textOrNull("agent_id");
    }

    /** Sub-agent type ({@code agent_type}) on SubagentStart/Stop and in-subagent tool events, or null. */
    public String agentType() {
        return textOrNull("agent_type");
    }

    public String error() {
        return textOrNull("error");
    }

    public String lastAssistantMessage() {
        return textOrNull("last_assistant_message");
    }

    /** TS `sessionId(input)`: session_id ?? conversation_id ?? null. */
    public String sessionId() {
        String sid = textOrNull("session_id");
        return sid != null ? sid : conversationId();
    }

    /** A string field of {@code tool_input} (e.g. {@code file_path}, {@code command}), or null. */
    public String toolInputText(String key) {
        JsonNode toolInput = raw.get("tool_input");
        if (toolInput == null || !toolInput.isObject()) return null;
        JsonNode value = toolInput.get(key);
        return (value != null && value.isTextual()) ? value.asText() : null;
    }

    private String toolInputCommand() {
        JsonNode toolInput = raw.get("tool_input");
        if (toolInput == null || !toolInput.isObject()) return null;
        JsonNode command = toolInput.get("command");
        return (command != null && command.isTextual()) ? command.asText() : null;
    }

    /** TS `extractBashCommand(input)` — only ever non-null for the Bash tool, redacted before return. */
    public String extractBashCommand() {
        if (!"Bash".equals(toolName())) return null;
        String command = toolInputCommand();
        return command != null ? SecretRedactor.redact(command) : null;
    }

    public Long durationMs() {
        JsonNode n = raw.get("duration_ms");
        return (n != null && n.isNumber()) ? n.asLong() : null;
    }

    public Boolean stopHookActive() {
        JsonNode n = raw.get("stop_hook_active");
        return (n != null && n.isBoolean()) ? n.asBoolean() : null;
    }

    public int backgroundTaskCount() {
        JsonNode n = raw.get("background_tasks");
        return (n != null && n.isArray()) ? n.size() : 0;
    }
}
