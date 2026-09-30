package dev.dorrian.agenticskillshooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers hooks/tests/event-log.test.ts's `extractSlashCommand` describe block. */
class SlashCommandExtractorTest {

    @Test
    void extractsTheCommandNameFromABareSlashCommandWithNoArgs() {
        assertEquals("/clear", SlashCommandExtractor.extract("/clear"));
    }

    @Test
    void extractsTheCommandNameWhenFollowedByArgs() {
        assertEquals("/code-review", SlashCommandExtractor.extract("/code-review the latest changes"));
    }

    @Test
    void doesNotCaptureTheArgsText() {
        String result = SlashCommandExtractor.extract("/plan find other files that should be included in the gitignore");
        assertEquals("/plan", result);
        assertFalse(result.contains("gitignore"));
    }

    @Test
    void handlesAHyphenatedCommandName() {
        assertEquals("/code-review", SlashCommandExtractor.extract("/code-review"));
    }

    @Test
    void doesNotMistakeAnAbsolutePathForASlashCommand() {
        assertNull(SlashCommandExtractor.extract("/Users/testuser/Development/Acme is broken"));
    }

    @Test
    void doesNotMistakeAPathWithNoTrailingTextForASlashCommand() {
        assertNull(SlashCommandExtractor.extract("/etc/hosts"));
    }

    @Test
    void returnsNullForAPlainTextPromptWithNoSlashCommand() {
        assertNull(SlashCommandExtractor.extract("just a normal message"));
    }

    @Test
    void returnsNullWhenPromptIsNull() {
        assertNull(SlashCommandExtractor.extract(null));
    }

    @Test
    void fallsBackToTheTaggedFormIfAPayloadEverDeliversItDirectly() {
        String prompt = "<command-message>code-review</command-message>\n<command-name>/code-review</command-name>";
        assertEquals("/code-review", SlashCommandExtractor.extract(prompt));
    }
}
