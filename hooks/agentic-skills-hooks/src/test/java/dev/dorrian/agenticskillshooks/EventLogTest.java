package dev.dorrian.agenticskillshooks;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLogTest {

    private String originalUserDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    private UsageEvent sampleEvent(String sessionId, String kind) {
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
    void recordEventWritesToAllFourFiles() {
        UsageEvent event = sampleEvent("session-1", "user_prompt");
        EventLog.recordEvent("user-prompt-submit", event);

        Path cwd = Path.of(System.getProperty("user.dir"));
        assertTrue(Files.exists(cwd.resolve("ai-usage-events.json")));
        assertTrue(Files.exists(cwd.resolve(".hooks-data/user-prompt-submit.json")));
        assertTrue(Files.exists(cwd.resolve("hooks-events.json")));
        assertTrue(Files.exists(cwd.resolve(".hooks-data/session-baseline.json")));
        assertNotNull(event.eventId);
    }

    @Test
    void appendSessionConsolidatedEventGroupsBySessionId() {
        EventLog.recordEvent("user-prompt-submit", sampleEvent("session-A", "user_prompt"));
        EventLog.recordEvent("stop", sampleEvent("session-A", "turn_stop"));
        EventLog.recordEvent("user-prompt-submit", sampleEvent("session-B", "user_prompt"));

        List<UsageEvent> groupA = EventLog.readSessionGroup("session-A");
        List<UsageEvent> groupB = EventLog.readSessionGroup("session-B");

        assertEquals(2, groupA.size());
        assertEquals(1, groupB.size());
    }

    @Test
    void sessionBaselinePreservesGitStartCommitAcrossUpdates() {
        UsageEvent start = sampleEvent("session-X", "session_start");
        start.gitStartCommit = "abc123";
        EventLog.recordEvent("session", start);

        UsageEvent midEvent = sampleEvent("session-X", "tool_use");
        EventLog.recordEvent("post-tool-use", midEvent);

        SessionBaselineStore.SessionBaseline baseline = SessionBaselineStore.read("session-X");
        assertNotNull(baseline);
        assertEquals("abc123", baseline.gitStartCommit);
    }

    @Test
    void evictsOldestSessionGroupsPastCap() {
        for (int i = 0; i < EventLog.MAX_SESSION_GROUPS + 1; i++) {
            EventLog.recordEvent("stop", sampleEvent("session-" + i, "turn_stop"));
        }
        // The very first session group should have been evicted.
        assertTrue(EventLog.readSessionGroup("session-0").isEmpty());
        // The most recent one should still be present.
        assertEquals(1, EventLog.readSessionGroup("session-" + EventLog.MAX_SESSION_GROUPS).size());
    }

    @Test
    void toleratesCorruptExistingFile() throws Exception {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Files.writeString(cwd.resolve("ai-usage-events.json"), "not valid json{{{");

        EventLog.recordEvent("stop", sampleEvent("session-Z", "turn_stop"));

        JsonNode parsed = JsonSupport.MAPPER.readTree(Files.readString(cwd.resolve("ai-usage-events.json")));
        assertTrue(parsed.isArray());
        assertEquals(1, parsed.size());
    }

    // ─── appendEvent (direct, not via recordEvent) ──────────────────────────

    @Test
    void appendEventReturnsTheFullPathToTheOutputFile() {
        Path result = EventLog.appendEvent(sampleEvent("session-1", "session_start"));
        assertEquals(Path.of(System.getProperty("user.dir"), "ai-usage-events.json"), result);
    }

    @Test
    void appendEventCreatesANewFileWithAOneElementArrayWhenNoneExists() throws Exception {
        Path path = EventLog.appendEvent(sampleEvent("session-1", "session_start"));
        JsonNode contents = JsonSupport.MAPPER.readTree(Files.readString(path));
        assertEquals(1, contents.size());
        assertEquals("session_start", contents.get(0).get("event").asText());
    }

    // ─── appendHookTypeData ──────────────────────────────────────────────────

    @Test
    void appendHookTypeDataCreatesFileWithOneElementArrayWhenMissing() throws Exception {
        Path path = EventLog.appendHookTypeData("session", sampleEvent("session-1", "session_start"));
        Path expected = Path.of(System.getProperty("user.dir"), ".hooks-data", "session.json");
        assertEquals(expected, path);
        JsonNode contents = JsonSupport.MAPPER.readTree(Files.readString(path));
        assertEquals(1, contents.size());
    }

    @Test
    void appendHookTypeDataAppendsSubsequentEventsForSameHookType() throws Exception {
        EventLog.appendHookTypeData("session", sampleEvent("session-1", "session_start"));
        Path path = EventLog.appendHookTypeData("session", sampleEvent("session-1", "session_end"));
        JsonNode contents = JsonSupport.MAPPER.readTree(Files.readString(path));
        assertEquals(2, contents.size());
        assertEquals("session_end", contents.get(1).get("event").asText());
    }

    @Test
    void appendHookTypeDataKeepsSeparateHookTypesInSeparateFiles() throws Exception {
        EventLog.appendHookTypeData("session", sampleEvent("session-1", "session_start"));
        EventLog.appendHookTypeData("stop", sampleEvent("session-1", "turn_stop"));
        Path cwd = Path.of(System.getProperty("user.dir"));
        JsonNode sessionContents = JsonSupport.MAPPER.readTree(
                Files.readString(cwd.resolve(".hooks-data/session.json")));
        JsonNode stopContents = JsonSupport.MAPPER.readTree(
                Files.readString(cwd.resolve(".hooks-data/stop.json")));
        assertEquals(1, sessionContents.size());
        assertEquals(1, stopContents.size());
    }

    @Test
    void appendHookTypeDataToleratesCorruptFileAndRecovers() throws Exception {
        Path dir = Files.createDirectories(Path.of(System.getProperty("user.dir"), ".hooks-data"));
        Files.writeString(dir.resolve("session.json"), "NOT VALID JSON");
        Path path = EventLog.appendHookTypeData("session", sampleEvent("session-1", "session_start"));
        JsonNode contents = JsonSupport.MAPPER.readTree(Files.readString(path));
        assertEquals(1, contents.size());
    }

    // ─── appendSessionConsolidatedEvent ──────────────────────────────────────

    @Test
    void appendSessionConsolidatedEventFallsBackToUnknownWhenSessionIdIsNull() throws Exception {
        Path path = EventLog.appendSessionConsolidatedEvent(sampleEvent(null, "session_start"));
        JsonNode contents = JsonSupport.MAPPER.readTree(Files.readString(path));
        assertEquals("unknown", contents.get(0).get("sessionId").asText());
    }

    @Test
    void appendSessionConsolidatedEventCreatesASecondGroupForADifferentSessionId() throws Exception {
        Path path = EventLog.appendSessionConsolidatedEvent(sampleEvent("sess-1", "session_start"));
        EventLog.appendSessionConsolidatedEvent(sampleEvent("sess-2", "session_start"));
        JsonNode contents = JsonSupport.MAPPER.readTree(Files.readString(path));
        assertEquals(2, contents.size());
        assertEquals("sess-1", contents.get(0).get("sessionId").asText());
        assertEquals("sess-2", contents.get(1).get("sessionId").asText());
    }

    @Test
    void appendSessionConsolidatedEventDoesNotEvictWhenAppendingToAnExistingSessionAtTheCap() {
        for (int i = 0; i < EventLog.MAX_SESSION_GROUPS; i++) {
            EventLog.appendSessionConsolidatedEvent(sampleEvent("sess-" + i, "session_start"));
        }
        // One more event for an already-existing session — group count stays at the cap.
        EventLog.appendSessionConsolidatedEvent(sampleEvent("sess-0", "user_prompt"));
        assertEquals(2, EventLog.readSessionGroup("sess-0").size());
        // Every original group is still present — none evicted.
        for (int i = 1; i < EventLog.MAX_SESSION_GROUPS; i++) {
            assertEquals(1, EventLog.readSessionGroup("sess-" + i).size());
        }
    }

    // ─── readSessionGroup ────────────────────────────────────────────────────

    @Test
    void readSessionGroupReturnsEmptyWhenSessionIdIsNull() {
        assertTrue(EventLog.readSessionGroup(null).isEmpty());
    }

    @Test
    void readSessionGroupReturnsEmptyWhenNoConsolidatedFileExistsYet() {
        assertTrue(EventLog.readSessionGroup("sess-1").isEmpty());
    }
}
