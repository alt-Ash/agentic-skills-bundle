package dev.dorrian.agenticskillshooks.transcript;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PostToolUseTranscriptParserTest {

    private String fixture(String name) throws URISyntaxException {
        Path path = Paths.get(getClass().getClassLoader().getResource("fixtures/" + name).toURI());
        return path.toString();
    }

    @Test
    void parsesClaudeTranscript() throws Exception {
        ClaudeTranscriptParser.Extracted extracted = ClaudeTranscriptParser.extract(fixture("claude-transcript.jsonl"));
        assertEquals("claude-opus-4-5", extracted.model);
        assertEquals(150, extracted.inputTokens);
        // cachedTokens = cache_read_input_tokens (50) + cache_creation_input_tokens (10)
        assertEquals(60, extracted.cachedTokens);
    }

    @Test
    void parsesGeminiTranscript() throws Exception {
        GeminiTranscriptParser.Extracted extracted = GeminiTranscriptParser.extract(fixture("gemini-transcript.jsonl"));
        assertEquals("gemini-1.5-pro", extracted.model);
        assertEquals(200, extracted.inputTokens);
        assertEquals(30, extracted.cachedTokens);
    }

    @Test
    void parsesCodexRollout() throws Exception {
        CodexTranscriptParser.Extracted extracted = CodexTranscriptParser.extract(fixture("codex-rollout.jsonl"));
        assertEquals("codex-mini", extracted.model);
        // Codex input_tokens already includes cached — must not be summed with cached_input_tokens.
        assertEquals(300, extracted.inputTokens);
        assertEquals(50, extracted.cachedTokens);
    }

    @Test
    void claudeParserReturnsEmptyExtractedForMissingFile() {
        ClaudeTranscriptParser.Extracted extracted = ClaudeTranscriptParser.extract("/nonexistent/path.jsonl");
        assertNull(extracted.model);
        assertNull(extracted.inputTokens);
        assertNull(extracted.cachedTokens);
    }
}
