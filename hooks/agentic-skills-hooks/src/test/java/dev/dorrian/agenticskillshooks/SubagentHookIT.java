package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Black-box ITs of skill/agent attribution against the packaged jar. */
class SubagentHookIT {

    @TempDir
    Path tmp;

    private Path work() throws Exception {
        return Files.createDirectory(tmp.resolve("work"));
    }

    private static Map<String, Object> base(String event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", "s-1");
        m.put("hook_event_name", event);
        return m;
    }

    @Test
    void skillCallIsAttributed() throws Exception {
        Path w = work();
        Map<String, Object> p = base("PostToolUse");
        p.put("tool_name", "Skill");
        p.put("tool_input", Map.of("skill", "grilling"));
        p.put("tool_use_id", "tu-1");
        assertEquals(0, HookJarHarness.run(w, "post-tool-use", p).exitCode());
        List<UsageEvent> ev = HookJarHarness.events(w);
        assertEquals(1, ev.size());
        assertEquals("tool_use", ev.get(0).event);
        assertEquals("grilling", ev.get(0).skillName);
        assertNull(ev.get(0).agentName);
    }

    @Test
    void taskCallIsAttributedWithoutPromptText() throws Exception {
        Path w = work();
        Map<String, Object> p = base("PostToolUse");
        p.put("tool_name", "Task");
        p.put("tool_input", Map.of("subagent_type", "tdd-engineer", "prompt", "TOPSECRET", "description", "d"));
        assertEquals(0, HookJarHarness.run(w, "post-tool-use", p).exitCode());
        List<UsageEvent> ev = HookJarHarness.events(w);
        assertEquals(1, ev.size());
        assertEquals("tdd-engineer", ev.get(0).agentName);
        assertNull(ev.get(0).skillName);
        assertNull(ev.get(0).command);
    }

    @Test
    void failedSkillCallIsAttributed() throws Exception {
        Path w = work();
        Map<String, Object> p = base("PostToolUseFailure");
        p.put("tool_name", "Skill");
        p.put("tool_input", Map.of("skill", "nope"));
        p.put("error", "unknown skill");
        assertEquals(0, HookJarHarness.run(w, "post-tool-use-failure", p).exitCode());
        List<UsageEvent> ev = HookJarHarness.events(w);
        assertEquals(1, ev.size());
        assertEquals("tool_failure", ev.get(0).event);
        assertEquals("nope", ev.get(0).skillName);
    }

    @Test
    void subagentStartAndStopAreRecorded() throws Exception {
        Path w = work();
        Map<String, Object> start = base("SubagentStart");
        start.put("agent_id", "agent-abc");
        start.put("agent_type", "Explore");
        HookJarHarness.Result r1 = HookJarHarness.run(w, "subagent", start);
        Map<String, Object> stop = base("SubagentStop");
        stop.put("agent_id", "agent-abc");
        stop.put("agent_type", "Explore");
        stop.put("last_assistant_message", "TOPSECRET");
        stop.put("stop_hook_active", false);
        HookJarHarness.Result r2 = HookJarHarness.run(w, "subagent", stop);
        assertEquals(0, r1.exitCode());
        assertEquals(0, r2.exitCode());
        assertEquals("", r1.stdout());
        List<UsageEvent> ev = HookJarHarness.events(w);
        assertEquals(2, ev.size());
        assertEquals("subagent_start", ev.get(0).event);
        assertEquals("subagent_stop", ev.get(1).event);
        for (UsageEvent e : ev) {
            assertEquals("Explore", e.agentName);
            assertEquals("agent-abc", e.toolUseId);
            assertEquals("s-1", e.sessionId);
        }
    }

    @Test
    void unknownEventNameWritesNothingAndExitsZero() throws Exception {
        Path w = work();
        assertEquals(0, HookJarHarness.run(w, "subagent", base("Other")).exitCode());
        assertEquals(0, HookJarHarness.events(w).size());
    }
}
