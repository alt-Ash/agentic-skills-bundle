package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * As of the Java/Spring Boot content refactor (2026-09-30), {@link
 * SkillMcpRegistry#ALL} is empty (its prior entries — the three figma-*
 * skills sharing one entries map, plus mui-migration — were all for deleted
 * skills with no Java successor) — these tests confirm that empty state
 * behaves correctly rather than testing removed content.
 */
class SkillMcpRegistryTest {

    @Test
    void registryIsEmptyPostRefactor() {
        assertTrue(SkillMcpRegistry.ALL.isEmpty());
    }

    @Test
    void anySkillReturnsEmptyMap() {
        assertTrue(SkillMcpRegistry.serversFor("spring-boot-best-practices", "opencode").isEmpty());
        assertTrue(SkillMcpRegistry.serversFor("no-such-skill", "claude").isEmpty());
    }
}
