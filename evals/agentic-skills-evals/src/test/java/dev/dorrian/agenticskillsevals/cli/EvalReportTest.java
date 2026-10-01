package dev.dorrian.agenticskillsevals.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode record(boolean passed, double judgeScore, int inputTokens, int outputTokens,
                                    double costUsd, long durationMs, String gitSha) throws Exception {
        String json = """
                {"passed":%s,"judgeScore":%s,"tokenUsage":{"inputTokens":%d,"outputTokens":%d,"estimatedCostUsd":%s},
                 "durationMs":%d,"gitSha":"%s"}
                """.formatted(passed, judgeScore, inputTokens, outputTokens, costUsd, durationMs, gitSha);
        return MAPPER.readTree(json);
    }

    @Test
    void windowStats_emptyList_returnsZeroedStats() {
        EvalReport.WindowStats stats = EvalReport.WindowStats.of(List.of());
        assertEquals(0, stats.passRate());
        assertEquals(0, stats.meanJudgeScore());
        assertEquals(0, stats.meanTotalTokens());
    }

    @Test
    void windowStats_computesMeansAndPassRateCorrectly() throws Exception {
        List<JsonNode> records = List.of(
                record(true, 5.0, 10, 100, 0.01, 1000, "abc123"),
                record(false, 3.0, 20, 200, 0.02, 2000, "abc123"));

        EvalReport.WindowStats stats = EvalReport.WindowStats.of(records);
        assertEquals(0.5, stats.passRate());
        assertEquals(4.0, stats.meanJudgeScore());
        assertEquals(165.0, stats.meanTotalTokens()); // (10+100 + 20+200) / 2
        assertEquals(0.015, stats.meanCostUsd(), 1e-9);
        assertEquals(1, stats.gitShas().size());
    }

    @Test
    void classifyAlert_noDeltaExceedsThresholds_returnsNull() {
        assertNull(EvalReport.classifyAlert("a::b", -0.1, -0.05, 0.05));
    }

    @Test
    void classifyAlert_judgeDropAtThreshold_isRegression() {
        String alert = EvalReport.classifyAlert("a::b", -0.3, 0, 0);
        assertTrue(alert.startsWith("QUALITY REGRESSION"), alert);
    }

    @Test
    void classifyAlert_passRateDropAtThreshold_isRegression() {
        String alert = EvalReport.classifyAlert("a::b", 0, -0.10, 0);
        assertTrue(alert.startsWith("QUALITY REGRESSION"), alert);
    }

    @Test
    void classifyAlert_tokensDownAtThreshold_isEfficiencyWin() {
        String alert = EvalReport.classifyAlert("a::b", 0, 0, -0.10);
        assertTrue(alert.startsWith("EFFICIENCY WIN"), alert);
    }

    @Test
    void classifyAlert_tokensUpAtThreshold_isTokenSpike() {
        String alert = EvalReport.classifyAlert("a::b", 0, 0, 0.10);
        assertTrue(alert.startsWith("TOKEN SPIKE"), alert);
    }

    @Test
    void classifyAlert_regressionTakesPriorityOverTokenDrop() {
        // A judge-score regression that also happens to use fewer tokens is still a regression,
        // not a win - the win/spike branches must not shadow it.
        String alert = EvalReport.classifyAlert("a::b", -0.5, 0, -0.20);
        assertTrue(alert.startsWith("QUALITY REGRESSION"), alert);
    }

    @Test
    void classifyAlert_justBelowThresholds_returnsNull() {
        assertNull(EvalReport.classifyAlert("a::b", -0.29, -0.09, 0.09));
    }
}
