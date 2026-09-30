package dev.dorrian.agenticskillscli.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code claude} CLI is not guaranteed to be on PATH in this build
 * environment, so these tests only assert the graceful-failure contract
 * (no exception escapes, a sane result is still returned) rather than a
 * real registration round-trip — matching how the original's {@code
 * execFileAsync('claude', ...)} calls are always wrapped in try/catch.
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
        // Either succeeds (claude CLI present and worked) or fails gracefully — must not throw.
        assertTrue(result.name().equals("test-server"));
    }
}
