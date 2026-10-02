package dev.dorrian.agenticskillsevals.parallel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zero-cost: canned stream-json transcripts, no model calls. */
class DispatchAnalysisTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode assistant(String msgId, String toolName, String inputJson) throws Exception {
        return M.readTree("""
                {"type":"assistant","message":{"id":"%s","content":[
                  {"type":"tool_use","id":"t","name":"%s","input":%s}]}}""".formatted(msgId, toolName, inputJson));
    }

    @Test
    void agentCallsSharingAMessageIdFormOneParallelBatch() throws Exception {
        // stream-json emits one event per content block; same message.id == same model turn.
        List<JsonNode> transcript = List.of(
                assistant("m1", "Agent", "{\"description\":\"a\",\"prompt\":\"A\",\"isolation\":\"worktree\"}"),
                assistant("m1", "Agent", "{\"description\":\"b\",\"prompt\":\"B\",\"isolation\":\"worktree\"}"),
                assistant("m1", "Agent", "{\"description\":\"c\",\"prompt\":\"C\"}"));

        DispatchAnalysis a = DispatchAnalysis.of(transcript);

        assertEquals(1, a.batches().size());
        assertEquals(3, a.largestBatch().size());
        assertTrue(a.isParallel());
    }

    @Test
    void agentCallsInSeparateMessagesAreSequential() throws Exception {
        List<JsonNode> transcript = List.of(
                assistant("m1", "Agent", "{\"prompt\":\"A\"}"),
                assistant("m2", "Agent", "{\"prompt\":\"B\"}"));

        DispatchAnalysis a = DispatchAnalysis.of(transcript);

        assertEquals(2, a.batches().size());
        assertEquals(1, a.largestBatch().size());
        assertTrue(!a.isParallel());
    }

    @Test
    void taskIsTreatedAsAnAliasOfAgentAndOtherToolsAreIgnored() throws Exception {
        List<JsonNode> transcript = List.of(
                assistant("m1", "Task", "{\"prompt\":\"A\"}"),
                assistant("m1", "Bash", "{\"command\":\"git worktree add x\"}"),
                assistant("m1", "Agent", "{\"prompt\":\"B\"}"));

        assertEquals(2, DispatchAnalysis.of(transcript).largestBatch().size());
    }

    @Test
    void noAgentCallsMeansNoBatches() throws Exception {
        DispatchAnalysis a = DispatchAnalysis.of(List.of(assistant("m1", "Read", "{}")));
        assertTrue(a.batches().isEmpty());
        assertTrue(a.largestBatch().isEmpty());
        assertTrue(!a.isParallel());
    }
}
