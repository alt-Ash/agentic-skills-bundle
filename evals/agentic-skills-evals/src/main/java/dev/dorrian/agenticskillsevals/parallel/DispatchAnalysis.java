package dev.dorrian.agenticskillsevals.parallel;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups the sub-agent spawns ({@code Agent}, aliased {@code Task}) in a stream-json transcript
 * by the assistant turn that issued them. Claude Code runs sub-agents concurrently only when the
 * model emits several spawn calls in the <em>same</em> turn; stream-json writes one event per
 * content block, so events sharing a {@code message.id} belong to one turn.
 */
public record DispatchAnalysis(List<List<JsonNode>> batches) {

    private static final Set<String> SPAWN_TOOLS = Set.of("Agent", "Task");

    public static DispatchAnalysis of(List<JsonNode> transcript) {
        Map<String, List<JsonNode>> byTurn = new LinkedHashMap<>();
        int anonymous = 0;
        for (JsonNode event : transcript) {
            if (!"assistant".equals(event.path("type").asText(""))) continue;
            JsonNode message = event.path("message");
            String turnId = message.path("id").asText("");
            if (turnId.isEmpty()) turnId = "anon-" + anonymous++;
            for (JsonNode block : message.path("content")) {
                if ("tool_use".equals(block.path("type").asText("")) && SPAWN_TOOLS.contains(block.path("name").asText(""))) {
                    byTurn.computeIfAbsent(turnId, k -> new ArrayList<>()).add(block.path("input"));
                }
            }
        }
        return new DispatchAnalysis(List.copyOf(byTurn.values()));
    }

    /** Spawn inputs of the turn that launched the most sub-agents (empty if none were spawned). */
    public List<JsonNode> largestBatch() {
        return batches.stream().max(Comparator.comparingInt(List::size)).orElse(List.of());
    }

    public boolean isParallel() {
        return largestBatch().size() >= 2;
    }
}
