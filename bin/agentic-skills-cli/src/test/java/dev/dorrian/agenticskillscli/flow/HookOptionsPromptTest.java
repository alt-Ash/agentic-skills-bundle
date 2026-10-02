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
    void geminiAndOtherToolsGetNoQuestionsAtAll() {
        assertFalse(HookOptionsPrompt.applicable(List.of("gemini")).any());
        assertFalse(HookOptionsPrompt.applicable(List.of("cursor", "codex", "gemini")).any());
        assertEquals(new HookOptionsPrompt.Applicable(false, false), HookOptionsPrompt.applicable(List.of()));
    }

    @Test
    void aMixedSelectionAsksTheUnionOfQuestions() {
        HookOptionsPrompt.Applicable a = HookOptionsPrompt.applicable(List.of("gemini", "opencode", "cursor"));
        assertTrue(a.guard());
        assertFalse(a.verifyAndContext());
        assertTrue(HookOptionsPrompt.applicable(List.of("gemini", "claude")).verifyAndContext());
    }

    @Test
    void antigravityGetsTheVerifyGateAndContextButNoGuard() {
        HookOptionsPrompt.Applicable a = HookOptionsPrompt.applicable(List.of("antigravity"));
        assertFalse(a.guard(), "Antigravity's PreToolUse has no 'no opinion' answer, so there is no guard for it");
        assertTrue(a.verifyAndContext());
        // alongside OpenCode the guard question is still asked, for OpenCode's sake
        assertTrue(HookOptionsPrompt.applicable(List.of("opencode", "antigravity")).guard());
    }
}
