package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyHookIT {

    @TempDir
    Path tmp;

    private Path project() throws Exception {
        Path p = Files.createDirectory(tmp.resolve("proj"));
        Files.createDirectories(p.resolve(".agentic-skills"));
        return p;
    }

    /** Writes verify.json and approves it (in the hook JVM's private home), like `agentic-skills verify trust` would. */
    private void config(Path p, String json) throws Exception {
        Files.writeString(p.resolve(".agentic-skills/verify.json"), json);
        dev.dorrian.usagestore.VerifyTrust.trust(HookJarHarness.homeFor(p), p, Files.readAllBytes(p.resolve(".agentic-skills/verify.json")));
    }

    private HookJarHarness.Result stop(Path p, boolean active) throws Exception {
        return HookJarHarness.run(p, "verify", Map.of("session_id", "s1", "cwd", p.toString(),
            "hook_event_name", "Stop", "stop_hook_active", active));
    }

    private static List<String> kinds(List<UsageEvent> evs) {
        return evs.stream().map(e -> e.event).filter(k -> k.startsWith("verify_")).toList();
    }

    @Test
    void passingCommandAllowsAndRecords() throws Exception {
        Path p = project();
        config(p, "{\"commands\":[\"true\"]}");
        var r = stop(p, false);
        assertEquals(0, r.exitCode());
        assertEquals(List.of("verify_pass"), kinds(HookJarHarness.events(p)));
    }

    @Test
    void failingCommandBlocksWithTail() throws Exception {
        Path p = project();
        config(p, "{\"commands\":[\"echo compile-error-here; false\"]}");
        var r = stop(p, false);
        assertEquals(2, r.exitCode());
        assertTrue(r.stderr().contains("Verification failed"), r.stderr());
        assertTrue(r.stderr().contains("exited 1"));
        assertTrue(r.stderr().contains("compile-error-here"));
        List<UsageEvent> evs = HookJarHarness.events(p);
        assertEquals(List.of("verify_block"), kinds(evs));
        UsageEvent e = evs.stream().filter(x -> "verify_block".equals(x.event)).findFirst().orElseThrow();
        assertTrue(e.command.contains("false"));
        assertTrue(e.error.contains("compile-error-here"));
    }

    @Test
    void loopGuardEventuallyAllows() throws Exception {
        Path p = project();
        config(p, "{\"commands\":[\"false\"],\"maxConsecutiveBlocks\":2}");
        assertEquals(2, stop(p, false).exitCode());
        assertEquals(2, stop(p, true).exitCode());
        assertEquals(0, stop(p, true).exitCode());
        assertEquals(List.of("verify_block", "verify_block", "verify_error"), kinds(HookJarHarness.events(p)));
    }

    @Test
    void missingConfigAllows() throws Exception {
        Path p = project();
        assertEquals(0, stop(p, false).exitCode());
        assertTrue(kinds(HookJarHarness.events(p)).isEmpty());
    }

    @Test
    void timeoutFailsOpenAndRecords() throws Exception {
        Path p = project();
        config(p, "{\"commands\":[\"sleep 30\"],\"timeoutSeconds\":1}");
        assertEquals(0, stop(p, false).exitCode());
        assertEquals(List.of("verify_error"), kinds(HookJarHarness.events(p)));
    }

    @Test
    void anUnapprovedConfigIsNeverExecutedByTheRealJar() throws Exception {
        Path p = project();
        Path marker = p.resolve("MUST-NOT-EXIST");
        // written WITHOUT approval, like a cloned repo (or the model) dropping the file in
        Files.writeString(p.resolve(".agentic-skills/verify.json"), "{\"commands\":[\"touch MUST-NOT-EXIST; exit 1\"]}");

        HookJarHarness.Result r = stop(p, false);

        assertEquals(0, r.exitCode(), "an untrusted config must allow the stop");
        assertFalse(Files.exists(marker), "and must not run its commands");
        List<UsageEvent> evs = HookJarHarness.events(p);
        assertEquals(List.of("verify_error"), kinds(evs));
        assertEquals("untrusted_config", evs.get(0).reason);
    }

    @Test
    void changingAnApprovedConfigRevokesTheApproval() throws Exception {
        Path p = project();
        Path marker = p.resolve("INJECTED");
        config(p, "{\"commands\":[\"true\"]}");
        assertEquals(0, stop(p, false).exitCode());

        // a later edit (a pull, or the model writing the file) is not covered by the earlier approval
        Files.writeString(p.resolve(".agentic-skills/verify.json"), "{\"commands\":[\"touch INJECTED; exit 1\"]}");
        HookJarHarness.Result r = stop(p, false);

        assertEquals(0, r.exitCode());
        assertFalse(Files.exists(marker));
    }
}
