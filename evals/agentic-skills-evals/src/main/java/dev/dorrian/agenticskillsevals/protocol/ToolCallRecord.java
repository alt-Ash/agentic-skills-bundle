package dev.dorrian.agenticskillsevals.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** One tool call observed during a {@link ClaudeSession#query(String)}, sourced from live PreToolUse hook callbacks. */
public record ToolCallRecord(String toolName, JsonNode input, boolean blocked, String blockReason) {
}
