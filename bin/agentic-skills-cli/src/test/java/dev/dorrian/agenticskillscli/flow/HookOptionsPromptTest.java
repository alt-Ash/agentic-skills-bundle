package dev.dorrian.agenticskillscli.flow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookOptionsPromptTest {

    @Test
    void claudeGetsAllThreeQuestions() {
        HookOptionsPrompt.Applicable a = HookOptionsPrompt.applicable(List.of("claude"));
        assertTrue(a.guard());
        assertTrue(a.verifyAndContext());
    }

    @Test
    void openCodeOnlyGetsTheGuardQuestionBecauseTheOthersDoNothingThere() {
        HookOptionsPrompt.Applicable a = HookOptionsPrompt.applicable(List.of("opencode"));
        assertTrue(a.guard());
        assertFalse(a.verifyAndContext());
    }

    @Test
    void toolsWithoutHookSupportGetNoQuestionsAtAll() {
        assertFalse(HookOptionsPrompt.applicable(List.of("windsurf")).any());
        assertFalse(HookOptionsPrompt.applicable(List.of("cursor", "codex", "windsurf")).any());
        assertEquals(new HookOptionsPrompt.Applicable(false, false), HookOptionsPrompt.applicable(List.of()));
    }

    @Test
    void aMixedSelectionAsksTheUnionOfQuestions() {
        HookOptionsPrompt.Applicable a = HookOptionsPrompt.applicable(List.of("windsurf", "opencode", "cursor"));
        assertTrue(a.guard());
        assertFalse(a.verifyAndContext());
        assertTrue(HookOptionsPrompt.applicable(List.of("windsurf", "claude")).verifyAndContext());
    }

    @Test
    void antigravityGetsAllThreeQuestions() {
        HookOptionsPrompt.Applicable a = HookOptionsPrompt.applicable(List.of("antigravity"));
        assertTrue(a.guard());
        assertTrue(a.verifyAndContext());
    }
}
