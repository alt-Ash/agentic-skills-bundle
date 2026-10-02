package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StopUsageIT {

    @TempDir
    Path tmp;

    @Test
    void stopEventCarriesRealTokenUsage() throws Exception {
        Path work = Files.createDirectory(tmp.resolve("work"));
        Path transcript = tmp.resolve("transcript.jsonl");
        Files.writeString(transcript, "{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}\n"
            + "{\"type\":\"assistant\",\"message\":{\"model\":\"claude-test\",\"usage\":"
            + "{\"input_tokens\":11,\"output_tokens\":22,\"cache_read_input_tokens\":33,\"cache_creation_input_tokens\":44}}}\n");

        HookJarHarness.Result r = HookJarHarness.run(work, "stop", Map.of(
            "session_id", "s-stop-usage",
            "hook_event_name", "Stop",
            "transcript_path", transcript.toString(),
            "last_assistant_message", "done"));
        assertEquals(0, r.exitCode());

        List<UsageEvent> events = HookJarHarness.events(work);
        UsageEvent e = events.stream().filter(x -> "turn_stop".equals(x.event)).findFirst().orElseThrow();
        assertEquals("claude-test", e.model);
        assertEquals(11, e.inputTokens);
        assertEquals(22, e.outputTokens);
        assertEquals(33, e.cacheReadTokens);
        assertEquals(44, e.cacheCreationTokens);
        assertEquals(77, e.cachedTokens);
    }
}
