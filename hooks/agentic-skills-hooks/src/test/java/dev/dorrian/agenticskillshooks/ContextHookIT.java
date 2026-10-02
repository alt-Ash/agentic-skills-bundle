package dev.dorrian.agenticskillshooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextHookIT {

    @TempDir
    Path tmp;

    @Test
    void printsProjectContextAndNotes() throws Exception {
        Path p = Files.createDirectory(tmp.resolve("ctxproj"));
        Files.createDirectories(p.resolve(".agentic-skills"));
        Files.writeString(p.resolve(".agentic-skills/context.md"), "Use bun, not npm.");
        var r = HookJarHarness.run(p, "context", Map.of("session_id", "s1", "cwd", p.toString(),
            "hook_event_name", "SessionStart", "source", "compact"));
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("Project: ctxproj"), r.stdout());
        assertTrue(r.stdout().contains("Use bun, not npm."));
        assertTrue(r.stdout().contains("just compacted"));
        assertTrue(r.stderr().isBlank(), r.stderr());
    }

    @Test
    void startupHasNoCompactLine() throws Exception {
        Path p = Files.createDirectory(tmp.resolve("ctxproj2"));
        var r = HookJarHarness.run(p, "context", Map.of("session_id", "s1", "cwd", p.toString(),
            "hook_event_name", "SessionStart", "source", "startup"));
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("Project: ctxproj2"));
        assertFalse(r.stdout().contains("compacted"));
    }
}
