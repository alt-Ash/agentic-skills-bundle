package dev.dorrian.agenticskillsevals.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-subprocess tests against the actual {@code claude} CLI, verifying the control protocol
 * frame shapes documented in the {@code claude_cli_control_protocol} memory entry. These make
 * real, small, billed API calls on {@code claude-haiku-4-5-20251001} - kept deliberately few and
 * cheap (trivial echo-style prompts), per the migration plan's Phase 1 scope. A larger real-API
 * eval suite is Phase 2's job, gated more carefully.
 */
class ClaudeSessionRealSubprocessTest {

    private static final String MODEL = "claude-haiku-4-5-20251001";

    @Test
    @Timeout(60)
    void handshakeAndQueryOnlySessionWorksWithoutAnyHooksRegistered() throws Exception {
        try (ClaudeSession session = ClaudeSession.builder().model(MODEL).build()) {
            ChatResult result = session.query("Reply with exactly the word: pong");
            assertTrue(result.text().toLowerCase().contains("pong"), "expected 'pong' in: " + result.text());
            assertTrue(result.totalCostUsd() >= 0);
            assertNotNull(result.sessionId());
        }
    }

    @Test
    @Timeout(60)
    void preToolUseHookFiresAndAllowingLetsToolExecute() throws Exception {
        AtomicBoolean hookInvoked = new AtomicBoolean(false);
        AtomicInteger toolCallSeen = new AtomicInteger(0);

        try (ClaudeSession session = ClaudeSession.builder()
                .model(MODEL)
                .registerPreToolUseHook(null, (toolName, input) -> {
                    hookInvoked.set(true);
                    if ("Bash".equals(toolName)) {
                        toolCallSeen.incrementAndGet();
                    }
                    return HookDecision.allow();
                })
                .build()) {
            ChatResult result = session.query("Run the shell command: echo phase1-allow-check");

            assertTrue(hookInvoked.get(), "PreToolUse hook should have fired for a Bash tool call");
            assertTrue(toolCallSeen.get() > 0, "hook should have observed a Bash tool call");
            assertTrue(result.text().contains("phase1-allow-check"),
                    "expected the echoed marker in the final result when the hook allows: " + result.text());
        }
    }

    @Test
    @Timeout(60)
    void preToolUseHookDenyGenuinelyBlocksExecution() throws Exception {
        AtomicBoolean hookInvoked = new AtomicBoolean(false);

        try (ClaudeSession session = ClaudeSession.builder()
                .model(MODEL)
                .registerPreToolUseHook(null, (toolName, input) -> {
                    hookInvoked.set(true);
                    return HookDecision.deny("Phase 1 test: proving deny blocks execution");
                })
                .build()) {
            ChatResult result = session.query("Run the shell command: echo phase1-deny-check-MARKER");

            assertTrue(hookInvoked.get(), "PreToolUse hook should have fired");
            assertFalse(result.text().contains("phase1-deny-check-MARKER"),
                    "the echoed marker must NOT appear in the final result if the tool call was genuinely blocked: "
                            + result.text());

            boolean transcriptShowsBlockedToolResult = result.transcript().stream().anyMatch(msg ->
                    "user".equals(msg.path("type").asText(""))
                            && msg.path("message").path("content").isArray()
                            && java.util.stream.StreamSupport.stream(
                                    msg.path("message").path("content").spliterator(), false)
                            .anyMatch(block -> block.path("is_error").asBoolean(false)
                                    && block.path("content").asText("").contains("hook error")));
            assertTrue(transcriptShowsBlockedToolResult,
                    "transcript should contain a hook-error tool_result proving the block was real, not just acknowledged");
        }
    }
}
