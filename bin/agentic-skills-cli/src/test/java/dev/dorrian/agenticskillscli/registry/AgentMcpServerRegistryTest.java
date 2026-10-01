package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * As of the Java/Spring Boot content refactor (2026-09-30), {@link
 * AgentMcpServerRegistry#ALL} is empty (its two prior entries,
 * react-browser-debugger and figma-style-migrator, were both for deleted
 * agents with no Java successor) — these tests confirm that empty state
 * behaves correctly rather than testing removed content.
 */
class AgentMcpServerRegistryTest {

    @Test
    void registryIsEmptyPostRefactor() {
        assertTrue(AgentMcpServerRegistry.ALL.isEmpty());
    }

    @Test
    void anyAgentReturnsEmptyMap() {
        assertTrue(AgentMcpServerRegistry.serversFor("spring-boot-backend-engineer", "opencode").isEmpty());
        assertTrue(AgentMcpServerRegistry.serversFor("no-such-agent", "opencode").isEmpty());
    }

    @Test
    void unknownAgentOrToolReturnsEmptyMap() {
        Map<String, Object> result = AgentMcpServerRegistry.serversFor("no-such-agent", "windsurf");
        assertTrue(result.isEmpty());
    }
}
