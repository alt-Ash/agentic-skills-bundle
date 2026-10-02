package dev.dorrian.agenticskillshooks.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dorrian.agenticskillshooks.JsonSupport;

import java.util.List;

/** Codex rollout JSONL: RolloutItems discriminated by `type`. */
public final class CodexTranscriptParser {

    private CodexTranscriptParser() {
    }

    public static final class Extracted {
        public String model;
        public Integer inputTokens;
        public Integer cachedTokens;
        public Integer outputTokens;
        public Integer cacheReadTokens;
        public Integer cacheCreationTokens;
    }

    public static Extracted extract(String path) {
        Extracted out = new Extracted();
        List<JsonNode> records;
        try {
            records = JsonSupport.readJsonlLines(path);
        } catch (Exception e) {
            return out;
        }

        for (JsonNode record : records) {
            if (record == null || !record.isObject()) continue;

            if ("TurnContext".equals(textOrNull(record, "type")) && textOrNull(record, "model") != null) {
                out.model = textOrNull(record, "model");
            }

            if ("TokenCount".equals(textOrNull(record, "type"))) {
                // TokenUsage may sit on the record or a nested `usage`/`info` object.
                JsonNode usage = record.has("usage") ? record.get("usage")
                        : record.has("info") ? record.get("info") : record;
                if (usage != null && usage.has("input_tokens") && usage.get("input_tokens").isNumber()) {
                    // Codex `input_tokens` already INCLUDES cached; do not sum.
                    out.inputTokens = usage.get("input_tokens").asInt();
                    out.cachedTokens = (usage.has("cached_input_tokens") && usage.get("cached_input_tokens").isNumber())
                            ? usage.get("cached_input_tokens").asInt()
                            : null;
                    out.cacheReadTokens = out.cachedTokens;
                    out.outputTokens = (usage.has("output_tokens") && usage.get("output_tokens").isNumber())
                            ? usage.get("output_tokens").asInt()
                            : null;
                }
            }
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }
}
