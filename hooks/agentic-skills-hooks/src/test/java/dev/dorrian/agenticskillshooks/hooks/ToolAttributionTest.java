package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolAttributionTest {

    private static UsageEvent apply(String json) {
        UsageEvent e = new UsageEvent();
        ToolAttribution.apply(HookInput.parse(json), e);
        return e;
    }

    @Test
    void skillToolSetsSkillName() {
        UsageEvent e = apply("{\"tool_name\":\"Skill\",\"tool_input\":{\"skill\":\"grilling\",\"args\":\"secret\"}}");
        assertEquals("grilling", e.skillName);
        assertNull(e.agentName);
    }

    @Test
    void taskAndAgentToolsSetAgentName() {
        for (String tool : new String[] {"Task", "Agent"}) {
            UsageEvent e = apply("{\"tool_name\":\"" + tool
                + "\",\"tool_input\":{\"subagent_type\":\"tdd-engineer\",\"prompt\":\"p\",\"description\":\"d\"}}");
            assertEquals("tdd-engineer", e.agentName);
            assertNull(e.skillName);
        }
    }

    @Test
    void missingToolInputLeavesNull() {
        assertNull(apply("{\"tool_name\":\"Skill\"}").skillName);
        assertNull(apply("{\"tool_name\":\"Task\"}").agentName);
    }

    @Test
    void wrongTypesLeaveNull() {
        assertNull(apply("{\"tool_name\":\"Skill\",\"tool_input\":{\"skill\":42}}").skillName);
        assertNull(apply("{\"tool_name\":\"Skill\",\"tool_input\":\"x\"}").skillName);
        assertNull(apply("{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":[\"a\"]}}").agentName);
        assertNull(apply("{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"  \"}}").agentName);
    }

    @Test
    void otherToolsAndMissingToolNameAreIgnored() {
        UsageEvent e = apply("{\"tool_name\":\"Bash\",\"tool_input\":{\"skill\":\"x\",\"subagent_type\":\"y\"}}");
        assertNull(e.skillName);
        assertNull(e.agentName);
        assertNull(apply("{\"tool_input\":{\"skill\":\"x\"}}").skillName);
    }
}
