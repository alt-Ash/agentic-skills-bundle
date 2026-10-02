package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLogTest {

    private Path dbFile;
    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        dbFile = tempDir.resolve("usage.db");
        EventLog.useDatabaseForTesting(dbFile);
    }

    @AfterEach
    void tearDown() {
        EventLog.resetForTesting();
        System.setProperty("user.dir", originalUserDir);
    }

    private static UsageEvent sampleEvent(String sessionId, String kind) {
        UsageEvent event = new UsageEvent();
        event.ts = "2024-01-01T00:00:00.000Z";
        event.event = kind;
        event.sessionId = sessionId;
        event.provider = "claude";
        event.user = "alice";
        event.project = "demo";
        return event;
    }

    @Test
    void recordEventStoresARowAndFillsInIdTimestampAndCwd() {
        UsageEvent event = sampleEvent("session-1", "user_prompt");
        event.ts = null;
        EventLog.recordEvent(event);

        assertNotNull(event.eventId);
        List<UsageEvent> stored = EventLog.sessionEvents("session-1");
        assertEquals(1, stored.size());
        assertEquals(event.eventId, stored.get(0).eventId);
        assertEquals(System.getProperty("user.dir"), stored.get(0).cwd);
        assertNotNull(stored.get(0).ts);
    }

    @Test
    void recordEventWritesNoJsonFilesInTheWorkingDirectory() throws Exception {
        EventLog.recordEvent(sampleEvent("session-1", "user_prompt"));

        Path cwd = Path.of(System.getProperty("user.dir"));
        assertFalse(Files.exists(cwd.resolve("ai-usage-events.json")));
        assertFalse(Files.exists(cwd.resolve("hooks-events.json")));
        assertFalse(Files.exists(cwd.resolve(".hooks-data")));
    }

    @Test
    void eventsAreGroupedBySessionAndKeepTimeOrder() {
        UsageEvent a1 = sampleEvent("session-A", "session_start");
        UsageEvent b1 = sampleEvent("session-B", "session_start");
        UsageEvent a2 = sampleEvent("session-A", "user_prompt");
        a2.ts = "2024-01-01T00:00:05.000Z";
        EventLog.recordEvent(a1);
        EventLog.recordEvent(b1);
        EventLog.recordEvent(a2);

        List<UsageEvent> groupA = EventLog.sessionEvents("session-A");
        assertEquals(List.of("session_start", "user_prompt"), groupA.stream().map(e -> e.event).toList());
        assertEquals(1, EventLog.sessionEvents("session-B").size());
    }

    @Test
    void thereIsNoEvictionHistoryStaysComplete() {
        for (int i = 0; i < 120; i++) {
            EventLog.recordEvent(sampleEvent("session-" + i, "session_start"));
        }
        assertEquals(1, EventLog.sessionEvents("session-0").size());
        assertEquals(1, EventLog.sessionEvents("session-119").size());
    }

    @Test
    void sessionRowCarriesIdentityAndTheGitStartCommitForward() {
        UsageEvent start = sampleEvent("session-X", "session_start");
        start.client = "origin-client";
        start.gitStartCommit = "abc123";
        EventLog.recordEvent(start);
        EventLog.recordEvent(sampleEvent("session-X", "tool_use"));

        UsageDb.SessionRow row = EventLog.session("session-X");
        assertEquals("alice", row.user());
        assertEquals("demo", row.project());
        assertEquals("origin-client", row.client());
        assertEquals("abc123", row.gitStartCommit());
    }

    @Test
    void nullAndUnknownSessionsAreHandled() {
        assertNull(EventLog.session(null));
        assertNull(EventLog.session("nope"));
        assertTrue(EventLog.sessionEvents(null).isEmpty());
        assertTrue(EventLog.sessionEvents("nope").isEmpty());
    }

    @Test
    void anUnusableDatabaseNeverThrowsAndNeverBlocksTheHook() throws Exception {
        Files.writeString(dbFile, "not a sqlite database ".repeat(100));
        EventLog.useDatabaseForTesting(dbFile);

        assertDoesNotThrow(() -> EventLog.recordEvent(sampleEvent("session-1", "user_prompt")));
        assertNull(EventLog.session("session-1"));
        assertTrue(EventLog.sessionEvents("session-1").isEmpty());
        assertDoesNotThrow(() -> EventLog.bump("anything"));
    }

    @Test
    void bumpCountsInMeta() {
        EventLog.recordEvent(sampleEvent("session-1", "session_start"));
        EventLog.bump(EventLog.SESSION_END_WITHOUT_START);
        EventLog.bump(EventLog.SESSION_END_WITHOUT_START);
        EventLog.close();

        try (UsageDb db = UsageDb.open(dbFile)) {
            assertEquals(2, db.meta(EventLog.SESSION_END_WITHOUT_START));
        }
    }
}
