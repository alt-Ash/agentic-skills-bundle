package dev.dorrian.agenticskillshooks;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Java port of hooks/lib/event-log.ts's session-baseline.json cache (readSessionBaseline /
 * updateSessionBaseline). A tiny, fixed-size-per-session record (unlike hooks-events.json's
 * per-session `hooks` array) that never grows with a session's activity — this is what keeps
 * resolveIdentity's cache and session_end's gitStartCommit lookup cheap for the whole duration
 * of even a very long-running session. Capped at MAX_ENTRIES sessions, oldest evicted first.
 */
public final class SessionBaselineStore {
    private static final String HOOKS_DATA_DIR = ".hooks-data";
    private static final String SESSION_BASELINE_FILE = "session-baseline.json";
    public static final int MAX_ENTRIES = 50;

    private SessionBaselineStore() {
    }

    public static final class SessionBaseline {
        public String sessionId;
        public String user;
        public String project;
        public String client;
        public String gitStartCommit;
    }

    private static Path baselinePath() {
        return Paths.get(System.getProperty("user.dir"), HOOKS_DATA_DIR, SESSION_BASELINE_FILE);
    }

    private static List<SessionBaseline> readAll() {
        try {
            Path path = baselinePath();
            if (!Files.exists(path)) return new ArrayList<>();
            JsonNode parsed = JsonSupport.MAPPER.readTree(Files.readString(path));
            List<SessionBaseline> list = new ArrayList<>();
            if (parsed != null && parsed.isArray()) {
                for (JsonNode n : parsed) {
                    list.add(JsonSupport.MAPPER.treeToValue(n, SessionBaseline.class));
                }
            }
            return list;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    public static SessionBaseline read(String sessionId) {
        if (sessionId == null) return null;
        for (SessionBaseline b : readAll()) {
            if (sessionId.equals(b.sessionId)) return b;
        }
        return null;
    }

    /** gitStartCommit is only ever present on session_start events; every other event kind
     * carries it forward from the existing baseline so a later update doesn't erase it. */
    public static void update(UsageEvent event) {
        if (event.sessionId == null) return;
        try {
            Path dir = Paths.get(System.getProperty("user.dir"), HOOKS_DATA_DIR);
            Files.createDirectories(dir);

            List<SessionBaseline> baselines = readAll();
            int idx = -1;
            for (int i = 0; i < baselines.size(); i++) {
                if (event.sessionId.equals(baselines.get(i).sessionId)) {
                    idx = i;
                    break;
                }
            }

            SessionBaseline next = new SessionBaseline();
            next.sessionId = event.sessionId;
            next.user = event.user;
            next.project = event.project != null ? event.project : "";
            next.client = event.client;
            next.gitStartCommit = event.gitStartCommit != null
                    ? event.gitStartCommit
                    : (idx >= 0 ? baselines.get(idx).gitStartCommit : null);

            if (idx >= 0) {
                baselines.set(idx, next);
            } else {
                baselines.add(next);
            }
            while (baselines.size() > MAX_ENTRIES) {
                baselines.remove(0);
            }

            String json = JsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(baselines);
            Files.writeString(baselinePath(), json);
        } catch (Exception ignored) {
            // never let telemetry break the host CLI
        }
    }
}
