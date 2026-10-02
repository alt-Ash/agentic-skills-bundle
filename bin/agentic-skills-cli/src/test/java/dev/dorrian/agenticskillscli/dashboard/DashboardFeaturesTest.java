package dev.dorrian.agenticskillscli.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Interactive filter, period comparison, real token views, usage view, CSV export, schema degradation. */
class DashboardFeaturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private DashboardServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private static UsageEvent ev(String id, String session, String kind, String ts) {
        UsageEvent e = new UsageEvent();
        e.eventId = id;
        e.sessionId = session;
        e.event = kind;
        e.ts = ts;
        e.project = "p";
        e.user = "ash";
        return e;
    }

    private void start(Path db, InstalledContent installed) throws Exception {
        server = DashboardServer.start(db, 0, installed);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(String path) throws Exception {
        HttpResponse<String> r = get(path);
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        return JSON.readTree(r.body());
    }

    private static List<String> names(JsonNode arr, String field) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    // ─── substantive sessions ───────────────────────────────────────────────

    /** one-shot (1 prompt), two-prompt chat, tool-only session, and an eval-ish prompt-only session. */
    private Path seedInteractive(Path dir) {
        Path db = dir.resolve("i.db");
        try (UsageDb u = UsageDb.open(db)) {
            u.record(ev("o1", "oneshot", "user_prompt", "2026-10-01T09:00:00Z"));
            u.record(ev("c1", "chat", "user_prompt", "2026-10-01T10:00:00Z"));
            u.record(ev("c2", "chat", "user_prompt", "2026-10-01T10:01:00Z"));
            UsageEvent t = ev("t1", "tooly", "tool_use", "2026-10-01T11:00:00Z");
            t.toolName = "Bash";
            t.model = "m1";
            t.inputTokens = 1;
            t.cachedTokens = 10;
            u.record(t);
            UsageEvent o = ev("o2", "oneshot", "tool_failure", "2026-10-01T09:00:05Z");
            o.toolName = "Read";
            u.record(o);
            UsageEvent e = ev("e1", "evalrun", "user_prompt", "2026-10-02T09:00:00Z");
            u.record(e);
        }
        return db;
    }

    @Test
    void interactiveKeepsOnlySessionsWithTwoPromptsOrAToolCallAcrossEveryEndpoint(@TempDir Path dir) throws Exception {
        start(seedInteractive(dir), null);

        assertEquals(4, json("/api/summary").path("sessions").asInt());
        JsonNode s = json("/api/summary?interactive=1");
        assertEquals(2, s.path("sessions").asInt()); // chat (2 prompts) + tooly (1 tool_use)
        assertEquals(2, s.path("prompts").asInt());
        assertEquals(0, s.path("tool_failures").asInt()); // the failure belonged to the one-shot session

        assertEquals(2, json("/api/sessions?interactive=1").size());
        assertEquals(List.of("2026-10-01"), names(json("/api/timeseries?interactive=1"), "day"));
        assertEquals(1, json("/api/models?interactive=1").size());
        assertEquals(List.of("Bash"), names(json("/api/tools?interactive=1").path("tools"), "tool"));
        assertEquals(2, json("/api/tools").path("tools").size());
        // default is off, and "interactive=0" or junk is off too
        assertEquals(4, json("/api/summary?interactive=0").path("sessions").asInt());
        assertEquals(4, json("/api/summary?interactive=banana").path("sessions").asInt());
        // composes with other filters
        assertEquals(0, json("/api/summary?interactive=1&from=2026-10-02").path("sessions").asInt());
        assertEquals(1, json("/api/summary?from=2026-10-02").path("sessions").asInt());
    }

    // ─── period comparison ──────────────────────────────────────────────────

    @Test
    void compareReturnsCurrentPreviousAndDeltasForAnEqualLengthWindow(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("c.db");
        try (UsageDb u = UsageDb.open(db)) {
            // previous window: 2026-10-01..02 (2 days) -> 2 prompts; current window 10-03..04 -> 3 prompts + failure
            u.record(ev("p1", "s1", "user_prompt", "2026-10-01T09:00:00Z"));
            u.record(ev("p2", "s1", "user_prompt", "2026-10-02T09:00:00Z"));
            u.record(ev("q1", "s2", "user_prompt", "2026-10-03T09:00:00Z"));
            u.record(ev("q2", "s2", "user_prompt", "2026-10-03T09:01:00Z"));
            u.record(ev("q3", "s3", "user_prompt", "2026-10-04T09:00:00Z"));
            UsageEvent f = ev("q4", "s3", "tool_failure", "2026-10-04T09:05:00Z");
            f.toolName = "Bash";
            u.record(f);
        }
        start(db, null);
        JsonNode r = json("/api/summary?from=2026-10-03&to=2026-10-04&compare=prev");
        assertEquals(3, r.path("current").path("prompts").asInt());
        assertEquals(2, r.path("previous").path("prompts").asInt());
        assertEquals(1.0, r.path("deltas").path("prompts").path("abs").asDouble(), 1e-9);
        assertEquals(0.5, r.path("deltas").path("prompts").path("pct").asDouble(), 1e-9);
        assertEquals("2026-10-01", r.path("window").path("previousFrom").asText());
        assertEquals("2026-10-03", r.path("window").path("previousTo").asText());
        assertTrue(r.path("deltas").path("failure_rate").path("lowerIsBetter").asBoolean());
        // previous window has no tool calls/failures: delta of a missing-vs-zero value never yields NaN
        JsonNode pct = r.path("deltas").path("tool_failures").path("pct");
        assertTrue(pct.isNull(), "pct against a zero baseline must be null, not NaN/Infinity");
        // null token data on both sides: abs and pct are null
        assertTrue(r.path("deltas").path("output_tokens").path("abs").isNull());
        // no compare -> plain summary shape
        assertTrue(json("/api/summary?from=2026-10-03&to=2026-10-04").path("current").isMissingNode());
    }

    @Test
    void compareNeedsAWindowAndAValidValue(@TempDir Path dir) throws Exception {
        start(seedInteractive(dir), null);
        HttpResponse<String> none = get("/api/summary?compare=prev");
        assertEquals(400, none.statusCode());
        assertTrue(none.body().contains("from and to"), none.body());
        assertEquals(400, get("/api/summary?from=2026-10-01&compare=prev").statusCode());
        assertEquals(400, get("/api/summary?to=2026-10-01&compare=prev").statusCode());
        assertEquals(400, get("/api/summary?from=2026-10-05&to=2026-10-01&compare=prev").statusCode());
        assertEquals(400, get("/api/summary?from=2026-10-01&to=2026-10-02&compare=next").statusCode());
        assertEquals(400, get("/api/summary?from=nope&to=2026-10-02&compare=prev").statusCode());
        assertEquals(200, get("/api/summary?from=2026-10-01&to=2026-10-01&compare=prev").statusCode()); // one day
    }

    // ─── real token usage ───────────────────────────────────────────────────

    @Test
    void realTokenColumnsAreSummedNullSafelyAndBrokenDownPerModel(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        try (UsageDb u = UsageDb.open(db)) {
            UsageEvent a = ev("a", "s", "tool_use", "2026-10-01T09:00:00Z");
            a.model = "m1";
            a.inputTokens = 10;
            a.cachedTokens = 900;
            a.outputTokens = 50; // a tool call repeats the last message's usage: must NOT be added to the output total
            a.cacheReadTokens = 800;
            a.cacheCreationTokens = 90;
            u.record(a);
            for (int i = 0; i < 2; i++) { // output tokens are counted once per turn, from the turn_stop rows
                UsageEvent stop = ev("stop" + i, "s", "turn_stop", "2026-10-01T09:0" + (2 + i) + ":00Z");
                stop.model = "m1";
                stop.outputTokens = i == 0 ? 40 : 30;
                u.record(stop);
            }
            UsageEvent legacy = ev("b", "s", "tool_use", "2026-10-01T09:01:00Z"); // NULL new columns
            legacy.model = "m2";
            legacy.inputTokens = 5;
            legacy.cachedTokens = 5;
            u.record(legacy);
        }
        start(db, null);
        JsonNode s = json("/api/summary");
        assertEquals(70, s.path("output_tokens").asInt(), "turn_stop rows only; the tool call's 50 is a repeat");
        assertEquals(800, s.path("cache_read_tokens").asInt());
        assertEquals(90, s.path("cache_creation_tokens").asInt());
        assertEquals(800.0 / (800 + 90 + 10), s.path("cache_read_share").asDouble(), 1e-9);

        JsonNode models = json("/api/models");
        for (JsonNode m : models) {
            if ("m1".equals(m.path("model").asText())) {
                assertEquals(70, m.path("output_tokens").asInt());
                assertEquals(800, m.path("cache_read_tokens").asInt());
            } else {
                assertTrue(m.path("output_tokens").isNull(), "legacy model has no real token data: " + m);
            }
        }
        // sessions: null where nothing was recorded, number where it was
        assertEquals(70, json("/api/sessions").get(0).path("output_tokens").asInt());
    }

    @Test
    void withNoRealTokenDataEverythingIsNullNotNaN(@TempDir Path dir) throws Exception {
        start(seedInteractive(dir), null);
        JsonNode s = json("/api/summary");
        assertTrue(s.path("output_tokens").isNull());
        assertTrue(s.path("cache_read_share").isNull());
        assertFalse(get("/api/summary").body().contains("NaN"));
        assertTrue(json("/api/models").get(0).path("cache_read_tokens").isNull());
    }

    @Test
    void aDatabaseWithoutTheV2ColumnsStillServesEverything(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("old.db");
        try (UsageDb u = UsageDb.open(db)) {
            UsageEvent t = ev("t1", "s", "tool_use", "2026-10-01T09:00:00Z");
            t.toolName = "Bash";
            t.model = "m1";
            t.inputTokens = 1;
            t.cachedTokens = 2;
            u.record(t);
            try (Statement st = u.connection().createStatement()) {
                st.execute("DROP INDEX idx_events_skill");
                st.execute("DROP INDEX idx_events_agent");
                for (String c : List.of("output_tokens", "cache_read_tokens", "cache_creation_tokens", "agent_name", "skill_name")) {
                    st.execute("ALTER TABLE events DROP COLUMN " + c);
                }
            }
        }
        start(db, InstalledContent.load(packageRoot(dir.resolve("pkg"))));
        for (String p : List.of("summary", "models", "sessions", "timeseries", "tools", "usage", "insights", "commands")) {
            assertEquals(200, get("/api/" + p).statusCode(), p);
        }
        assertTrue(json("/api/summary").path("output_tokens").isNull());
        assertEquals(200, get("/api/export/events.csv").statusCode());
        assertEquals(200, get("/api/sessions/s").statusCode());
    }

    // ─── installed vs used ──────────────────────────────────────────────────

    private static Path packageRoot(Path root) throws Exception {
        Files.createDirectories(root.resolve("skills/cat/used-skill"));
        Files.createDirectories(root.resolve("skills/cat/idle-skill"));
        Files.createDirectories(root.resolve("agents"));
        Files.writeString(root.resolve("agents/used-agent.md"), "---\ndescription: An agent that is used in tests\n---\nbody\n");
        Files.writeString(root.resolve("agents/idle-agent.md"), "---\ndescription: An agent that is idle in tests\n---\nbody\n");
        Files.createDirectories(root.resolve(".opencode/commands"));
        Files.writeString(root.resolve(".opencode/commands/migrate-java.md"), "---\ndescription: x\n---\n");
        Files.writeString(root.resolve(".opencode/commands/security-gate.md"), "---\ndescription: x\n---\n");
        Files.writeString(root.resolve(".opencode/commands/eval-agent.md"), "---\ndescription: dev only\n---\n");
        return root;
    }

    private Path seedUsage(Path dir, int sessions) throws Exception {
        Files.createDirectories(dir);
        Path db = dir.resolve("u.db");
        try (UsageDb u = UsageDb.open(db)) {
            for (int i = 0; i < sessions; i++) {
                String sid = "s" + i;
                u.record(ev("p" + i, sid, "user_prompt", "2026-10-01T09:" + String.format("%02d", i) + ":00Z"));
                u.record(ev("q" + i, sid, "user_prompt", "2026-10-01T09:" + String.format("%02d", i) + ":30Z"));
            }
            UsageEvent skill = ev("sk", "s0", "tool_use", "2026-10-01T10:00:00Z");
            skill.skillName = "used-skill";
            u.record(skill);
            UsageEvent skill2 = ev("sk2", "s1", "tool_use", "2026-10-01T10:05:00Z");
            skill2.skillName = "used-skill";
            u.record(skill2);
            UsageEvent agent = ev("ag", "s0", "tool_use", "2026-10-01T10:10:00Z");
            agent.agentName = "used-agent";
            u.record(agent);
            UsageEvent stray = ev("st", "s0", "tool_use", "2026-10-01T10:11:00Z");
            stray.agentName = "removed-agent";
            u.record(stray);
            UsageEvent cmd = ev("cm", "s0", "user_prompt", "2026-10-01T10:12:00Z");
            cmd.slashCommand = "/migrate-java";
            u.record(cmd);
        }
        return db;
    }

    @Test
    void usageMergesUsedAndInstalledAndListsNeverUsed(@TempDir Path dir) throws Exception {
        start(seedUsage(dir, 3), InstalledContent.load(packageRoot(dir.resolve("pkg"))));
        JsonNode u = json("/api/usage");
        assertTrue(u.path("installed").path("available").asBoolean());

        JsonNode skills = u.path("skills");
        assertEquals("used-skill", skills.get(0).path("name").asText());
        assertEquals(2, skills.get(0).path("uses").asInt());
        assertEquals(2, skills.get(0).path("sessions").asInt());
        assertEquals("2026-10-01T10:05:00Z", skills.get(0).path("last_used").asText());
        assertTrue(skills.get(0).path("installed").asBoolean());
        assertEquals(List.of("idle-skill"), list(u.path("neverUsed").path("skills")));

        assertEquals(List.of("idle-agent"), list(u.path("neverUsed").path("agents")));
        JsonNode removed = StreamSupport.stream(u.path("agents").spliterator(), false)
            .filter(a -> "removed-agent".equals(a.path("name").asText())).findFirst().orElseThrow();
        assertFalse(removed.path("installed").asBoolean(), "used but no longer in the bundle");

        // commands: slash stored with a leading slash, matched by bare name; eval-agent is never installed
        assertEquals(List.of("security-gate"), list(u.path("neverUsed").path("commands")));
        assertEquals("migrate-java", u.path("commands").get(0).path("name").asText());
    }

    private static List<String> list(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void usageWithoutAnInstalledListIsUsedOnly(@TempDir Path dir) throws Exception {
        start(seedUsage(dir, 3), null);
        JsonNode u = json("/api/usage");
        assertFalse(u.path("installed").path("available").asBoolean());
        assertTrue(u.path("neverUsed").isEmpty());
        assertEquals("used-skill", u.path("skills").get(0).path("name").asText());
        assertTrue(u.path("skills").get(0).path("installed").isNull());
    }

    @Test
    void neverUsedIsRelativeToTheSelectedWindowAndFilters(@TempDir Path dir) throws Exception {
        start(seedUsage(dir, 3), InstalledContent.load(packageRoot(dir.resolve("pkg"))));
        JsonNode later = json("/api/usage?from=2026-10-05");
        assertTrue(list(later.path("neverUsed").path("skills")).contains("used-skill"));
        assertEquals(0, later.path("skills").get(0).path("uses").asInt());
    }

    @Test
    void theNeverUsedInsightNeedsAnInstalledListAndEnoughSessions(@TempDir Path dir) throws Exception {
        Path pkg = packageRoot(dir.resolve("pkg"));
        Path few = seedUsage(dir.resolve("few"), 5);
        start(few, InstalledContent.load(pkg));
        assertFalse(insightTitles().stream().anyMatch(t -> t.contains("never used")), insightTitles().toString());
        server.close();

        Path many = seedUsage(dir.resolve("many"), 25);
        start(many, null);
        assertFalse(insightTitles().stream().anyMatch(t -> t.contains("never used")), "no installed list");
        server.close();

        start(many, InstalledContent.load(pkg));
        List<String> titles = insightTitles();
        assertTrue(titles.stream().anyMatch(t -> t.contains("never used")), titles.toString());
    }

    private List<String> insightTitles() throws Exception {
        return names(json("/api/insights"), "title");
    }

    // ─── CSV ────────────────────────────────────────────────────────────────

    @Test
    void csvEscapingAndFormulaInjectionProtection() {
        assertEquals("plain", Csv.cell("plain"));
        assertEquals("\"a,b\"", Csv.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""));
        assertEquals("\"line1\nline2\"", Csv.cell("line1\nline2"));
        assertEquals("'=1+1", Csv.cell("=1+1"));
        assertEquals("'+cmd", Csv.cell("+cmd"));
        assertEquals("'-2", Csv.cell("-2"));
        assertEquals("'@SUM(A1)", Csv.cell("@SUM(A1)"));
        assertEquals("'\tx", Csv.cell("\tx"));
        assertTrue(Csv.cell("\rx").startsWith("\"'"));
        assertEquals("\"'=a,b\"", Csv.cell("=a,b"));
        assertEquals("-5", Csv.cell(-5)); // a real number is not text
        assertEquals("", Csv.cell(null));
        assertEquals("ünï", Csv.cell("ünï"));
    }

    @Test
    void eventsAndSessionsExportHonorFiltersAndAreSafeAttachments(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("x.db");
        try (UsageDb u = UsageDb.open(db)) {
            UsageEvent bad = ev("x1", "s1", "tool_failure", "2026-10-01T09:00:00Z");
            bad.toolName = "Bash";
            bad.command = "=HYPERLINK(\"http://evil\",\"x\"), more";
            bad.error = "boom\nsecond line ünï";
            bad.cwd = "/home/secret/should-not-export";
            u.record(bad);
            UsageEvent other = ev("x2", "s2", "user_prompt", "2026-10-09T09:00:00Z");
            other.project = "other";
            u.record(other);
        }
        start(db, null);

        HttpResponse<String> r = get("/api/export/events.csv?project=p");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/csv; charset=utf-8"));
        assertTrue(r.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
        assertEquals("false", r.headers().firstValue("X-Export-Truncated").orElse(""));
        String body = r.body();
        assertTrue(body.startsWith("ts,event,session_id,project,model,tool,"), body);
        assertTrue(body.contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\"), more\""), body);
        assertTrue(body.contains("\"boom\nsecond line ünï\""), body);
        assertFalse(body.contains("should-not-export"), "cwd must not be exported");
        assertFalse(body.contains(",ash"), "user must not be exported");
        assertFalse(body.contains("2026-10-09"), "project filter must apply");

        assertFalse(get("/api/export/events.csv?from=2026-10-05").body().contains("2026-10-01T09"));

        HttpResponse<String> s = get("/api/export/sessions.csv?project=p");
        assertEquals(200, s.statusCode());
        assertTrue(s.headers().firstValue("Content-Disposition").orElse("").contains("sessions.csv"));
        assertTrue(s.body().startsWith("session_id,project,user,"), s.body());
        assertTrue(s.body().contains("s1"));
        assertFalse(s.body().contains("s2,"));

        assertEquals(400, get("/api/export/events.csv?from=bad").statusCode());
        assertEquals(404, get("/api/export/other.csv").statusCode());
    }

    @Test
    void exportsRespectTheInteractiveFilter(@TempDir Path dir) throws Exception {
        start(seedInteractive(dir), null);
        String all = get("/api/export/sessions.csv").body();
        String some = get("/api/export/sessions.csv?interactive=1").body();
        assertTrue(all.contains("oneshot"));
        assertFalse(some.contains("oneshot"));
        assertTrue(some.contains("chat"));
    }

    // ─── DashboardCommand argument handling ─────────────────────────────────

    @Test
    void commandRejectsABadPackageRoot(@TempDir Path dir) {
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream e = new java.io.PrintStream(err);
        java.io.PrintStream o = new java.io.PrintStream(new java.io.ByteArrayOutputStream());
        assertEquals(2, DashboardCommand.run(List.of("--package-root"), dir.resolve("none.db"), o, e));
        assertEquals(2, DashboardCommand.run(List.of("--package-root", dir.resolve("missing").toString()), dir.resolve("none.db"), o, e));
        assertTrue(err.toString().contains("not a directory"));
        // a valid root parses, then stops at the missing database (exit 1) without starting a server
        assertEquals(1, DashboardCommand.run(List.of("--package-root", dir.toString(), "--no-open"), dir.resolve("none.db"), o, e));
        assertEquals(2, DashboardCommand.run(List.of("--bogus"), dir.resolve("none.db"), o, e));
    }

    @Test
    void installedContentReadsSkillsAgentsAndDistributedCommands(@TempDir Path dir) throws Exception {
        InstalledContent c = InstalledContent.load(packageRoot(dir));
        assertEquals(List.of("idle-skill", "used-skill"), c.skills());
        assertEquals(List.of("idle-agent", "used-agent"), c.agents());
        assertEquals(List.of("migrate-java", "security-gate"), c.commands());
        InstalledContent none = InstalledContent.load(dir.resolve("empty"));
        assertTrue(none.skills().isEmpty() && none.agents().isEmpty() && none.commands().isEmpty());
    }
}
