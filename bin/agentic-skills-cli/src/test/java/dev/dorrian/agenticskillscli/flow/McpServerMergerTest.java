package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerMergerTest {

    @Test
    void mergeSkillServersReturnsEmptyForUnknownSkill() {
        SkillDescriptor skill = new SkillDescriptor("misc", "not-a-real-skill", Paths.get("/tmp"));
        assertTrue(McpServerMerger.mergeSkillServers(List.of(skill), "opencode").isEmpty());
    }

    // As of the Java/Spring Boot content refactor (2026-09-30), both
    // SkillMcpRegistry and AgentMcpServerRegistry are empty (their prior
    // entries were all for deleted frontend skills/agents with no Java
    // successor - spring-boot-backend-engineer verifies via actual execution,
    // not a browser, so it has no MCP wiring to register). There is currently
    // no skill or agent with real MCP-server entries to test a positive
    // resolution case against - these tests confirm the merge logic still
    // behaves correctly (returns empty, unions across multiple inputs
    // without throwing) against that now-empty registry state, rather than
    // asserting on removed content.

    @Test
    void mergeSkillServersAcrossMultipleSkillsStillEmptyPostRefactor() {
        SkillDescriptor a = new SkillDescriptor("backend", "spring-boot-best-practices", Paths.get("/tmp"));
        SkillDescriptor b = new SkillDescriptor("security", "secure-feature-gate", Paths.get("/tmp"));
        Map<String, Object> merged = McpServerMerger.mergeSkillServers(List.of(a, b), "opencode");
        assertTrue(merged.isEmpty());
    }

    @Test
    void mergeAgentServersStillEmptyPostRefactor() {
        AgentDescriptor agent = new AgentDescriptor("spring-boot-backend-engineer", Paths.get("/tmp/x.md"), Map.of());
        Map<String, Object> merged = McpServerMerger.mergeAgentServers(List.of(agent), "opencode");
        assertTrue(merged.isEmpty());
    }

    @Test
    void mergeAgentServersReturnsEmptyForUnknownAgent() {
        AgentDescriptor agent = new AgentDescriptor("not-a-real-agent", Paths.get("/tmp/x.md"), Map.of());
        assertTrue(McpServerMerger.mergeAgentServers(List.of(agent), "opencode").isEmpty());
    }
}
