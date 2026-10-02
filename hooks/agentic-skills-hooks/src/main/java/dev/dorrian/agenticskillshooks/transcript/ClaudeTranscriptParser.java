package dev.dorrian.agenticskillshooks.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dorrian.agenticskillshooks.JsonSupport;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Claude Code transcript: per-event JSONL with `type` and a nested `message`. */
public final class ClaudeTranscriptParser {

    /** Only the last this-many bytes of a transcript are read; the last assistant message is near the end. */
    public static final int TAIL_BYTES = 256 * 1024;

    private ClaudeTranscriptParser() {
    }

    public static final class Extracted {
        public String model;
        public Integer inputTokens;
        public Integer cachedTokens;
        public Integer outputTokens;
        public Integer cacheReadTokens;
        public Integer cacheCreationTokens;
        /** Output tokens summed over the distinct API messages since the last real user prompt; null if unknown. */
        public Integer turnOutputTokens;
    }

    private static final String INTERRUPT_MARKER = "[Request interrupted by user";

    public static Extracted extract(String path) {
        return extract(path, TAIL_BYTES);
    }

    static Extracted extract(String path, int tailBytes) {
        Extracted out = new Extracted();
        List<JsonNode> records;
        try {
            records = readTailRecords(path, tailBytes);
        } catch (Exception e) {
            return out;
        }

        for (JsonNode record : records) {
            if (record == null || !record.isObject()) continue;
            if (!"assistant".equals(textOrNull(record, "type"))) continue;
            JsonNode message = record.get("message");
            if (message == null || !message.isObject()) continue;

            String model = textOrNull(message, "model");
            // Claude Code writes placeholder messages (e.g. for an API error) with model "<synthetic>" and
            // zero usage; they are not model output and must not replace the real last message's numbers.
            if ("<synthetic>".equals(model)) continue;
            if (model != null) out.model = model;

            JsonNode usage = message.get("usage");
            if (usage != null && usage.isObject()) {
                Integer in = intOrNull(usage, "input_tokens");
                Integer outTok = intOrNull(usage, "output_tokens");
                Integer read = intOrNull(usage, "cache_read_input_tokens");
                Integer create = intOrNull(usage, "cache_creation_input_tokens");
                if (in == null && outTok == null && read == null && create == null) continue;
                // One message's usage replaces the previous one wholesale (never mix messages).
                out.inputTokens = in;
                out.outputTokens = outTok;
                out.cacheReadTokens = read;
                out.cacheCreationTokens = create;
                out.cachedTokens = (read == null && create == null) ? null
                        : (read == null ? 0 : read) + (create == null ? 0 : create);
            }
        }
        out.turnOutputTokens = turnOutputTokens(records, isTruncated(path, tailBytes));
        return out;
    }

    private static boolean isTruncated(String path, int tailBytes) {
        try {
            return new java.io.File(path).length() > tailBytes;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * One API response is written as several assistant records (one per content block) that share
     * {@code message.id} and repeat the same usage, so output tokens are summed per distinct id.
     * Returns null when the turn's start is not in the tail (it would be undercounted) or nothing was counted.
     */
    static Integer turnOutputTokens(List<JsonNode> records, boolean tailTruncated) {
        int start = -1;
        for (int i = records.size() - 1; i >= 0; i--) {
            if (isRealUserPrompt(records.get(i))) {
                start = i;
                break;
            }
        }
        if (start < 0 && tailTruncated) return null;
        Map<String, Integer> perMessage = new LinkedHashMap<>();
        for (int i = start + 1; i < records.size(); i++) {
            JsonNode record = records.get(i);
            if (!"assistant".equals(textOrNull(record, "type")) || bool(record, "isSidechain")) continue;
            JsonNode message = record.get("message");
            if (message == null || !message.isObject() || "<synthetic>".equals(textOrNull(message, "model"))) continue;
            JsonNode usage = message.get("usage");
            Integer tokens = usage != null && usage.isObject() ? intOrNull(usage, "output_tokens") : null;
            if (tokens == null) continue;
            String id = textOrNull(message, "id");
            if (id == null) id = "anon-" + i;
            perMessage.merge(id, tokens, Math::max);
        }
        return perMessage.isEmpty() ? null : perMessage.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** A user record typed by the person: not meta, not a sub-agent record, not tool results, not an interrupt marker. */
    private static boolean isRealUserPrompt(JsonNode record) {
        if (record == null || !"user".equals(textOrNull(record, "type"))) return false;
        if (bool(record, "isMeta") || bool(record, "isSidechain")) return false;
        JsonNode message = record.get("message");
        if (message == null) return false;
        JsonNode content = message.get("content");
        if (content == null) return false;
        if (content.isTextual()) return !content.asText().startsWith(INTERRUPT_MARKER);
        if (!content.isArray()) return false;
        for (JsonNode block : content) {
            if (!"text".equals(textOrNull(block, "type"))) continue;
            String text = textOrNull(block, "text");
            if (text != null && !text.startsWith(INTERRUPT_MARKER)) return true;
        }
        return false;
    }

    /**
     * Whether the turn before the current prompt was interrupted. Claude Code does not fire Stop for an
     * interrupt, so the marker record is the only trace. The newest prompt may or may not be written yet when
     * UserPromptSubmit fires, so the last two user/assistant records are checked.
     */
    public static boolean previousTurnInterrupted(String path) {
        try {
            List<JsonNode> records = readTailRecords(path, TAIL_BYTES);
            int seen = 0;
            for (int i = records.size() - 1; i >= 0 && seen < 2; i--) {
                JsonNode record = records.get(i);
                String type = textOrNull(record, "type");
                if (!"user".equals(type) && !"assistant".equals(type)) continue;
                seen++;
                if ("user".equals(type) && isInterruptMarker(record)) return true;
            }
        } catch (Exception ignored) {
            // fail open
        }
        return false;
    }

    private static boolean isInterruptMarker(JsonNode record) {
        JsonNode message = record.get("message");
        JsonNode content = message == null ? null : message.get("content");
        if (content == null) return false;
        if (content.isTextual()) return content.asText().startsWith(INTERRUPT_MARKER);
        if (!content.isArray()) return false;
        for (JsonNode block : content) {
            String text = textOrNull(block, "text");
            if (text != null && text.startsWith(INTERRUPT_MARKER)) return true;
        }
        return false;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && n.asBoolean(false);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isNumber()) ? n.asInt() : null;
    }

    /** Parses JSONL from the last {@code tailBytes} of the file, dropping a leading partial line. */
    static List<JsonNode> readTailRecords(String path, int tailBytes) throws Exception {
        byte[] buf;
        boolean partialStart;
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            long len = f.length();
            long start = Math.max(0, len - tailBytes);
            partialStart = start > 0;
            buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
        }
        int from = 0;
        if (partialStart) {
            while (from < buf.length && buf[from] != '\n') from++;
            from++; // skip the newline; the bytes before it were a cut-off line
        }
        List<JsonNode> records = new ArrayList<>();
        if (from >= buf.length) return records;
        String content = new String(buf, from, buf.length - from, StandardCharsets.UTF_8);
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            try {
                records.add(JsonSupport.MAPPER.readTree(line));
            } catch (Exception ignored) {
                // skip malformed lines
            }
        }
        return records;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }
}
