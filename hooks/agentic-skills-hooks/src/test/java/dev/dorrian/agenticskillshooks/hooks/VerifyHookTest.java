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
}
