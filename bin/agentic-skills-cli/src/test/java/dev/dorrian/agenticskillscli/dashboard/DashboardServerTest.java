package dev.dorrian.agenticskillscli.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.usagestore.GitCommitInfo;
import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private DashboardServer server;

    private static UsageEvent ev(String id, String session, String kind, String ts, String project) {
        UsageEvent e = new UsageEvent();
        e.eventId = id;
        e.sessionId = session;
        e.event = kind;
        e.ts = ts;
        e.project = project;
        e.user = "ash";
        return e;
    }

    @BeforeEach
    void seed(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("u.db");
        try (UsageDb u = UsageDb.open(db)) {
            u.record(ev("a1", "s1", "session_start", "2026-10-01T09:00:00Z", "alpha"));
            u.record(ev("a2", "s1", "user_prompt", "2026-10-01T09:01:00Z", "alpha"));
            u.record(withSlash(ev("a3", "s1", "user_prompt", "2026-10-01T09:02:00Z", "alpha"), "/plan"));
            UsageEvent again = withSlash(ev("a4", "s1", "user_prompt", "2026-10-01T09:03:00Z", "alpha"), "/plan");
            again.command = "ignored-but-different";
            u.record(again);
            for (int i = 0; i < 25; i++) {
                UsageEvent call = ev("c" + i, "s1", "tool_use", "2026-10-01T09:1" + (i % 10) + ":0" + (i % 6) + "Z", "alpha");
                call.toolName = "Bash";
                call.command = i % 2 == 0 ? "git status" : "git log -1";
                call.model = "claude-sonnet-5-5";
                call.inputTokens = 2;
                call.cachedTokens = 60_000 + i;
                u.record(call);
            }
            for (int i = 0; i < 10; i++) {
                UsageEvent fail = ev("f" + i, "s1", "tool_failure", "2026-10-01T09:30:0" + i + "Z", "alpha");
                fail.toolName = "Bash";
                fail.command = "false";
                fail.error = "exit status 1";
                u.record(fail);
            }
            UsageEvent block = ev("g1", "s1", "guard_block", "2026-10-01T09:40:00Z", "alpha");
            block.toolName = "Bash";
            block.guardRule = "force-push to main/master";
            block.command = "git push -f origin main";
            u.record(block);
            UsageEvent end = ev("a9", "s1", "session_end", "2026-10-01T10:00:00Z", "alpha");
            end.gitLinesAdded = 40;
            end.gitLinesDeleted = 5;
            end.gitFilesModified = List.of("src/Main.java");
            end.gitCommits = List.of(new GitCommitInfo("abc", "msg"));
            u.record(end);

            UsageEvent other = ev("b1", "s2", "tool_use", "2026-10-03T09:00:00Z", "beta");
            other.toolName = "Read";
            other.model = "claude-opus-5-5";
            other.inputTokens = 2;
            other.cachedTokens = 200_000;
            u.record(other);
            u.incrementMeta(UsageDb.DROPPED_EVENTS);
        }
        server = DashboardServer.start(db, 0);
    }

    private static UsageEvent withSlash(UsageEvent e, String cmd) {
        e.slashCommand = cmd;
        return e;
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String path) throws Exception {
        HttpResponse<String> r = get(path);
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        return JSON.readTree(r.body());
    }

    @Test
    void summaryCountsEventsAndComputesFailureRateAndContext() throws Exception {
        JsonNode s = json("/api/summary?project=alpha");
        assertEquals(1, s.path("sessions").asInt());
        assertEquals(3, s.path("prompts").asInt());
        assertEquals(25, s.path("tool_calls").asInt());
        assertEquals(10, s.path("tool_failures").asInt());
        assertEquals(1, s.path("guard_blocks").asInt());
        assertEquals(10.0 / 35.0, s.path("failure_rate").asDouble(), 1e-9);
        assertEquals(40, s.path("lines_added").asInt());
        assertEquals(60_024 + 2, s.path("peak_context").asInt() , 0);
    }

    @Test
    void filtersNarrowByProjectModelAndDate() throws Exception {
        assertEquals(26, json("/api/summary").path("tool_calls").asInt());
        assertEquals(1, json("/api/summary?project=beta").path("tool_calls").asInt());
        assertEquals(1, json("/api/summary?model=claude-opus-5-5").path("tool_calls").asInt());
        assertEquals(26, json("/api/summary?from=2026-10-01&to=2026-10-03").path("tool_calls").asInt());
        assertEquals(25, json("/api/summary?from=2026-10-01&to=2026-10-02").path("tool_calls").asInt());
        assertEquals(1, json("/api/summary?from=2026-10-03").path("tool_calls").asInt());
    }

    @Test
    void aFilterValueIsNeverInterpretedAsSql() throws Exception {
        JsonNode s = json("/api/summary?project=" + java.net.URLEncoder.encode("x' OR '1'='1", StandardCharsets.UTF_8));
        assertEquals(0, s.path("tool_calls").asInt());
    }

    @Test
    void anInvalidDateIsA400() throws Exception {
        assertEquals(400, get("/api/summary?from=yesterday").statusCode());
    }

    @Test
    void toolsReportCallsFailuresAndTopErrors() throws Exception {
        JsonNode t = json("/api/tools?project=alpha");
        JsonNode bash = t.path("tools").get(0);
        assertEquals("Bash", bash.path("tool").asText());
        assertEquals(25, bash.path("calls").asInt());
        assertEquals(10, bash.path("failures").asInt());
        assertEquals("exit status 1", t.path("errors").get(0).path("error").asText());
        // 25 calls with two different "git ..." commands must group under the single word "git".
        assertEquals("git", t.path("bash").get(0).path("command").asText());
        assertEquals(25, t.path("bash").get(0).path("count").asInt());
        assertEquals(1, java.util.stream.StreamSupport.stream(t.path("bash").spliterator(), false)
            .filter(b -> "git".equals(b.path("command").asText())).count());
    }

    @Test
    void commandsSessionsGuardAndGitEndpointsReturnTheSeededData() throws Exception {
        assertEquals("/plan", json("/api/commands").get(0).path("command").asText());
        assertEquals(2, json("/api/commands").get(0).path("count").asInt());
        assertEquals(1, json("/api/commands").size());

        JsonNode sessions = json("/api/sessions?project=alpha");
        assertEquals(1, sessions.size());
        assertEquals("s1", sessions.get(0).path("session_id").asText());
        assertEquals(40, sessions.get(0).path("lines_added").asInt());

        JsonNode events = json("/api/sessions/s1");
        assertEquals("session_start", events.get(0).path("event").asText());
        assertEquals("session_end", events.get(events.size() - 1).path("event").asText());

        JsonNode guard = json("/api/guard");
        assertEquals("force-push to main/master", guard.path("byRule").get(0).path("rule").asText());
        assertEquals("git push -f origin main", guard.path("recent").get(0).path("subject").asText());

        assertEquals("src/Main.java", json("/api/git").get(0).path("path").asText());
    }

    @Test
    void modelsAndTimeseriesGroupCorrectly() throws Exception {
        JsonNode models = json("/api/models");
        assertEquals("claude-sonnet-5-5", models.get(0).path("model").asText());
        assertEquals(25, models.get(0).path("tool_calls").asInt());

        JsonNode series = json("/api/timeseries");
        assertEquals(List.of("2026-10-01", "2026-10-03"),
            List.of(series.get(0).path("day").asText(), series.get(1).path("day").asText()));
    }

    @Test
    void insightsFlagHighFailureRateLargeContextGuardBlocksAndDroppedEvents() throws Exception {
        List<String> titles = new java.util.ArrayList<>();
        json("/api/insights").forEach(i -> titles.add(i.path("title").asText()));
        assertTrue(titles.stream().anyMatch(t -> t.startsWith("Bash fails 29%")), titles.toString());
        assertTrue(titles.stream().anyMatch(t -> t.contains("context over 150k")), titles.toString());
        assertTrue(titles.stream().anyMatch(t -> t.contains("Guard blocked")), titles.toString());
        assertTrue(titles.stream().anyMatch(t -> t.contains("event(s) were dropped")), titles.toString());
    }

    @Test
    void filtersEndpointListsProjectsModelsAndHealth() throws Exception {
        JsonNode f = json("/api/filters");
        assertEquals(List.of("alpha", "beta"), List.of(f.path("projects").get(0).asText(), f.path("projects").get(1).asText()));
        assertEquals(1, f.path("health").path("droppedEvents").asInt());
    }

    @Test
    void servesTheStaticPagesAndNothingElse() throws Exception {
        HttpResponse<String> index = get("/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("Agentic Skills usage"));
        assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(index.headers().firstValue("Content-Security-Policy").isPresent());
        assertEquals(200, get("/app.js").statusCode());
        assertEquals(200, get("/vendor/chart.umd.min.js").statusCode());

        assertEquals(404, get("/..%2f..%2fetc%2fpasswd").statusCode());
        assertEquals(404, get("/vendor/../app.js/../../pom.xml").statusCode());
        assertEquals(404, get("/api/nope").statusCode());
        assertEquals(404, get("/secret.txt").statusCode());
    }

    @Test
    void onlyGetIsAllowed() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/summary"))
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
    }

    @Test
    void aRequestWithAForeignHostHeaderIsRejected() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.getOutputStream().write(("GET /api/summary HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            assertTrue(status.contains("403"), status);
        }
    }

    @Test
    void listensOnLoopbackOnly() {
        assertTrue(server.url().startsWith("http://127.0.0.1:"));
        assertFalse(server.url().contains("0.0.0.0"));
    }
}
