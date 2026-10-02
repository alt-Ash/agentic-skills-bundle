package dev.dorrian.agenticskillshooks.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dorrian.agenticskillshooks.JsonSupport;

import java.util.List;

/** Claude Code transcript: per-event JSONL with `type` and a nested `message`. */
public final class ClaudeTranscriptParser {

    private ClaudeTranscriptParser() {
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
            if (!"assistant".equals(textOrNull(record, "type"))) continue;
            JsonNode message = record.get("message");
            if (message == null || !message.isObject()) continue;

            String model = textOrNull(message, "model");
            if (model != null) out.model = model;

            JsonNode usage = message.get("usage");
            if (usage != null && usage.has("input_tokens") && usage.get("input_tokens").isNumber()) {
                out.inputTokens = usage.get("input_tokens").asInt();
                int cacheRead = numberOrZero(usage, "cache_read_input_tokens");
                int cacheCreate = numberOrZero(usage, "cache_creation_input_tokens");
                out.cachedTokens = cacheRead + cacheCreate;
            }
        }
        return out;
    }

    private static int numberOrZero(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isNumber()) ? n.asInt() : 0;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }
}
