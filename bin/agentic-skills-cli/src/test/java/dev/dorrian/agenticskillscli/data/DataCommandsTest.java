package dev.dorrian.agenticskillscli.data;

import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataCommandsTest {

    private static final String LEGACY = """
        [
          { "eventId": "id-1", "ts": "2026-10-01T10:00:00Z", "event": "session_start", "sessionId": "s1",
            "provider": "claude", "user": "ash", "project": "demo", "gitStartCommit": "abc" },
          { "ts": "2026-10-01T10:01:00Z", "event": "tool_use", "sessionId": "s1", "toolName": "Bash",
            "command": "ls", "someFutureField": 1 },
          { "ts": "2026-10-01T10:02:00Z", "event": "tool_failure", "sessionId": "s1", "toolName": "Bash",
            "error": "Exit code 1\\n--- quoted file ---\\ntoken=abc123def456 and lots more text" },
          { "event": "tool_use", "sessionId": "s1" }
        ]
        """;

    private record Out(int code, String out, String err) {
    }

    private static Out run(List<String> args, Path db, Path cwd) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = DataCommands.run(args, db, cwd, new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Out(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path project(Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("proj"));
        Files.writeString(dir.resolve("ai-usage-events.json"), LEGACY);
        return dir;
    }

    @Test
    void pathPrintsTheDatabaseLocation(@TempDir Path tmp) {
        Out r = run(List.of("path"), tmp.resolve("u.db"), tmp);
        assertEquals(0, r.code());
        assertEquals(tmp.resolve("u.db").toString(), r.out().trim());
    }

    @Test
    void importLoadsLegacyEventsSkipsBrokenOnesAndLeavesTheSourceAlone(@TempDir Path tmp) throws Exception {
        Path proj = project(tmp);
        String before = Files.readString(proj.resolve("ai-usage-events.json"));
        Path db = tmp.resolve("u.db");

        Out r = run(List.of("import", "proj"), db, tmp);

        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("3 imported, 0 already present, 1 skipped"), r.out());
        assertEquals(before, Files.readString(proj.resolve("ai-usage-events.json")));
        try (UsageDb u = UsageDb.open(db)) {
            List<UsageEvent> events = u.eventsForSession("s1");
            assertEquals(3, events.size());
            assertEquals(proj.toAbsolutePath().normalize().toString(), events.get(0).cwd);
            assertEquals("abc", u.session("s1").gitStartCommit());
            assertNotNull(events.get(1).eventId);
            assertEquals("ls", events.get(1).command);
            String error = events.get(2).error;
            assertTrue(error.startsWith("Exit code 1 --- quoted file ---"), error);
            assertTrue(error.contains("[REDACTED]") && !error.contains("abc123def456"), error);
        }
    }

    @Test
    void importingTwiceAddsNothing(@TempDir Path tmp) throws Exception {
        project(tmp);
        Path db = tmp.resolve("u.db");

        run(List.of("import", "proj"), db, tmp);
        Out second = run(List.of("import", "proj"), db, tmp);

        assertTrue(second.out().contains("0 imported, 3 already present"), second.out());
        try (UsageDb u = UsageDb.open(db)) {
            assertEquals(3, u.countEvents());
        }
    }

    @Test
    void importDefaultsToTheCurrentDirectory(@TempDir Path tmp) throws Exception {
        Path proj = project(tmp);
        Out r = run(List.of("import"), tmp.resolve("u.db"), proj);
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("3 imported"));
    }

    @Test
    void importReportsAMissingOrInvalidFileAsAFailure(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("empty"));
        assertEquals(1, run(List.of("import", "empty"), tmp.resolve("u.db"), tmp).code());

        Path bad = Files.createDirectories(tmp.resolve("bad"));
        Files.writeString(bad.resolve("ai-usage-events.json"), "not json {{{");
        Out r = run(List.of("import", "bad"), tmp.resolve("u.db"), tmp);
        assertEquals(1, r.code());
        assertTrue(r.err().contains("Not valid JSON"));
    }

    @Test
    void pruneDeletesOnlyEventsOlderThanTheCutoff(@TempDir Path tmp) {
        Path db = tmp.resolve("u.db");
        try (UsageDb u = UsageDb.open(db)) {
            UsageEvent old = new UsageEvent();
            old.eventId = "old";
            old.ts = Instant.now().minus(200, ChronoUnit.DAYS).toString();
            old.event = "tool_use";
            old.sessionId = "s-old";
            u.record(old);
            UsageEvent fresh = new UsageEvent();
            fresh.eventId = "fresh";
            fresh.ts = Instant.now().toString();
            fresh.event = "tool_use";
            fresh.sessionId = "s-new";
            u.record(fresh);
        }

        Out r = run(List.of("prune", "--older-than", "90d"), db, tmp);

        assertEquals(0, r.code());
        assertTrue(r.out().contains("Deleted 1 event"));
        try (UsageDb u = UsageDb.open(db)) {
            assertEquals(1, u.countEvents());
        }
    }

    @Test
    void pruneValidatesItsArgumentAndToleratesNoDatabase(@TempDir Path tmp) {
        assertEquals(2, run(List.of("prune"), tmp.resolve("u.db"), tmp).code());
        assertEquals(2, run(List.of("prune", "--older-than", "soon"), tmp.resolve("u.db"), tmp).code());
        assertEquals(0, run(List.of("prune", "--older-than", "30d"), tmp.resolve("missing.db"), tmp).code());
    }

    @Test
    void unknownSubcommandPrintsUsage(@TempDir Path tmp) {
        Out r = run(List.of("frobnicate"), tmp.resolve("u.db"), tmp);
        assertEquals(2, r.code());
        assertTrue(r.err().contains("Usage"));
    }
}
