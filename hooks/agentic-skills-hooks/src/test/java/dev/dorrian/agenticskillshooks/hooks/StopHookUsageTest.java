package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StopHookUsageTest {

    @TempDir
    Path tmp;

    @Test
    void copiesRealUsageFromTranscript() throws Exception {
        Path t = tmp.resolve("t.jsonl");
        Files.writeString(t, "{\"type\":\"assistant\",\"message\":{\"model\":\"claude-x\",\"usage\":"
            + "{\"input_tokens\":10,\"output_tokens\":20,\"cache_read_input_tokens\":30,\"cache_creation_input_tokens\":40}}}\n");
        UsageEvent e = new UsageEvent();
        StopHook.applyUsage(HookInput.parse("{\"transcript_path\":\"" + t + "\"}"), e);
        assertEquals("claude-x", e.model);
        assertEquals(10, e.inputTokens);
        assertEquals(20, e.outputTokens);
        assertEquals(30, e.cacheReadTokens);
        assertEquals(40, e.cacheCreationTokens);
        assertEquals(70, e.cachedTokens);
    }

    @Test
    void missingTranscriptLeavesEventUntouched() {
        UsageEvent e = new UsageEvent();
        StopHook.applyUsage(HookInput.parse("{\"transcript_path\":\"/nonexistent/x.jsonl\"}"), e);
        StopHook.applyUsage(HookInput.parse("{}"), e);
        assertNull(e.model);
        assertNull(e.inputTokens);
        assertNull(e.outputTokens);
        assertNull(e.cacheReadTokens);
        assertNull(e.cacheCreationTokens);
        assertNull(e.cachedTokens);
    }

    @Test
    void outputTokensAreTheWholeTurnNotJustTheLastMessage() throws Exception {
        Path t = tmp.resolve("turn.jsonl");
        Files.writeString(t, String.join("\n",
            "{\"type\":\"user\",\"message\":{\"content\":\"go\"}}",
            "{\"type\":\"assistant\",\"message\":{\"id\":\"a\",\"model\":\"m\",\"usage\":{\"output_tokens\":300}}}",
            "{\"type\":\"assistant\",\"message\":{\"id\":\"b\",\"model\":\"m\",\"usage\":{\"output_tokens\":20}}}") + "\n");
        UsageEvent e = new UsageEvent();
        StopHook.applyUsage(HookInput.parse("{\"transcript_path\":\"" + t + "\"}"), e);
        assertEquals(320, e.outputTokens);
    }
}
