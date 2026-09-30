package dev.dorrian.agenticskillsevals.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * A PreToolUse/PostToolUse hook callback's decision, matching the wire shape verified in the
 * claude_cli_control_protocol memory: an empty object allows the tool call to proceed unmodified;
 * a populated {@code hookSpecificOutput.permissionDecision} can deny or modify it.
 */
public final class HookDecision {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectNode responsePayload;

    private HookDecision(ObjectNode responsePayload) {
        this.responsePayload = responsePayload;
    }

    public static HookDecision allow() {
        return new HookDecision(NODES.objectNode());
    }

    public static HookDecision deny(String reason) {
        ObjectNode hookSpecificOutput = NODES.objectNode();
        hookSpecificOutput.put("hookEventName", "PreToolUse");
        hookSpecificOutput.put("permissionDecision", "deny");
        hookSpecificOutput.put("permissionDecisionReason", reason);
        ObjectNode payload = NODES.objectNode();
        payload.set("hookSpecificOutput", hookSpecificOutput);
        return new HookDecision(payload);
    }

    public static HookDecision modify(JsonNode updatedInput) {
        ObjectNode hookSpecificOutput = NODES.objectNode();
        hookSpecificOutput.put("hookEventName", "PreToolUse");
        hookSpecificOutput.put("permissionDecision", "allow");
        hookSpecificOutput.set("updatedInput", updatedInput);
        ObjectNode payload = NODES.objectNode();
        payload.set("hookSpecificOutput", hookSpecificOutput);
        return new HookDecision(payload);
    }

    /** Raw JSON payload to place under {@code control_response.response.response}. */
    public ObjectNode toResponsePayload() {
        return responsePayload;
    }
}
