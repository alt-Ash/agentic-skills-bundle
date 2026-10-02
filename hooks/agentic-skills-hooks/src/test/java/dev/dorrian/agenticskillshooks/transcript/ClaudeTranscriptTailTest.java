package dev.dorrian.agenticskillshooks.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeTranscriptTailTest {

    @TempDir
    Path tmp;

    private static String assistant(String model, String usage) {
        return "{\"type\":\"assistant\",\"message\":{\"model\":\"" + model + "\"" + (usage == null ? "" : ",\"usage\":" + usage) + "}}";
    }

    private Path write(String... lines) throws Exception {
        Path p = tmp.resolve("t.jsonl");
        Files.writeString(p, String.join("\n", lines) + "\n");
        return p;
    }

    @Test
    void extractsAllUsageFieldsAndKeepsCachedSum() throws Exception {
        Path p = write(assistant("m1",
            "{\"input_tokens\":150,\"output_tokens\":42,\"cache_read_input_tokens\":50,\"cache_creation_input_tokens\":10}"));
        var e = ClaudeTranscriptParser.extract(p.toString());
        assertEquals("m1", e.model);
        assertEquals(150, e.inputTokens);
        assertEquals(42, e.outputTokens);
        assertEquals(50, e.cacheReadTokens);
        assertEquals(10, e.cacheCreationTokens);
        assertEquals(60, e.cachedTokens);
    }

    @Test
    void aSyntheticPlaceholderMessageDoesNotReplaceTheRealLastMessage() throws Exception {
        // Claude Code writes "<synthetic>" assistant records (e.g. after an API error) with zero usage.
        Path p = write(
            assistant("real-model", "{\"input_tokens\":10,\"output_tokens\":99,\"cache_read_input_tokens\":500}"),
            assistant("<synthetic>", "{\"input_tokens\":0,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}"));
        var e = ClaudeTranscriptParser.extract(p.toString());
        assertEquals("real-model", e.model);
        assertEquals(10, e.inputTokens);
        assertEquals(99, e.outputTokens);
        assertEquals(500, e.cacheReadTokens);
    }

    @Test
    void missingFieldsStayNullNotZero() throws Exception {
        Path p = write(assistant("m1", "{\"input_tokens\":7}"));
        var e = ClaudeTranscriptParser.extract(p.toString());
        assertEquals(7, e.inputTokens);
        assertNull(e.outputTokens);
        assertNull(e.cacheReadTokens);
        assertNull(e.cacheCreationTokens);
        assertNull(e.cachedTokens);
    }

    @Test
    void missingUsageYieldsModelOnly() throws Exception {
        var e = ClaudeTranscriptParser.extract(write(assistant("m1", null)).toString());
        assertEquals("m1", e.model);
        assertNull(e.inputTokens);
        assertNull(e.outputTokens);
    }

    @Test
    void toleratesMalformedLinesAndUsesLastAssistant() throws Exception {
        Path p = write(assistant("m1", "{\"input_tokens\":1,\"output_tokens\":1}"),
            "{not json", "", assistant("m2", "{\"input_tokens\":2,\"output_tokens\":3}"), "}}}");
        var e = ClaudeTranscriptParser.extract(p.toString());
        assertEquals("m2", e.model);
        assertEquals(2, e.inputTokens);
        assertEquals(3, e.outputTokens);
    }

    @Test
    void largeTranscriptIsReadFromTailOnlyAndFast() throws Exception {
        String filler = "{\"type\":\"user\",\"message\":{\"content\":\"" + "x".repeat(900) + "\"}}";
        Path p = tmp.resolve("big.jsonl");
        try (var w = Files.newBufferedWriter(p)) {
            w.write(assistant("old", "{\"input_tokens\":1,\"output_tokens\":1}") + "\n");
            for (int i = 0; i < 12_000; i++) w.write(filler + "\n"); // ~11 MB
            w.write(assistant("new", "{\"input_tokens\":11,\"output_tokens\":22,\"cache_read_input_tokens\":33}") + "\n");
        }
        assertTrue(Files.size(p) > 10_000_000);
        long t0 = System.nanoTime();
        var e = ClaudeTranscriptParser.extract(p.toString());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("new", e.model);
        assertEquals(11, e.inputTokens);
        assertEquals(22, e.outputTokens);
        assertEquals(33, e.cacheReadTokens);
        assertTrue(ms < 1000, "tail read took " + ms + "ms");
    }

    @Test
    void onlyTheTailWindowIsRead() throws Exception {
        String filler = "{\"type\":\"user\",\"message\":{\"content\":\"" + "x".repeat(900) + "\"}}";
        Path p = tmp.resolve("only-old.jsonl");
        try (var w = Files.newBufferedWriter(p)) {
            w.write(assistant("old", "{\"input_tokens\":1,\"output_tokens\":1}") + "\n");
            for (int i = 0; i < 1_000; i++) w.write(filler + "\n");
        }
        // The only assistant message is outside the window, so nothing is found.
        var e = ClaudeTranscriptParser.extract(p.toString(), 4096);
        assertNull(e.model);
        assertNull(e.inputTokens);
    }

    @Test
    void partialFirstLineOfTailIsDiscarded() throws Exception {
        String last = assistant("new", "{\"input_tokens\":5,\"output_tokens\":6}");
        Path p = write(assistant("old", "{\"input_tokens\":1,\"output_tokens\":1}"), last);
        var e = ClaudeTranscriptParser.extract(p.toString(), last.length() + 10);
        assertEquals("new", e.model);
        assertEquals(6, e.outputTokens);
    }

    @Test
    void codexExposesOutputAndCacheRead() throws Exception {
        Path c = tmp.resolve("c.jsonl");
        Files.writeString(c, "{\"type\":\"TokenCount\",\"usage\":{\"input_tokens\":300,\"cached_input_tokens\":50,\"output_tokens\":12}}\n");
        var ce = CodexTranscriptParser.extract(c.toString());
        assertEquals(12, ce.outputTokens);
        assertEquals(50, ce.cacheReadTokens);
        assertEquals(50, ce.cachedTokens);
        assertNull(ce.cacheCreationTokens);
    }
}
