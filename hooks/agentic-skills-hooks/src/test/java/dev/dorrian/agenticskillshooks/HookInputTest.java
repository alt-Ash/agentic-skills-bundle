package dev.dorrian.agenticskillshooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers hooks/tests/event-log.test.ts's `parseInput` and `sessionId` describe blocks. */
class HookInputTest {

    // ─── parseInput (HookInput.parse) ───────────────────────────────────────

    @Test
    void parsesAValidJsonObject() {
        HookInput input = HookInput.parse("{\"session_id\":\"abc\"}");
        assertEquals("abc", input.sessionId());
    }

    @Test
    void treatsAJsonStringNotObjectAsEmptyInput() {
        HookInput input = HookInput.parse("\"hello\"");
        assertNull(input.sessionId());
        assertNull(input.model());
    }

    @Test
    void treatsMalformedJsonAsEmptyInput() {
        HookInput input = HookInput.parse("not json");
        assertNull(input.sessionId());
        assertTrue(input.backgroundTaskCount() == 0);
    }

    @Test
    void treatsEmptyStringAsEmptyInput() {
        HookInput input = HookInput.parse("");
        assertNull(input.sessionId());
    }

    // ─── sessionId ───────────────────────────────────────────────────────────

    @Test
    void sessionIdReturnsSessionIdWhenPresent() {
        assertEquals("abc", HookInput.parse("{\"session_id\":\"abc\"}").sessionId());
    }

    @Test
    void sessionIdReturnsConversationIdWhenSessionIdAbsent() {
        assertEquals("xyz", HookInput.parse("{\"conversation_id\":\"xyz\"}").sessionId());
    }

    @Test
    void sessionIdPrefersSessionIdOverConversationId() {
        assertEquals("abc", HookInput.parse("{\"session_id\":\"abc\",\"conversation_id\":\"xyz\"}").sessionId());
    }

    @Test
    void sessionIdReturnsNullForEmptyPayload() {
        assertNull(HookInput.parse("{}").sessionId());
    }

    // ─── extractBashCommand ───────────────────────────────────────────────────

    @Test
    void extractBashCommandExtractsTheCommandWhenToolNameIsBash() {
        HookInput input = HookInput.parse("{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git status\"}}");
        assertEquals("git status", input.extractBashCommand());
    }

    @Test
    void extractBashCommandReturnsNullForANonBashTool() {
        HookInput input = HookInput.parse("{\"tool_name\":\"Read\",\"tool_input\":{\"command\":\"not-actually-a-command\"}}");
        assertNull(input.extractBashCommand());
    }

    @Test
    void extractBashCommandReturnsNullWhenToolInputIsMissing() {
        HookInput input = HookInput.parse("{\"tool_name\":\"Bash\"}");
        assertNull(input.extractBashCommand());
    }

    @Test
    void extractBashCommandReturnsNullWhenCommandIsNotAString() {
        HookInput input = HookInput.parse("{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":123}}");
        assertNull(input.extractBashCommand());
    }

    @Test
    void extractBashCommandReturnsNullForAnEmptyPayload() {
        assertNull(HookInput.parse("{}").extractBashCommand());
    }

    @Test
    void extractBashCommandRedactsSecretsInTheExtractedCommand() {
        HookInput input = HookInput.parse(
                "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"export GITHUB_TOKEN=ghp_1234567890abcdef1234567890abcdef1234\"}}");
        String result = input.extractBashCommand();
        assertFalse(result.contains("ghp_1234567890abcdef1234567890abcdef1234"));
    }
}
