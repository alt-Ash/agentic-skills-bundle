package dev.dorrian.agenticskillshooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box tests of the packaged hooks jar: each test spawns {@code java -jar
 * agentic-skills-hooks.jar <hookType>} as a real child process, pipes a JSON payload to stdin,
 * and asserts on the usage database it writes and the events it POSTs. Run by failsafe after
 * {@code package}, so this exercises the actual shaded artifact (manifest, main class, bundled
 * dependencies) rather than classes on the test classpath — the point of an out-of-process test.
 */
class HookInvocationIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path HOOKS_JAR = Path.of(System.getProperty("hooks.jar",
        "target/agentic-skills-hooks.jar"));

    private static final List<JsonNode> capturedBodies = new CopyOnWriteArrayList<>();
    private static HttpServer captureServer;
    private static String captureUrl;

    @BeforeAll
    static void startCaptureServer() throws IOException {
        assertTrue(Files.isRegularFile(HOOKS_JAR), "packaged jar not found: " + HOOKS_JAR.toAbsolutePath());
        captureServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        captureServer.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            try {
                capturedBodies.add(JSON.readTree(body));
            } catch (IOException ignored) {
                // Non-JSON body: not something these tests assert on.
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        captureServer.start();
        captureUrl = "http://127.0.0.1:" + captureServer.getAddress().getPort();
    }

    @AfterAll
    static void stopCaptureServer() {
        captureServer.stop(0);
    }

    @BeforeEach
    void clearCaptured() {
        capturedBodies.clear();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private record HookRun(int exitCode, JsonNode events) {
    }

    /** Each test gets its own database, next to (never inside) the hook's working directory. */
    private static Path dbFor(Path cwd) {
        return cwd.resolveSibling(cwd.getFileName() + "-usage.db");
    }

    private static HookRun runHook(Path cwd, String hookType, Object payload) throws Exception {
        return runHook(cwd, hookType, payload, Map.of());
    }

    private static HookRun runHook(Path cwd, String hookType, Object payload, Map<String, String> extraEnv)
        throws Exception {
        int exit = spawnHook(cwd, hookType, payload, extraEnv);
        return new HookRun(exit, storedEvents(cwd));
    }

    private static int spawnHook(Path cwd, String hookType, Object payload, Map<String, String> extraEnv)
        throws Exception {
        Process process = startHook(cwd, hookType, payload, extraEnv);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "hook process timed out: " + hookType);
        return process.exitValue();
    }

    private static Process startHook(Path cwd, String hookType, Object payload, Map<String, String> extraEnv)
        throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", HOOKS_JAR.toAbsolutePath().toString(), hookType)
            .directory(cwd.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        // Hermetic: never inherit a developer's real analytics endpoint or database.
        pb.environment().remove("ANALYTICS_SERVICE_URL");
        pb.environment().put(UsageDb.ENV_DB_PATH, dbFor(cwd).toString());
        pb.environment().putAll(extraEnv);
        Process process = pb.start();
        try (OutputStream stdin = process.getOutputStream()) {
            JSON.writeValue(stdin, payload);
        }
        return process;
    }

    /** Every stored event for this test's database, oldest first, as JSON (empty if there is no DB yet). */
    private static JsonNode storedEvents(Path cwd) {
        ArrayNode out = JSON.createArrayNode();
        if (!Files.exists(dbFor(cwd))) return out;
        try (UsageDb db = UsageDb.openReadOnly(dbFor(cwd));
             var st = db.connection().createStatement();
             var rs = st.executeQuery("SELECT session_id FROM events GROUP BY session_id ORDER BY MIN(ts), MIN(rowid)")) {
            List<String> sessions = new ArrayList<>();
            while (rs.next()) sessions.add(rs.getString(1));
            List<UsageEvent> all = new ArrayList<>();
            for (String id : sessions) all.addAll(db.eventsForSession(id));
            if (sessions.contains(null)) all.addAll(eventsWithoutSession(db));
            all.sort(java.util.Comparator.comparing(e -> e.ts));
            all.forEach(e -> out.add(JSON.valueToTree(e)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static List<UsageEvent> eventsWithoutSession(UsageDb db) throws Exception {
        List<UsageEvent> out = new ArrayList<>();
        try (var st = db.connection().createStatement(); var rs = st.executeQuery("SELECT event_id FROM events WHERE session_id IS NULL")) {
            while (rs.next()) {
                UsageEvent e = new UsageEvent();
                e.eventId = rs.getString(1);
                e.event = "(no session)";
                out.add(e);
            }
        }
        return out;
    }

    /** POSTs are fire-and-forget from the hook's point of view; poll briefly for them to land. */
    private static List<JsonNode> awaitCaptured(Predicate<JsonNode> filter, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<JsonNode> matching;
        do {
            matching = capturedBodies.stream().filter(filter).toList();
            if (matching.size() >= expected) {
                break;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        Thread.sleep(200); // catch any unexpected extra POSTs
        return capturedBodies.stream().filter(filter).toList();
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText(null)));
        return out;
    }

    private static Map<String, Object> sessionPayload(String event, String sessionId, Path cwd) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("hook_event_name", event);
        payload.put("session_id", sessionId);
        payload.put("cwd", cwd.toString());
        if (event.equals("SessionEnd")) {
            payload.put("reason", "other");
        }
        return payload;
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args));
    }

    // ─── session ─────────────────────────────────────────────────────────────

    @Nested
    class Session {

        @Test
        void writesASessionStartEventForASessionStartPayload(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "session",
                Map.of("hook_event_name", "SessionStart", "session_id", "sess-1", "cwd", "/tmp/proj"));

            assertEquals(0, run.exitCode());
            assertEquals(1, run.events().size());
            JsonNode ev = run.events().get(0);
            assertEquals("session_start", ev.path("event").asText());
            assertEquals("sess-1", ev.path("sessionId").asText());
            assertEquals("claude", ev.path("provider").asText());

            // Nothing is written to the working directory any more: the database is the only sink.
            assertFalse(Files.exists(cwd.resolve("ai-usage-events.json")));
            assertFalse(Files.exists(cwd.resolve("hooks-events.json")));
            assertFalse(Files.exists(cwd.resolve(".hooks-data")));
            try (UsageDb db = UsageDb.openReadOnly(dbFor(cwd))) {
                assertEquals("sess-1", db.session("sess-1").sessionId());
            }
        }

        @Test
        void alsoPostsTheEventToTheCaptureServerWhenAnalyticsServiceUrlIsSet(@TempDir Path cwd) throws Exception {
            runHook(cwd, "session",
                Map.of("hook_event_name", "SessionStart", "session_id", "sess-http", "cwd", "/tmp/proj"),
                Map.of("ANALYTICS_SERVICE_URL", captureUrl));

            List<JsonNode> posts = awaitCaptured(b -> true, 1);
            assertEquals(1, posts.size());
            assertEquals("session_start", posts.get(0).path("event").asText());
        }

        @Test
        void postsTheFullAccumulatedSessionOnSessionEndInAdditionToPerEventPosts(@TempDir Path cwd)
            throws Exception {
            Map<String, String> env = Map.of("ANALYTICS_SERVICE_URL", captureUrl);
            spawnHook(cwd, "session", sessionPayload("SessionStart", "sess-flush", cwd), env);
            spawnHook(cwd, "session", sessionPayload("SessionEnd", "sess-flush", cwd), env);

            List<JsonNode> eventPosts = awaitCaptured(b -> !b.has("hooks"), 2);
            List<JsonNode> sessionPosts = awaitCaptured(b -> b.has("hooks"), 1);

            assertEquals(List.of("session_start", "session_end"),
                eventPosts.stream().map(b -> b.path("event").asText()).toList());
            assertEquals(1, sessionPosts.size());
            assertEquals("sess-flush", sessionPosts.get(0).path("sessionId").asText());
            assertEquals(List.of("session_start", "session_end"), texts(sessionPosts.get(0).path("hooks"), "event"));

            assertTrue(eventPosts.stream().allMatch(b -> b.path("eventId").isTextual()));
            assertEquals(eventPosts.stream().map(b -> b.path("eventId").asText()).toList(),
                texts(sessionPosts.get(0).path("hooks"), "eventId"));
        }

        @Test
        void skipsTheSessionsPostAndCountsAMissWhenASessionEndsWithoutEverHavingStarted(@TempDir Path cwd)
            throws Exception {
            Map<String, String> env = Map.of("ANALYTICS_SERVICE_URL", captureUrl);
            spawnHook(cwd, "session", sessionPayload("SessionEnd", "sess-miss", cwd), env);

            List<JsonNode> sessionPosts = awaitCaptured(b -> b.has("hooks"), 0);
            assertEquals(0, sessionPosts.size());

            try (UsageDb db = UsageDb.openReadOnly(dbFor(cwd))) {
                assertEquals(1, db.meta(EventLog.SESSION_END_WITHOUT_START));
                assertEquals(List.of("session_end"),
                    db.eventsForSession("sess-miss").stream().map(e -> e.event).toList());
            }
        }

        @Test
        void capturesCommitsAddedFilesAndLineCountsMadeBetweenSessionStartAndEnd(@TempDir Path repo)
            throws Exception {
            git(repo, "init", "-q");
            git(repo, "config", "user.name", "Test User");
            git(repo, "config", "user.email", "test@example.com");
            Files.writeString(repo.resolve("a.txt"), "hello\n");
            git(repo, "add", "-A");
            git(repo, "commit", "-q", "-m", "init");

            spawnHook(repo, "session", sessionPayload("SessionStart", "sess-git-1", repo), Map.of());

            Files.writeString(repo.resolve("b.txt"), "world\n");
            git(repo, "add", "-A");
            git(repo, "commit", "-q", "-m", "add b.txt");
            Files.writeString(repo.resolve("c.txt"), "uncommitted\n");

            spawnHook(repo, "session", sessionPayload("SessionEnd", "sess-git-1", repo), Map.of());

            JsonNode start = null;
            JsonNode end = null;
            for (JsonNode e : storedEvents(repo)) {
                switch (e.path("event").asText()) {
                    case "session_start" -> start = e;
                    case "session_end" -> end = e;
                    default -> { }
                }
            }
            assertTrue(start != null && end != null, "expected both session_start and session_end");
            assertTrue(start.path("gitStartCommit").isTextual());
            assertEquals(1, end.path("gitCommits").size());
            assertEquals("add b.txt", end.path("gitCommits").get(0).path("message").asText());
            List<String> added = new ArrayList<>();
            end.path("gitFilesAdded").forEach(n -> added.add(n.asText()));
            assertTrue(added.containsAll(List.of("b.txt", "c.txt")), "gitFilesAdded: " + added);
            assertTrue(end.path("gitLinesAdded").isNumber());
        }
    }

    // ─── user-prompt-submit ──────────────────────────────────────────────────

    @Nested
    class UserPromptSubmit {

        @Test
        void writesAUserPromptEventWithDerivedMetadataButNotThePromptText(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "user-prompt-submit", Map.of(
                "session_id", "sess-2",
                "prompt", "Hello world",
                "permission_mode", "default",
                "prompt_id", "prompt-abc",
                "cwd", "/tmp/proj"));

            assertEquals(0, run.exitCode());
            JsonNode ev = run.events().get(0);
            assertEquals("user_prompt", ev.path("event").asText());
            assertEquals(11, ev.path("promptCharLength").asInt());
            assertEquals(3, ev.path("estimatedInputTokens").asInt());
            assertEquals("default", ev.path("permissionMode").asText());
            assertEquals("prompt-abc", ev.path("promptId").asText());
            assertFalse(ev.has("prompt"));
        }

        @Test
        void extractsTheSlashCommandNameButNotTheArgs(@TempDir Path cwd) throws Exception {
            // The raw payload carries the literal typed line, not the expanded <command-name> form.
            HookRun run = runHook(cwd, "user-prompt-submit", Map.of(
                "session_id", "sess-slash",
                "prompt", "/plan find gitignore candidates",
                "cwd", "/tmp/proj"));

            assertEquals(0, run.exitCode());
            JsonNode ev = run.events().get(0);
            assertEquals("/plan", ev.path("slashCommand").asText());
            assertFalse(ev.toString().contains("find gitignore candidates"));
        }

        @Test
        void setsSlashCommandToNullForAPlainTextPrompt(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "user-prompt-submit", Map.of(
                "session_id", "sess-plain",
                "prompt", "just a normal message",
                "cwd", "/tmp/proj"));

            assertTrue(run.events().get(0).path("slashCommand").isNull());
        }
    }

    // ─── post-tool-use ───────────────────────────────────────────────────────

    @Nested
    class PostToolUse {

        @Test
        void writesAToolUseEventForAClaudePayloadWithoutATranscript(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "post-tool-use", Map.of("session_id", "sess-3", "cwd", "/tmp/proj"));

            assertEquals(0, run.exitCode());
            JsonNode ev = run.events().get(0);
            assertEquals("tool_use", ev.path("event").asText());
            assertEquals("claude", ev.path("provider").asText());
            assertTrue(ev.path("model").isNull());
        }

        @Test
        void recordsTheToolNameIdAndDurationSoPerToolStatsAreRecoverable(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "post-tool-use", Map.of(
                "session_id", "sess-tool",
                "tool_name", "Edit",
                "tool_use_id", "tu-42",
                "duration_ms", 87,
                "cwd", "/tmp/proj"));

            JsonNode ev = run.events().get(0);
            assertEquals("Edit", ev.path("toolName").asText());
            assertEquals("tu-42", ev.path("toolUseId").asText());
            assertEquals(87, ev.path("durationMs").asInt());
        }

        @Test
        void exitsZeroAndWritesAnEventForAnEmptyPayload(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "post-tool-use", Map.of());

            assertEquals(0, run.exitCode());
            assertTrue(run.events() instanceof ArrayNode);
            assertEquals(1, run.events().size());
        }

        @Test
        void capturesTheCommandForABashToolCall(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "post-tool-use", Map.of(
                "session_id", "sess-bash",
                "tool_name", "Bash",
                "tool_input", Map.of("command", "git status"),
                "cwd", "/tmp/proj"));

            assertEquals("git status", run.events().get(0).path("command").asText());
        }

        @Test
        void setsCommandToNullForANonBashToolCall(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "post-tool-use", Map.of(
                "session_id", "sess-read",
                "tool_name", "Read",
                "tool_input", Map.of("file_path", "/tmp/some-file.ts"),
                "cwd", "/tmp/proj"));

            assertTrue(run.events().get(0).path("command").isNull());
        }
    }

    // ─── post-tool-use-failure ───────────────────────────────────────────────

    @Nested
    class PostToolUseFailure {

        @Test
        void writesAToolFailureEventWithToolNameAndError(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "post-tool-use-failure", Map.of(
                "session_id", "sess-fail",
                "tool_name", "Bash",
                "tool_use_id", "tu-1",
                "error", "command not found",
                "cwd", "/tmp/proj"));

            assertEquals(0, run.exitCode());
            JsonNode ev = run.events().get(0);
            assertEquals("tool_failure", ev.path("event").asText());
            assertEquals("claude", ev.path("provider").asText());
            assertEquals("Bash", ev.path("toolName").asText());
            assertEquals("command not found", ev.path("error").asText());
        }

        @Test
        void redactsAndCapsTheErrorTextBecauseItCanQuoteFileContents(@TempDir Path cwd) throws Exception {
            String quoted = "Exit code 1\n" + "secret-looking file body\n".repeat(100) + "token=abc123def456";
            HookRun run = runHook(cwd, "post-tool-use-failure", Map.of(
                "session_id", "sess-err", "tool_name", "Bash", "error", quoted, "cwd", "/tmp/proj"));

            String stored = run.events().get(0).path("error").asText();
            assertTrue(stored.length() < 400, "error should be capped, was " + stored.length());
            assertTrue(stored.endsWith("...[truncated]"));
            assertFalse(stored.contains("\n"));
        }

        @Test
        void capturesTheCommandForAFailedBashToolCall(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "post-tool-use-failure", Map.of(
                "session_id", "sess-fail-2",
                "tool_name", "Bash",
                "tool_input", Map.of("command", "exit 1"),
                "error", "command failed",
                "cwd", "/tmp/proj"));

            assertEquals("exit 1", run.events().get(0).path("command").asText());
        }
    }

    // ─── stop ────────────────────────────────────────────────────────────────

    @Nested
    class Stop {

        @Test
        void writesATurnStopEventWithCharLengthButNotMessageContent(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "stop", Map.of(
                "session_id", "sess-stop",
                "last_assistant_message", "Hello world",
                "stop_hook_active", false,
                "cwd", "/tmp/proj"));

            assertEquals(0, run.exitCode());
            JsonNode ev = run.events().get(0);
            assertEquals("turn_stop", ev.path("event").asText());
            assertEquals(11, ev.path("lastMessageCharLength").asInt());
            assertEquals(3, ev.path("estimatedOutputTokens").asInt());
            assertFalse(ev.has("last_assistant_message"));
        }
    }

    // ─── guard ───────────────────────────────────────────────────────────────

    @Nested
    class Guard {

        private Map<String, Object> bash(String command) {
            return Map.of("session_id", "sess-guard", "tool_name", "Bash", "tool_input", Map.of("command", command));
        }

        @Test
        void blocksWithExitTwoAndRecordsAGuardBlockEvent(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "guard", bash("git push --force origin main"));

            assertEquals(2, run.exitCode());
            assertEquals(1, run.events().size());
            JsonNode ev = run.events().get(0);
            assertEquals("guard_block", ev.path("event").asText());
            assertEquals("Bash", ev.path("toolName").asText());
            assertEquals("force-push to main/master", ev.path("guardRule").asText());
            assertEquals("git push --force origin main", ev.path("command").asText());
        }

        @Test
        void allowsWithExitZeroAndRecordsNothing(@TempDir Path cwd) throws Exception {
            HookRun run = runHook(cwd, "guard", bash("ls -la"));

            assertEquals(0, run.exitCode());
            assertEquals(0, run.events().size());
        }

        @Test
        void stillBlocksWhenTheDatabaseIsUnusable(@TempDir Path cwd) throws Exception {
            Files.writeString(dbFor(cwd), "not a database ".repeat(100));

            assertEquals(2, spawnHook(cwd, "guard", bash("rm -rf /"), Map.of()));
        }
    }

    // ─── concurrency ─────────────────────────────────────────────────────────

    @Nested
    class Concurrency {

        @Test
        void manyHookProcessesWritingAtOnceLoseNoEvents(@TempDir Path cwd) throws Exception {
            int processes = 12;
            ExecutorService pool = Executors.newFixedThreadPool(processes);
            List<Future<Integer>> exits = new ArrayList<>();
            for (int i = 0; i < processes; i++) {
                String session = "sess-par-" + (i % 3);
                exits.add(pool.submit(() -> spawnHook(cwd, "user-prompt-submit",
                    Map.of("session_id", session, "prompt", "hi", "cwd", "/tmp/proj"), Map.of())));
            }
            for (Future<Integer> exit : exits) assertEquals(0, exit.get());
            pool.shutdown();

            assertEquals(processes, storedEvents(cwd).size());
        }
    }
}
