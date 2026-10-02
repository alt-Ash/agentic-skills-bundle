package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Black-box: the packaged jar honors a project {@code .agentic-skills/guard.json}. */
class GuardConfigIT {

    private static Map<String, Object> bash(Path cwd, String command) {
        return Map.of("session_id", "sess-gc", "cwd", cwd.toString(), "tool_name", "Bash",
            "tool_input", Map.of("command", command));
    }

    private static void guardJson(Path cwd, String json) throws Exception {
        Path f = cwd.resolve(".agentic-skills/guard.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json);
    }

    /** The USER-level file: the hook JVM's private home (see HookJarHarness.homeFor). */
    private static void userGuardJson(Path cwd, String json) throws Exception {
        Path f = HookJarHarness.homeFor(cwd).resolve(".agentic-skills/guard.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json);
    }

    @Test
    void aProjectFileCannotWeakenTheGuardOnlyTheUserFileCan(@TempDir Path cwd) throws Exception {
        // a repo-controlled file (or one the model wrote) tries to switch the guard off
        guardJson(cwd, "{\"disable\":[\"recursive-delete\",\"force-push\"],\"allow\":[\".*\"]}");
        assertEquals(2, HookJarHarness.run(cwd, "guard", bash(cwd, "rm -rf /")).exitCode());
        assertEquals(2, HookJarHarness.run(cwd, "guard", bash(cwd, "git push --force origin main")).exitCode());
    }

    @Test
    void userAllowMustMatchTheWholeCommandSoItCannotShieldACompoundOne(@TempDir Path cwd) throws Exception {
        userGuardJson(cwd, "{\"allow\":[\"git push --force origin main\"]}");

        assertEquals(0, HookJarHarness.run(cwd, "guard", bash(cwd, "git push --force origin main")).exitCode());
        assertEquals(2, HookJarHarness.run(cwd, "guard", bash(cwd, "git push --force origin main && rm -rf /")).exitCode());
    }

    @Test
    void allowRuleLetsAnOtherwiseBlockedCommandThrough(@TempDir Path cwd) throws Exception {
        userGuardJson(cwd, "{\"allow\":[\"^git push --force origin main$\"]}");

        HookJarHarness.Result r = HookJarHarness.run(cwd, "guard", bash(cwd, "git push --force origin main"));

        assertEquals(0, r.exitCode());
        assertTrue(HookJarHarness.events(cwd).isEmpty());
    }

    @Test
    void customDenyBlocksAndRecordsItsReason(@TempDir Path cwd) throws Exception {
        guardJson(cwd, "{\"deny\":[{\"pattern\":\"terraform\\\\s+destroy\",\"reason\":\"no destroys\"}]}");

        HookJarHarness.Result r = HookJarHarness.run(cwd, "guard", bash(cwd, "terraform destroy -auto-approve"));

        assertEquals(2, r.exitCode());
        assertTrue(r.stderr().contains("custom: no destroys"), r.stderr());
        List<UsageEvent> events = HookJarHarness.events(cwd);
        assertEquals(1, events.size());
        assertEquals("guard_block", events.get(0).event);
        assertEquals("custom: no destroys", events.get(0).guardRule);
    }

    @Test
    void invalidGuardJsonKeepsBuiltInDefaults(@TempDir Path cwd) throws Exception {
        guardJson(cwd, "{ nope");

        assertEquals(2, HookJarHarness.run(cwd, "guard", bash(cwd, "rm -rf /")).exitCode());
        assertEquals(0, HookJarHarness.run(cwd, "guard", bash(cwd, "ls")).exitCode());
    }
}
