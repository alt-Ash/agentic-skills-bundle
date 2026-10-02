package dev.dorrian.usagestore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageDbTest {

    private static UsageEvent event(String id, String session, String kind, String ts) {
        UsageEvent e = new UsageEvent();
        e.eventId = id;
        e.sessionId = session;
        e.event = kind;
        e.ts = ts;
        e.provider = "claude";
        e.user = "ash";
        e.project = "demo";
        e.cwd = "/work/demo";
        return e;
    }

    @Test
    void roundTripsEveryFieldIncludingGitDetails(@TempDir Path dir) {
        UsageEvent e = event("e1", "s1", "session_end", "2026-10-02T10:00:00Z");
        e.model = "claude-sonnet-5-5";
        e.inputTokens = 100;
        e.cachedTokens = 40;
        e.toolName = "Bash";
        e.durationMs = 1234L;
        e.stopHookActive = true;
        e.command = "ls";
        e.slashCommand = "/plan";
        e.guardRule = "force-push";
        e.outputTokens = 321;
        e.cacheReadTokens = 1000;
        e.cacheCreationTokens = 50;
        e.agentName = "tdd-engineer";
        e.skillName = "validation-loop";
        e.gitLinesAdded = 7;
        e.gitCommits = List.of(new GitCommitInfo("abc", "msg"));
        e.gitFilesAdded = List.of("a.txt");
        e.gitFilesModified = List.of("b.txt", "c.txt");

        try (UsageDb db = UsageDb.open(dir.resolve("u.db"))) {
            assertTrue(db.record(e));
            UsageEvent back = db.eventsForSession("s1").get(0);
            assertEquals("claude-sonnet-5-5", back.model);
            assertEquals(100, back.inputTokens);
            assertEquals(1234L, back.durationMs);
            assertEquals(Boolean.TRUE, back.stopHookActive);
            assertEquals("/plan", back.slashCommand);
            assertEquals("force-push", back.guardRule);
            assertEquals(321, back.outputTokens);
            assertEquals(1000, back.cacheReadTokens);
            assertEquals(50, back.cacheCreationTokens);
            assertEquals("tdd-engineer", back.agentName);
            assertEquals("validation-loop", back.skillName);
            assertEquals("/work/demo", back.cwd);
            assertEquals("abc", back.gitCommits.get(0).hash);
            assertEquals(List.of("a.txt"), back.gitFilesAdded);
            assertEquals(List.of("b.txt", "c.txt"), back.gitFilesModified);
            assertNull(back.gitFilesDeleted);
            assertNull(back.error);
            assertNull(back.lastMessageCharLength);
        }
    }

    @Test
    void duplicateEventIdIsIgnored(@TempDir Path dir) {
        try (UsageDb db = UsageDb.open(dir.resolve("u.db"))) {
            assertTrue(db.record(event("e1", "s1", "tool_use", "2026-10-02T10:00:00Z")));
            assertFalse(db.record(event("e1", "s1", "tool_use", "2026-10-02T10:00:00Z")));
            assertEquals(1, db.countEvents());
        }
    }

    @Test
    void assignsAnEventIdWhenMissing(@TempDir Path dir) {
        UsageEvent e = event(null, "s1", "tool_use", "2026-10-02T10:00:00Z");
        try (UsageDb db = UsageDb.open(dir.resolve("u.db"))) {
            db.record(e);
            assertNotNull(e.eventId);
        }
    }

    @Test
    void sessionKeepsGitStartCommitAndTracksStartAndEnd(@TempDir Path dir) {
        try (UsageDb db = UsageDb.open(dir.resolve("u.db"))) {
            UsageEvent start = event("e1", "s1", "session_start", "2026-10-02T10:00:00Z");
            start.gitStartCommit = "deadbeef";
            db.record(start);
            db.record(event("e2", "s1", "tool_use", "2026-10-02T10:05:00Z"));
            db.record(event("e3", "s1", "session_end", "2026-10-02T10:30:00Z"));

            UsageDb.SessionRow s = db.session("s1");
            assertEquals("deadbeef", s.gitStartCommit());
            assertEquals("2026-10-02T10:00:00Z", s.startedAt());
            assertEquals("2026-10-02T10:30:00Z", s.endedAt());
            assertEquals("demo", s.project());
            assertEquals("/work/demo", s.cwd());
        }
    }

    @Test
    void eventsWithoutASessionStillStoreButCreateNoSession(@TempDir Path dir) {
        try (UsageDb db = UsageDb.open(dir.resolve("u.db"))) {
            db.record(event("e1", null, "tool_use", "2026-10-02T10:00:00Z"));
            assertEquals(1, db.countEvents());
            assertNull(db.session(null));
        }
    }

    @Test
    void manyConcurrentWritersLoseNothing(@TempDir Path dir) throws Exception {
        // Each round races a fresh database's first-time open (WAL switch + migration), the
        // real-world case of several hooks starting together right after install.
        for (int round = 0; round < 15; round++) {
            raceWriters(dir.resolve("u" + round + ".db"), 20);
        }
    }

    private static void raceWriters(Path file, int writers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String id = "e" + i;
            futures.add(pool.submit(() -> {
                try (UsageDb db = UsageDb.open(file)) {
                    db.record(event(id, "s1", "tool_use", "2026-10-02T10:00:" + String.format("%02d", Integer.parseInt(id.substring(1))) + "Z"));
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        try (UsageDb db = UsageDb.open(file)) {
            assertEquals(writers, db.countEvents());
        }
    }

    @Test
    void aCorruptFileFailsOpenWithAStoreExceptionAndIsLeftAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("u.db");
        Files.writeString(file, "this is not a sqlite database, just text ".repeat(50));
        assertThrows(UsageStoreException.class, () -> UsageDb.open(file));
        assertTrue(Files.readString(file).startsWith("this is not"));
    }

    @Test
    void readOnlyOpenCannotWriteOrCreate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("u.db");
        assertThrows(UsageStoreException.class, () -> UsageDb.openReadOnly(file));
        try (UsageDb db = UsageDb.open(file)) {
            db.record(event("e1", "s1", "tool_use", "2026-10-02T10:00:00Z"));
        }
        try (UsageDb ro = UsageDb.openReadOnly(file)) {
            assertEquals(1, ro.countEvents());
            assertThrows(Exception.class, () -> {
                try (Statement s = ro.connection().createStatement()) {
                    s.execute("DELETE FROM events");
                }
            });
        }
    }

    @Test
    void pruneRemovesOldEventsAndOrphanedSessions(@TempDir Path dir) {
        try (UsageDb db = UsageDb.open(dir.resolve("u.db"))) {
            UsageEvent old = event("e1", "old", "tool_use", "2026-01-01T00:00:00Z");
            old.gitFilesAdded = List.of("x");
            db.record(old);
            db.record(event("e2", "new", "tool_use", "2026-10-01T00:00:00Z"));

            assertEquals(1, db.pruneBefore("2026-06-01T00:00:00Z"));
            assertEquals(1, db.countEvents());
            assertNull(db.session("old"));
            assertNotNull(db.session("new"));
        }
    }

    @Test
    void metaCounterIncrements(@TempDir Path dir) {
        try (UsageDb db = UsageDb.open(dir.resolve("u.db"))) {
            assertEquals(0, db.meta(UsageDb.DROPPED_EVENTS));
            db.incrementMeta(UsageDb.DROPPED_EVENTS);
            db.incrementMeta(UsageDb.DROPPED_EVENTS);
            assertEquals(2, db.meta(UsageDb.DROPPED_EVENTS));
        }
    }

    @Test
    void refusesADatabaseFromANewerSchema(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("u.db");
        try (UsageDb db = UsageDb.open(file); Statement s = db.connection().createStatement()) {
            s.execute("PRAGMA user_version = 99");
        }
        UsageStoreException ex = assertThrows(UsageStoreException.class, () -> UsageDb.open(file));
        assertTrue(ex.getMessage().contains("newer"));
    }

    @Test
    void reopeningAnUpToDateDatabaseKeepsItsData(@TempDir Path dir) {
        Path file = dir.resolve("u.db");
        try (UsageDb db = UsageDb.open(file)) {
            db.record(event("e1", "s1", "tool_use", "2026-10-02T10:00:00Z"));
        }
        try (UsageDb db = UsageDb.open(file)) {
            assertEquals(1, db.countEvents());
        }
    }

    @Test
    void upgradesAVersionOneDatabaseInPlaceKeepingItsRows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("v1.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            for (String ddl : SchemaV1.STATEMENTS) s.execute(ddl);
            s.execute("INSERT INTO events(event_id, ts, event, session_id) VALUES ('old', '2026-01-01T00:00:00Z', 'tool_use', 's1')");
            s.execute("PRAGMA user_version = 1");
        }

        try (UsageDb db = UsageDb.open(file)) {
            assertEquals(1, db.countEvents());
            UsageEvent old = db.eventsForSession("s1").get(0);
            assertNull(old.outputTokens);
            assertNull(old.skillName);

            UsageEvent fresh = event("new", "s1", "tool_use", "2026-10-02T10:00:00Z");
            fresh.skillName = "x";
            fresh.outputTokens = 5;
            db.record(fresh);
            assertEquals(2, db.countEvents());
        }
        try (UsageDb db = UsageDb.open(file); Statement s = db.connection().createStatement();
             var rs = s.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(UsageDb.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    @Test
    void manyConcurrentOpensOfAVersionOneDatabaseMigrateExactlyOnce(@TempDir Path dir) throws Exception {
        for (int round = 0; round < 8; round++) {
            Path file = dir.resolve("m" + round + ".db");
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
                for (String ddl : SchemaV1.STATEMENTS) s.execute(ddl);
                s.execute("PRAGMA user_version = 1");
            }
            raceWriters(file, 12);
        }
    }
}
