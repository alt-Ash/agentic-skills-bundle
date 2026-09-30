package dev.dorrian.agenticskillscli.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surefire sets {@code AGENTIC_SKILLS_CLAUDE_CLI_OVERRIDE} to a nonexistent
 * binary (see pom.xml), so these tests exercise the graceful-failure contract
 * deterministically and never write to the developer's real {@code
 * ~/.claude.json}, even when the real {@code claude} CLI is on PATH.
 */
class ClaudeCliMcpRegistrarTest {

    @Test
    void existsReturnsFalseRatherThanThrowingWhenClaudeCliIsUnavailableOrServerIsUnknown() {
        assertFalse(ClaudeCliMcpRegistrar.exists("definitely-not-a-registered-server-xyz"));
    }

    @Test
    void uninstallNeverThrowsEvenWhenNothingIsRegistered() {
        OperationResult result = ClaudeCliMcpRegistrar.uninstall("definitely-not-a-registered-server-xyz");
        assertTrue(result.success()); // uninstall always reports success (skipped if not found), matching the original
    }

    @Test
    void installReturnsAResultRatherThanThrowingEvenWhenTheCliCallFails() {
        OperationResult result = ClaudeCliMcpRegistrar.install(
            "test-server", Map.of("command", "java", "args", java.util.List.of("-jar", "x.jar")), false
        );
        assertTrue(result.name().equals("test-server"));
        assertFalse(result.success(), "must fail gracefully against the overridden, nonexistent CLI");
    }
}
