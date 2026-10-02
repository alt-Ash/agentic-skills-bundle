package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyHookTest {

    @TempDir
    Path project;

    private void config(String json) throws Exception {
        Files.createDirectories(project.resolve(".agentic-skills"));
        Files.writeString(project.resolve(".agentic-skills/verify.json"), json);
    }

    private HookInput input(boolean active) {
        return HookInput.parse("{\"session_id\":\"s1\",\"cwd\":\"" + project.toString().replace("\\", "\\\\")
            + "\",\"stop_hook_active\":" + active + "}");
    }

    private static UsageEvent ev(String kind) {
        UsageEvent e = new UsageEvent();
        e.event = kind;
        return e;
    }

    private static CommandRunner.Result res(int code) {
        return new CommandRunner.Result(code, "line1\nboom", false, false, 5);
    }

    @Test
    void configDefaultsAndOverrides() {
        VerifyConfig c = VerifyConfig.parse("{\"commands\":[\"a\",\"b\"]}").orElseThrow();
        assertEquals(300, c.timeoutSeconds());
        assertEquals(3, c.maxConsecutiveBlocks());
        VerifyConfig d = VerifyConfig.parse("{\"commands\":[\"a\"],\"timeoutSeconds\":5,\"maxConsecutiveBlocks\":1}").orElseThrow();
        assertEquals(5, d.timeoutSeconds());
        assertEquals(1, d.maxConsecutiveBlocks());
    }

    @Test
    void badConfigIsEmpty() {
        assertTrue(VerifyConfig.parse("not json").isEmpty());
        assertTrue(VerifyConfig.parse("[]").isEmpty());
        assertTrue(VerifyConfig.parse("{\"commands\":[]}").isEmpty());
        assertTrue(VerifyConfig.load(project).isEmpty());
    }

    @Test
    void missingOrBrokenConfigAllows() throws Exception {
        List<UsageEvent> out = new ArrayList<>();
        assertTrue(VerifyHook.evaluate(input(false), s -> List.of(), (c, d, t) -> res(1), out::add).isEmpty());
        config("{oops");
        assertTrue(VerifyHook.evaluate(input(false), s -> List.of(), (c, d, t) -> res(1), out::add).isEmpty());
        assertTrue(out.isEmpty());
    }

    @Test
    void failureBlocksAndStopsAtFirstFailure() throws Exception {
        config("{\"commands\":[\"one\",\"two\",\"three\"]}");
        List<UsageEvent> out = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        Optional<String> r = VerifyHook.evaluate(input(false), s -> List.of(),
            (c, d, t) -> {
                ran.add(c);
                return res(c.equals("two") ? 3 : 0);
            }, out::add);
        assertEquals(List.of("one", "two"), ran);
        assertTrue(r.orElseThrow().startsWith("Verification failed: `two` exited 3."));
        assertTrue(r.get().contains("boom"));
        assertEquals("verify_block", out.get(0).event);
        assertEquals("two", out.get(0).command);
    }

    @Test
    void passRecordsPass() throws Exception {
        config("{\"commands\":[\"one\"]}");
        List<UsageEvent> out = new ArrayList<>();
        assertTrue(VerifyHook.evaluate(input(false), s -> List.of(), (c, d, t) -> res(0), out::add).isEmpty());
        assertEquals("verify_pass", out.get(0).event);
    }

    @Test
    void timeoutAndStartFailureFailOpenButRecord() throws Exception {
        config("{\"commands\":[\"one\"]}");
        List<UsageEvent> out = new ArrayList<>();
        assertTrue(VerifyHook.evaluate(input(false), s -> List.of(),
            (c, d, t) -> new CommandRunner.Result(-1, "", true, false, 9), out::add).isEmpty());
        assertTrue(VerifyHook.evaluate(input(false), s -> List.of(),
            (c, d, t) -> new CommandRunner.Result(-1, "nope", false, true, 1), out::add).isEmpty());
        assertEquals("verify_error", out.get(0).event);
        assertEquals("timeout", out.get(0).reason);
        assertEquals("start_failed", out.get(1).reason);
    }

    @Test
    void loopGuardGivesUpAfterMaxBlocks() throws Exception {
        config("{\"commands\":[\"one\"],\"maxConsecutiveBlocks\":2}");
        List<UsageEvent> out = new ArrayList<>();
        List<UsageEvent> hist = List.of(ev("turn_stop"), ev("verify_block"), ev("verify_block"));
        assertTrue(VerifyHook.evaluate(input(true), s -> hist, (c, d, t) -> res(1), out::add).isEmpty());
        assertEquals("max_blocks", out.get(0).reason);
        // below the limit it still blocks
        List<UsageEvent> one = List.of(ev("verify_block"));
        assertTrue(VerifyHook.evaluate(input(true), s -> one, (c, d, t) -> res(1), out::add).isPresent());
    }

    @Test
    void trailingBlocksResetOnPass() {
        assertEquals(1, VerifyHook.trailingBlocks(List.of(ev("verify_block"), ev("verify_pass"), ev("verify_block"))));
        assertEquals(0, VerifyHook.trailingBlocks(List.of(ev("verify_block"), ev("verify_error"))));
    }

    @Test
    void tailCapsLinesAndChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("line").append(i).append('\n');
        String t = VerifyHook.tail(sb.toString());
        assertEquals(40, t.lines().count());
        assertTrue(t.endsWith("line199"));
        assertTrue(VerifyHook.tail("x".repeat(10000)).length() <= 3000);
        assertFalse(VerifyHook.tail(null).contains("null"));
    }

    @Test
    void commandRunnerRealShell() {
        assertEquals(0, CommandRunner.run("true", project.toFile(), 10).exitCode());
        CommandRunner.Result f = CommandRunner.run("echo hi; exit 4", project.toFile(), 10);
        assertEquals(4, f.exitCode());
        assertTrue(f.output().contains("hi"));
        assertTrue(CommandRunner.run("sleep 30", project.toFile(), 1).timedOut());
    }

    // ─── trust: a verify.json in a repo is untrusted input ───────────────────────

    @Test
    void anUnapprovedConfigNeverRunsAndIsNotedOncePerSession() throws Exception {
        config("{\"commands\":[\"rm -rf everything\"]}");
        List<UsageEvent> out = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        VerifyHook.Runner runner = (c, d, t) -> {
            ran.add(c);
            return res(1);
        };

        Optional<String> first = VerifyHook.evaluate(input(false), s -> List.of(), runner, out::add, (p, b) -> false);
        assertTrue(first.isEmpty(), "an untrusted config must allow the stop");
        assertTrue(ran.isEmpty(), "and must not run anything");
        assertEquals(1, out.size());
        assertEquals("verify_error", out.get(0).event);
        assertEquals("untrusted_config", out.get(0).reason);
        assertTrue(out.get(0).error.contains("agentic-skills verify trust"));

        // the second stop in the same session does not repeat the note
        UsageEvent noted = ev("verify_error");
        noted.reason = "untrusted_config";
        VerifyHook.evaluate(input(false), s -> List.of(noted), runner, out::add, (p, b) -> false);
        assertEquals(1, out.size());
        assertTrue(ran.isEmpty());
    }

    @Test
    void anApprovedConfigRunsAndTheTrustCheckSeesTheExactBytes() throws Exception {
        config("{\"commands\":[\"ok\"]}");
        List<UsageEvent> out = new ArrayList<>();
        List<byte[]> seen = new ArrayList<>();
        Optional<String> r = VerifyHook.evaluate(input(false), s -> List.of(), (c, d, t) -> res(0), out::add, (p, b) -> {
            seen.add(b);
            return true;
        });
        assertTrue(r.isEmpty());
        assertEquals("verify_pass", out.get(0).event);
        assertEquals("{\"commands\":[\"ok\"]}", new String(seen.get(0), java.nio.charset.StandardCharsets.UTF_8));
    }

    // ─── total time budget ───────────────────────────────────────────────────────

    @Test
    void allCommandsTogetherAreHeldToTheTotalBudgetSoTheHostNeverKillsTheGate() throws Exception {
        config("{\"commands\":[\"a\",\"b\",\"c\"],\"timeoutSeconds\":540}");
        List<UsageEvent> out = new ArrayList<>();
        List<Integer> limits = new ArrayList<>();
        // each command "takes" 300s
        VerifyHook.Runner runner = (c, d, t) -> {
            limits.add(t);
            return new CommandRunner.Result(0, "", false, false, 300_000);
        };
        Optional<String> r = VerifyHook.evaluate(input(false), s -> List.of(), runner, out::add);

        assertTrue(r.isEmpty(), "running out of budget must fail open");
        assertEquals(List.of(540, 240), limits, "second command only gets what is left; the third never runs");
        assertEquals("verify_error", out.get(0).event);
        assertEquals("budget", out.get(0).reason);
        assertEquals("c", out.get(0).command);
    }
}
