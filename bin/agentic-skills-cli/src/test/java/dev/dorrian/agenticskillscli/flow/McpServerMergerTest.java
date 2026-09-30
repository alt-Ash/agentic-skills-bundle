package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerMergerTest {

    @Test
    void mergeSkillServersReturnsEmptyForUnknownSkill() {
        SkillDescriptor skill = new SkillDescriptor("misc", "not-a-real-skill", Paths.get("/tmp"));
        assertTrue(McpServerMerger.mergeSkillServers(List.of(skill), "opencode").isEmpty());
    }

    @Test
    void mergeSkillServersResolvesKnownSkill() {
        SkillDescriptor skill = new SkillDescriptor("frontend", "figma-design-to-code", Paths.get("/tmp"));
        Map<String, Object> merged = McpServerMerger.mergeSkillServers(List.of(skill), "opencode");
        assertTrue(merged.containsKey("figma-mcp"));
    }

    @Test
    void mergeSkillServersAcrossMultipleSkillsUnionsKeys() {
        SkillDescriptor figma = new SkillDescriptor("frontend", "figma-design-to-code", Paths.get("/tmp"));
        SkillDescriptor mui = new SkillDescriptor("frontend", "mui-migration", Paths.get("/tmp"));
        Map<String, Object> merged = McpServerMerger.mergeSkillServers(List.of(figma, mui), "opencode");
        assertTrue(merged.containsKey("figma-mcp"));
        assertTrue(merged.containsKey("mui-mcp"));
    }

    @Test
    void mergeAgentServersResolvesKnownAgent() {
        AgentDescriptor agent = new AgentDescriptor("react-browser-debugger", Paths.get("/tmp/x.md"), Map.of());
        Map<String, Object> merged = McpServerMerger.mergeAgentServers(List.of(agent), "opencode");
        assertEquals(2, merged.size());
        assertTrue(merged.containsKey("chrome-devtools"));
        assertTrue(merged.containsKey("playwright"));
    }

    @Test
    void mergeAgentServersReturnsEmptyForUnknownAgent() {
        AgentDescriptor agent = new AgentDescriptor("not-a-real-agent", Paths.get("/tmp/x.md"), Map.of());
        assertTrue(McpServerMerger.mergeAgentServers(List.of(agent), "opencode").isEmpty());
    }
}
