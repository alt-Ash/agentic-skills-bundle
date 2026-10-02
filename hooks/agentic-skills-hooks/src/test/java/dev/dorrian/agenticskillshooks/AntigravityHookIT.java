package dev.dorrian.agenticskillshooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.usagestore.UsageEvent;
import dev.dorrian.usagestore.VerifyTrust;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box tests of the Antigravity adapter in the packaged jar. Payloads follow the hooks contract that ships
 * inside Antigravity itself: camelCase keys, the event named on the command line (it is not in the payload),
 * and a JSON object on stdout for every hook.
 */
class AntigravityHookIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONVERSATION = "ec33ebf9-0cba-4100-8142-c61503f6c587";

    private static Map<String, Object> common(Path work) {
        return new java.util.HashMap<>(Map.of(
            "conversationId", CONVERSATION,
            "workspacePaths", List.of(work.toString()),
            "transcriptPath", work.resolve("transcript.jsonl").toString(),
            "artifactDirectoryPath", work.resolve("artifacts").toString(),
            "modelName", "gemini-3.5-flash"));
    }

    private static HookJarHarness.Result agy(Path work, String event, Map<String, Object> payload, String... flags) throws Exception {
        List<String> args = new java.util.ArrayList<>(List.of("agy", event));
        args.addAll(List.of(flags));
        return HookJarHarness.runArgs(work, args, payload, Map.of());
    }

    private static JsonNode stdout(HookJarHarness.Result r) throws Exception {
        return JSON.readTree(r.stdout());
    }

    private static List<String> kinds(Path work) {
        return HookJarHarness.events(work).stream().map(e -> e.event).toList();
    }

    @Test
    void postToolUseRecordsASessionStartAndTheToolCallAndAnswersWithAnEmptyObject(@TempDir Path work) throws Exception {
        Map<String, Object> p = common(work);
        p.put("toolCall", Map.of("name", "run_command", "args", Map.of("CommandLine", "npm test", "Cwd", work.toString())));
        p.put("stepIdx", 5);

        HookJarHarness.Result r = agy(work, "post-tool-use", p);

        assertEquals(0, r.exitCode());
        assertTrue(stdout(r).isObject() && stdout(r).isEmpty(), "must answer {}: " + r.stdout());
        assertEquals("", r.stderr());
        assertEquals(List.of("session_start", "tool_use"), kinds(work));
        UsageEvent tool = HookJarHarness.events(work).get(1);
        assertEquals("Bash", tool.toolName, "run_command is recorded under our common tool name");
        assertEquals("npm test", tool.command);
        assertEquals("antigravity", tool.provider);
        assertEquals("gemini-3.5-flash", tool.model);
        assertEquals(CONVERSATION, tool.sessionId);
        assertEquals("step-5", tool.toolUseId);
    }

    @Test
    void aFailedToolCallIsRecordedAsAFailure(@TempDir Path work) throws Exception {
        Map<String, Object> p = common(work);
        p.put("toolCall", Map.of("name", "run_command", "args", Map.of("CommandLine", "false")));
        p.put("error", "exit status 1");

        agy(work, "post-tool-use", p);

        UsageEvent failure = HookJarHarness.events(work).get(1);
        assertEquals("tool_failure", failure.event);
        assertEquals("exit status 1", failure.error);
        assertEquals("Bash", failure.toolName);
    }

    @Test
    void fileToolsKeepOnlyThePathNeverTheContent(@TempDir Path work) throws Exception {
        Map<String, Object> p = common(work);
        p.put("toolCall", Map.of("name", "write_to_file", "args",
            Map.of("TargetFile", "/proj/src/A.java", "CodeContent", "SECRET FILE BODY")));

        agy(work, "post-tool-use", p);

        UsageEvent tool = HookJarHarness.events(work).get(1);
        assertEquals("Write", tool.toolName);
        assertFalse(tool.toString().contains("SECRET"), "file content must never be stored");
        assertTrue(HookJarHarness.events(work).stream().noneMatch(e -> String.valueOf(e.command).contains("SECRET")));
    }

    @Test
    void aNullToolCallIsIgnored(@TempDir Path work) throws Exception {
        Map<String, Object> p = common(work);
        p.put("toolCall", null); // PostToolUse sometimes carries this

        HookJarHarness.Result r = agy(work, "post-tool-use", p);

        assertEquals(0, r.exitCode());
        assertEquals("{}", r.stdout().strip());
        assertTrue(kinds(work).isEmpty());
    }

    @Test
    void stopRecordsTheTurnAndAnswersEmptyWithoutVerify(@TempDir Path work) throws Exception {
        Map<String, Object> p = common(work);
        p.put("executionNum", 0);
        p.put("terminationReason", "model_stop");
        p.put("fullyIdle", true);
        p.put("finalModelOutput", "all done");

        HookJarHarness.Result r = agy(work, "stop", p);

        assertEquals("{}", r.stdout().strip());
        assertEquals(List.of("session_start", "turn_stop"), kinds(work));
        assertEquals(8, HookJarHarness.events(work).get(1).lastMessageCharLength);
        assertEquals("antigravity", HookJarHarness.events(work).get(1).provider);
    }

    private static void verifyConfig(Path work, String json) throws Exception {
        Path dir = Files.createDirectories(work.resolve(".agentic-skills"));
        Files.writeString(dir.resolve("verify.json"), json);
        VerifyTrust.trust(HookJarHarness.homeFor(work), work, Files.readAllBytes(dir.resolve("verify.json")));
    }

    @Test
    void stopWithVerifyKeepsTheAgentGoingWhileTheCheckFailsAndLetsItStopWhenItPasses(@TempDir Path work) throws Exception {
        verifyConfig(work, "{\"commands\":[\"test -f DONE.txt\"],\"maxConsecutiveBlocks\":5}");
        Map<String, Object> p = common(work);
        p.put("executionNum", 0);

        HookJarHarness.Result blocked = agy(work, "stop", p, "--verify");
        JsonNode out = stdout(blocked);
        assertEquals(0, blocked.exitCode(), "Antigravity blocks through the JSON decision, never the exit code");
        assertEquals("continue", out.path("decision").asText());
        assertTrue(out.path("reason").asText().contains("Verification failed"), out.toString());

        Files.writeString(work.resolve("DONE.txt"), "");
        assertEquals("stop", stdout(agy(work, "stop", p, "--verify")).path("decision").asText());
    }

    @Test
    void theVerifyLoopIsBoundedEvenIfTheExecutionCounterNeverRises(@TempDir Path work) throws Exception {
        verifyConfig(work, "{\"commands\":[\"false\"],\"maxConsecutiveBlocks\":2}");
        Map<String, Object> p = common(work);
        p.put("executionNum", 0); // never rises: the guard must come from our own history

        List<String> decisions = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) decisions.add(stdout(agy(work, "stop", p, "--verify")).path("decision").asText());

        // Two blocks, then it gives up and lets the agent stop: the cap bounds one cycle. The fourth call is a
        // later turn (the agent had already stopped), which is gated afresh, not an endless continuation.
        assertEquals(List.of("continue", "continue", "stop", "continue"), decisions);
    }

    @Test
    void anUnapprovedVerifyFileNeverRunsAndLetsTheAgentStop(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve(".agentic-skills"));
        Files.writeString(work.resolve(".agentic-skills/verify.json"), "{\"commands\":[\"touch MUST-NOT-EXIST; exit 1\"]}");

        HookJarHarness.Result r = agy(work, "stop", common(work), "--verify");

        assertEquals("stop", stdout(r).path("decision").asText());
        assertFalse(Files.exists(work.resolve("MUST-NOT-EXIST")));
    }

    @Test
    void contextIsInjectedOnceOnTheFirstInvocationOfAConversation(@TempDir Path work) throws Exception {
        Files.createDirectories(work.resolve(".agentic-skills"));
        Files.writeString(work.resolve(".agentic-skills/context.md"), "The codeword is ZEBRA-7741.");
        Map<String, Object> p = common(work);
        p.put("invocationNum", 0);
        p.put("initialNumSteps", 0);

        JsonNode first = stdout(agy(work, "pre-invocation", p));
        String message = first.path("injectSteps").path(0).path("ephemeralMessage").asText();
        assertTrue(message.contains("ZEBRA-7741"), first.toString());
        assertTrue(message.contains("Project: "), message);

        // the next invocation of the same conversation injects nothing
        assertEquals("{}", agy(work, "pre-invocation", p).stdout().strip());
    }

    @Test
    void garbageAndUnknownEventsAnswerAnEmptyObjectAndNeverFail(@TempDir Path work) throws Exception {
        for (Object garbage : new Object[] {"not json", List.of(), Map.of()}) {
            for (String event : List.of("post-tool-use", "stop", "pre-invocation", "no-such-event", "")) {
                HookJarHarness.Result r = agy(work, event, garbage instanceof Map<?, ?> m ? castMap(m) : Map.of("x", garbage));
                assertEquals(0, r.exitCode(), event);
                assertEquals("{}", r.stdout().strip(), event + " -> " + r.stdout());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    // ─── guard: behavior verified against a live agy 1.2.14 session ──────────────

    private static Map<String, Object> preTool(Path work, String tool, Map<String, Object> args) {
        Map<String, Object> p = common(work);
        p.put("toolCall", Map.of("name", tool, "args", args));
        p.put("stepIdx", 2);
        return p;
    }

    @Test
    void theGuardPrintsNOTHINGToAllowBecauseAnEmptyObjectIsReadAsADenial(@TempDir Path work) throws Exception {
        HookJarHarness.Result r = agy(work, "pre-tool-use", preTool(work, "run_command", Map.of("CommandLine", "ls -la")));

        assertEquals(0, r.exitCode());
        assertEquals("", r.stdout(), "must be empty: Antigravity treats {} as 'tool call denied by pre-tool hook'");
        assertTrue(HookJarHarness.events(work).isEmpty(), "an allowed call records nothing");
    }

    @Test
    void theGuardDeniesWithTheDocumentedDecisionAndRecordsTheBlock(@TempDir Path work) throws Exception {
        HookJarHarness.Result r = agy(work, "pre-tool-use",
            preTool(work, "run_command", Map.of("CommandLine", "git push --force origin main")));

        assertEquals(0, r.exitCode(), "Antigravity blocks through the JSON decision, not the exit code");
        JsonNode out = stdout(r);
        assertEquals("deny", out.path("decision").asText());
        assertTrue(out.path("reason").asText().contains("force-push to main/master"), out.toString());
        List<UsageEvent> events = HookJarHarness.events(work);
        assertTrue(events.stream().anyMatch(e -> "guard_block".equals(e.event) && "antigravity".equals(e.provider)), events.toString());
    }

    @Test
    void theGuardSeesFilePathsFromViewFileAndWriteTools(@TempDir Path work) throws Exception {
        assertEquals("deny", stdout(agy(work, "pre-tool-use", preTool(work, "view_file", Map.of("AbsolutePath", "/proj/.env")))).path("decision").asText());
        assertEquals("deny", stdout(agy(work, "pre-tool-use", preTool(work, "write_to_file", Map.of("TargetFile", "/home/u/.ssh/id_rsa", "CodeContent", "x")))).path("decision").asText());
        assertEquals("", agy(work, "pre-tool-use", preTool(work, "view_file", Map.of("AbsolutePath", "/proj/src/Main.java"))).stdout());
        assertEquals("", agy(work, "pre-tool-use", preTool(work, "view_file", Map.of("AbsolutePath", "/proj/.env.example"))).stdout());
    }

    @Test
    void theGuardFailsOpenWithNoOutputOnGarbage(@TempDir Path work) throws Exception {
        for (Object garbage : new Object[] {"not json", List.of(), Map.of(), Map.of("toolCall", "oops")}) {
            HookJarHarness.Result r = agy(work, "pre-tool-use", garbage instanceof Map<?, ?> m ? castMap(m) : Map.of("x", garbage));
            assertEquals(0, r.exitCode());
            assertEquals("", r.stdout(), "garbage must allow (no output), never {}: " + garbage);
        }
    }

    @Test
    void thePostToolUseErrorFieldIsAnEmptyStringOnSuccessSoItMustNotCountAsAFailure(@TempDir Path work) throws Exception {
        // Real payloads carry "error": "" for every successful call, including a command that exited non-zero.
        Map<String, Object> p = common(work);
        p.put("toolCall", Map.of("name", "run_command", "args", Map.of("CommandLine", "false")));
        p.put("error", "");

        agy(work, "post-tool-use", p);

        assertEquals("tool_use", HookJarHarness.events(work).get(1).event);
    }
}
