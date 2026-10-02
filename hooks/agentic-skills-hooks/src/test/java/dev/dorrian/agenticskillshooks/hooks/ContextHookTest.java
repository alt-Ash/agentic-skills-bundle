package dev.dorrian.agenticskillshooks.hooks;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextHookTest {

    private static final Path P = Path.of("/work/myproj");

    @Test
    void includesGitFactsAndNotes() {
        String s = ContextHook.build(P, "main", "Fix bug", " M a\n?? b\n", "Remember X", "startup");
        assertTrue(s.contains("Project: myproj"));
        assertTrue(s.contains("Git branch: main"));
        assertTrue(s.contains("Last commit: Fix bug"));
        assertTrue(s.contains("Uncommitted changes: 2"));
        assertTrue(s.contains("Remember X"));
        assertFalse(s.contains("compacted"));
    }

    @Test
    void compactAddsReminder() {
        assertTrue(ContextHook.build(P, null, null, null, null, "compact").contains("just compacted"));
    }

    @Test
    void noGitOmitsGitLines() {
        String s = ContextHook.build(P, null, null, null, null, "startup");
        assertFalse(s.contains("Git branch"));
        assertFalse(s.contains("Uncommitted"));
    }

    @Test
    void cappedAndRedacted() {
        String s = ContextHook.build(P, "main", "x", "", "y".repeat(10000), "startup");
        assertTrue(s.length() <= ContextHook.MAX_CHARS);
        String secret = ContextHook.build(P, "main", "x", "", "token=ghp_abcdefghijklmnopqrstuvwxyz0123456789", "startup");
        assertFalse(secret.contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
        assertEquals(0, ContextHook.build(P, "m", "s", "", null, null).lines().filter(l -> l.contains("Notes")).count());
    }
}
