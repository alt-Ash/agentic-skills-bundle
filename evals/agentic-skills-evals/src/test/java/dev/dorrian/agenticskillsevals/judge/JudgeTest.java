package dev.dorrian.agenticskillsevals.judge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests of {@link Judge#parse}'s JSON-extraction/scoring logic - zero real API calls.
 * Port of the parsing-relevant assertions implicit in {@code evals/behavioral/lib/judge.ts}
 * (there was no dedicated judge.test.ts in the original; this covers the bracket-slice fallback
 * and control-character sanitization the plan calls out as edge cases worth testing explicitly).
 */
class JudgeTest {

    @Test
    void parsesResultTagWrappedJson() {
        String raw = "<result>{\"reasoning\":\"good\",\"dimensions\":{\"structure\":4,\"clarity\":5},\"rationale\":\"solid\"}</result>";
        Judge.Result result = Judge.parse(raw, 3);
        assertEquals(4.5, result.score());
        assertEquals("solid", result.rationale());
        assertTrue(result.passed());
    }

    @Test
    void fallsBackToBracketSliceWhenNoResultTag() {
        String raw = "Here is my assessment: {\"dimensions\":{\"a\":2,\"b\":2},\"rationale\":\"weak\"} - hope that helps";
        Judge.Result result = Judge.parse(raw, 3);
        assertEquals(2.0, result.score());
        assertFalse(result.passed());
    }

    @Test
    void sanitizesControlCharactersInsideStringLiterals() {
        String raw = "<result>{\"reasoning\":\"line one\nline two\ttabbed\",\"dimensions\":{\"x\":5},\"rationale\":\"ok\"}</result>";
        Judge.Result result = Judge.parse(raw, 3);
        assertEquals(5.0, result.score());
        assertTrue(result.passed());
    }

    @Test
    void averagesMultipleDimensionsAndRoundsToOneDecimal() {
        String raw = "<result>{\"dimensions\":{\"a\":3,\"b\":4,\"c\":5},\"rationale\":\"\"}</result>";
        Judge.Result result = Judge.parse(raw, 3);
        assertEquals(4.0, result.score());
    }

    @Test
    void passedIsFalseWhenAverageBelowThreshold() {
        String raw = "<result>{\"dimensions\":{\"a\":2,\"b\":2},\"rationale\":\"\"}</result>";
        Judge.Result result = Judge.parse(raw, 3);
        assertFalse(result.passed());
    }

    @Test
    void throwsWhenNoParseableJsonFound() {
        assertThrows(IllegalStateException.class, () -> Judge.parse("no json anywhere in this reply", 3));
    }

    @Test
    void throwsOnMalformedJson() {
        assertThrows(IllegalStateException.class,
                () -> Judge.parse("<result>{\"dimensions\": {unterminated</result>", 3));
    }
}
