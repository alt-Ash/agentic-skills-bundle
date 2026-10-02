package dev.dorrian.agenticskillshooks.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dorrian.agenticskillshooks.JsonSupport;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
    }

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
        return out;
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
