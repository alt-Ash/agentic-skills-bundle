package dev.dorrian.agenticskillshooks.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dorrian.agenticskillshooks.JsonSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini CLI transcript: Gemini-specific records (`type: "gemini" | "user"`), optionally
 * wrapped in a ConversationRecord with a `messages[]` array that must be flattened first.
 */
public final class GeminiTranscriptParser {

    private GeminiTranscriptParser() {
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

        List<JsonNode> flat = new ArrayList<>();
        for (JsonNode record : records) {
            if (record != null && record.isObject() && record.has("messages") && record.get("messages").isArray()) {
                String model = textOrNull(record, "model");
                if (model != null && out.model == null) out.model = model;
                for (JsonNode msg : record.get("messages")) flat.add(msg);
            } else {
                flat.add(record);
            }
        }

        for (JsonNode obj : flat) {
            if (obj == null || !obj.isObject()) continue;
            if (!"gemini".equals(textOrNull(obj, "type"))) continue;

            String model = textOrNull(obj, "model");
            if (model != null) out.model = model;

            JsonNode tokens = obj.get("tokens");
            if (tokens != null && tokens.has("input") && tokens.get("input").isNumber()) {
                out.inputTokens = tokens.get("input").asInt();
                out.cachedTokens = (tokens.has("cached") && tokens.get("cached").isNumber())
                        ? tokens.get("cached").asInt()
                        : null;
                // Gemini `cached` is cache-read; it has no cache-creation counter.
                out.cacheReadTokens = out.cachedTokens;
                out.outputTokens = (tokens.has("output") && tokens.get("output").isNumber())
                        ? tokens.get("output").asInt()
                        : null;
            }
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }
}
