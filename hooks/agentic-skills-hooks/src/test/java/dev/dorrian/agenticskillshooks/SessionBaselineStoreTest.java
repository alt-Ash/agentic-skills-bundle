package dev.dorrian.agenticskillshooks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers hooks/tests/event-log.test.ts's `readSessionBaseline` describe block. */
class SessionBaselineStoreTest {

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

    private UsageEvent sessionStart(String sessionId, String gitStartCommit) {
        UsageEvent event = new UsageEvent();
        event.ts = "2026-06-23T10:00:00.000Z";
        event.event = "session_start";
        event.sessionId = sessionId;
        event.provider = "claude";
        event.user = "ashleigh";
        event.project = "/dev/project";
        event.gitStartCommit = gitStartCommit;
        return event;
    }

    @Test
    void returnsNullWhenSessionIdIsNull() {
        assertNull(SessionBaselineStore.read(null));
    }

    @Test
    void returnsNullWhenNoBaselineFileExistsYet() {
        assertNull(SessionBaselineStore.read("sess-1"));
    }

    @Test
    void returnsTheBaselineWrittenAtSessionStart() {
        EventLog.recordEvent("session", sessionStart("sess-1", "abc1234"));

        SessionBaselineStore.SessionBaseline baseline = SessionBaselineStore.read("sess-1");
        assertEquals("sess-1", baseline.sessionId);
        assertEquals("ashleigh", baseline.user);
        assertEquals("/dev/project", baseline.project);
        assertNull(baseline.client);
        assertEquals("abc1234", baseline.gitStartCommit);
    }

    @Test
    void capsAtMaxEntriesEvictingOldestFirst() {
        for (int i = 0; i <= SessionBaselineStore.MAX_ENTRIES; i++) {
            EventLog.recordEvent("session", sessionStart("sess-" + i, null));
        }
        assertNull(SessionBaselineStore.read("sess-0"));
        assertEquals("sess-" + SessionBaselineStore.MAX_ENTRIES,
                SessionBaselineStore.read("sess-" + SessionBaselineStore.MAX_ENTRIES).sessionId);
    }
}
