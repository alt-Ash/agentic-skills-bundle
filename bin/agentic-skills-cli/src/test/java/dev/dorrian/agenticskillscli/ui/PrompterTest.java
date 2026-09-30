package dev.dorrian.agenticskillscli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Prompter}'s prompt methods ({@code list}/{@code checkbox}/{@code
 * input}/{@code password}/{@code confirm}) all block on real interactive
 * terminal input and cannot be meaningfully unit-tested without a live TTY —
 * that limitation is accepted here rather than faked with a mock reader that
 * would just test the mock. What IS unit-testable, and covered below, is the
 * pure index-parsing logic {@code list}/{@code checkbox} share, and that the
 * class constructs/tears down cleanly (JLine falls back to a "dumb" terminal
 * under a non-TTY test runner, which is sufficient to prove wiring, not
 * interaction).
 */
class PrompterTest {

    @Test
    void constructsAndClosesCleanlyUnderANonInteractiveTestRunner() {
        try (Prompter prompter = new Prompter()) {
            assertEquals(true, prompter != null);
        }
    }

    @Test
    void parseOneBasedIndexAcceptsInRangeValues() {
        assertEquals(0, Prompter.parseOneBasedIndex("1", 3));
        assertEquals(2, Prompter.parseOneBasedIndex("3", 3));
    }

    @Test
    void parseOneBasedIndexRejectsOutOfRangeValues() {
        assertEquals(-1, Prompter.parseOneBasedIndex("0", 3));
        assertEquals(-1, Prompter.parseOneBasedIndex("4", 3));
    }

    @Test
    void parseOneBasedIndexRejectsNonNumericInput() {
        assertEquals(-1, Prompter.parseOneBasedIndex("abc", 3));
        assertEquals(-1, Prompter.parseOneBasedIndex("", 3));
    }
}
