package dev.dorrian.agenticskillsevals.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Accumulated result of one {@link ClaudeSession#query(String)} call. */
public record ChatResult(
        String text,
        List<ToolCallRecord> toolCalls,
        Map<String, Integer> toolCallCounts,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreationTokens,
        double totalCostUsd,
        String sessionId,
        List<JsonNode> transcript
) {
}
