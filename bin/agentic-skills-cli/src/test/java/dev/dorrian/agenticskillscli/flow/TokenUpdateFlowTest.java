package dev.dorrian.agenticskillscli.flow;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenUpdateFlowTest {

    @Test
    void movesClaudeToTheEndPreservingOtherOrder() {
        Set<String> keys = new LinkedHashSet<>(List.of("opencode", "claude", "cursor", "zed"));
        assertEquals(List.of("opencode", "cursor", "zed", "claude"), TokenUpdateFlow.claudeLastToolKeyOrder(keys));
    }

    @Test
    void leavesOrderUntouchedWhenClaudeAbsent() {
        Set<String> keys = new LinkedHashSet<>(List.of("opencode", "cursor"));
        assertEquals(List.of("opencode", "cursor"), TokenUpdateFlow.claudeLastToolKeyOrder(keys));
    }

    @Test
    void handlesEmptyInput() {
        assertEquals(List.of(), TokenUpdateFlow.claudeLastToolKeyOrder(Set.of()));
    }

    @Test
    void handlesClaudeOnly() {
        Set<String> keys = new LinkedHashSet<>(List.of("claude"));
        assertEquals(List.of("claude"), TokenUpdateFlow.claudeLastToolKeyOrder(keys));
    }
}
