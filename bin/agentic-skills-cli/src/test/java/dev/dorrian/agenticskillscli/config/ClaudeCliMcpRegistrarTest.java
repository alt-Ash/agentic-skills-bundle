package dev.dorrian.agenticskillscli.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void stdioServerArgsPutEnvBeforeTransportAndCommandAfterDoubleDash() {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("type", "stdio");
        cfg.put("command", "engram");
        cfg.put("args", List.of("mcp"));
        cfg.put("env", Map.of("K", "v"));
        assertEquals(
            List.of("mcp", "add", "--scope", "user", "-e", "K=v", "--transport", "stdio", "engram", "--", "engram", "mcp"),
            ClaudeCliMcpRegistrar.addArgs("engram", cfg)
        );
    }

    @Test
    void httpServerArgsUseHttpTransportAndUrlWithoutHeaders() {
        assertEquals(
            List.of("mcp", "add", "--scope", "user", "--transport", "http", "figma-mcp", "https://mcp.figma.com/mcp"),
            ClaudeCliMcpRegistrar.addArgs("figma-mcp", Map.of("type", "http", "url", "https://mcp.figma.com/mcp"))
        );
    }

    @Test
    void httpServerArgsPutVariadicHeaderFlagsBeforeTransportSoTheyCannotSwallowNameAndUrl() {
        Map<String, Object> cfg = Map.of(
            "type", "http",
            "url", "https://mcp.context7.com/mcp",
            "headers", Map.of("Authorization", "Bearer abc123")
        );
        assertEquals(
            List.of("mcp", "add", "--scope", "user", "--header", "Authorization: Bearer abc123",
                "--transport", "http", "context7", "https://mcp.context7.com/mcp"),
            ClaudeCliMcpRegistrar.addArgs("context7", cfg)
        );
    }
}
