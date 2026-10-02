package dev.dorrian.agenticskillshooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderDetectorTest {

    @Test
    void defaultsToClaudeWhenNoDistinguishingFields() {
        HookInput input = HookInput.parse("{}");
        assertEquals("claude", ProviderDetector.detect(input));
    }

    @Test
    void detectsGeminiByEventName() {
        HookInput input = HookInput.parse("{\"hook_event_name\":\"AfterTool\"}");
        assertEquals("gemini", ProviderDetector.detect(input));
    }

    @Test
    void detectsCursorWhenModelAndUserEmailKeyPresentEvenIfNull() {
        HookInput input = HookInput.parse("{\"model\":\"gpt-4\",\"user_email\":null}");
        assertEquals("cursor", ProviderDetector.detect(input));
    }

    @Test
    void detectsCursorWhenModelAndConversationIdTruthy() {
        HookInput input = HookInput.parse("{\"model\":\"gpt-4\",\"conversation_id\":\"abc\"}");
        assertEquals("cursor", ProviderDetector.detect(input));
    }

    @Test
    void detectsCodexWhenOnlyModelPresent() {
        HookInput input = HookInput.parse("{\"model\":\"codex-mini\"}");
        assertEquals("codex", ProviderDetector.detect(input));
    }

    @Test
    void modelPresentButUserEmailKeyAbsentIsCodexNotCursor() {
        HookInput input = HookInput.parse("{\"model\":\"gpt-4\"}");
        assertEquals("codex", ProviderDetector.detect(input));
    }

    @Test
    void detectsCursorWhenModelAndUserEmailStringArePresent() {
        HookInput input = HookInput.parse("{\"model\":\"gpt-4\",\"user_email\":\"a@b.com\"}");
        assertEquals("cursor", ProviderDetector.detect(input));
    }

    @Test
    void hookEventNameWinsOverModelForGeminiDetection() {
        HookInput input = HookInput.parse("{\"hook_event_name\":\"AfterTool\",\"model\":\"gemini-pro\"}");
        assertEquals("gemini", ProviderDetector.detect(input));
    }

    @Test
    void detectsGeminiByBeforeAndAfterAgentEvents() {
        assertEquals("gemini", ProviderDetector.detect(HookInput.parse("{\"hook_event_name\":\"BeforeAgent\"}")));
        assertEquals("gemini", ProviderDetector.detect(HookInput.parse("{\"hook_event_name\":\"AfterAgent\"}")));
    }

    @Test
    void geminiSessionEventsAreRecognisedByTimestampKey() {
        assertEquals("gemini", ProviderDetector.detect(HookInput.parse(
            "{\"hook_event_name\":\"SessionStart\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"source\":\"startup\"}")));
        assertEquals("gemini", ProviderDetector.detect(HookInput.parse(
            "{\"hook_event_name\":\"SessionEnd\",\"timestamp\":\"2026-01-01T00:00:00Z\"}")));
    }

    @Test
    void claudeSessionEventsWithoutTimestampStayClaude() {
        assertEquals("claude", ProviderDetector.detect(HookInput.parse("{\"hook_event_name\":\"SessionStart\",\"source\":\"startup\"}")));
    }
}
