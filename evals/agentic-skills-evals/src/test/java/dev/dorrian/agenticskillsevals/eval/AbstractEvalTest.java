package dev.dorrian.agenticskillsevals.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.agenticskillsevals.agent.AgentParser;
import dev.dorrian.agenticskillsevals.golden.GoldenChecker;
import dev.dorrian.agenticskillsevals.golden.GoldenSpec;
import dev.dorrian.agenticskillsevals.judge.Judge;
import dev.dorrian.agenticskillsevals.protocol.ChatResult;
import dev.dorrian.agenticskillsevals.protocol.ClaudeSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Port of {@code evals/behavioral/lib/eval-runner.ts}'s {@code runEval} lifecycle as a JUnit 5
 * {@code @TestFactory} base class. A concrete subclass supplies agent/scenario/rubric config via
 * overridable protected methods (more idiomatic Java than the TS original's function-valued object
 * fields) and gets one {@link DynamicTest} generated per scenario.
 *
 * <p>Each scenario: read the fixture prompt + golden spec, build a {@link ClaudeSession} (with any
 * per-scenario allowed-tools/MCP wiring), query it, run deterministic golden checks, run the judge
 * (skipped if golden checks failed), persist result + transcript JSON, assert baseline invariants,
 * run {@link #additionalAssertions}, then assert {@link #isPassed}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String PROVIDER_NAME = "claude-cli-control-protocol";
    private static String cachedGitSha;
    private static boolean gitShaResolved = false;

    private AgentParser.ParsedAgent agent;

    protected abstract String agentName();

    protected abstract Path agentPath();

    protected abstract Path fixturesDir();

    protected Path resultsDir() {
        return Path.of("results");
    }

    protected abstract List<String> scenarios();

    protected abstract Map<String, String> judgeCriteria();

    protected double judgeThreshold() {
        return 3;
    }

    protected PrescannedPrefix prescannedPrefix() {
        return null;
    }

    protected abstract boolean isPassed(String scenarioName, GoldenChecker.Result checks, Judge.Result judge);

    protected ScenarioOptions scenarioOptions(String scenarioName) {
        return ScenarioOptions.EMPTY;
    }

    protected void additionalAssertions(String scenarioName, GoldenChecker.Result checks, Judge.Result judge) {
        // no-op by default
    }

    /** Convenience for reading a golden-check detail flag by key, defaulting to false if absent. */
    protected static boolean bool(GoldenChecker.Result checks, String key) {
        return Boolean.TRUE.equals(checks.details().get(key));
    }

    private static String model() {
        String env = System.getenv("EVAL_MODEL");
        return (env != null && !env.isBlank()) ? env : "claude-haiku-4-5-20251001";
    }

    @BeforeAll
    void setUpAgentAndResultDirs() throws IOException {
        agent = AgentParser.parse(agentPath());
        Files.createDirectories(resultsDir());
        Files.createDirectories(resultsDir().resolve("history"));
        Files.createDirectories(resultsDir().resolve("transcripts"));
    }

    @TestFactory
    Stream<DynamicTest> behavioralScenarios() {
        return scenarios().stream()
                .map(scenarioName -> DynamicTest.dynamicTest(scenarioName, () -> runScenario(scenarioName)));
    }

    private void runScenario(String scenarioName) throws Exception {
        String scenario = Files.readString(fixturesDir().resolve(scenarioName + ".md"));
        GoldenSpec spec = GoldenSpec.load(fixturesDir().resolve(scenarioName + ".golden.yaml"));

        PrescannedPrefix prefix = prescannedPrefix();
        String systemPrompt = (prefix != null && scenario.contains(prefix.trigger()))
                ? prefix.prefix() + agent.systemPrompt()
                : agent.systemPrompt();

        ScenarioOptions opts = scenarioOptions(scenarioName);
        Map<String, String> criteria = opts.judgeCriteria() != null ? opts.judgeCriteria() : judgeCriteria();

        ClaudeSession.Builder builder = ClaudeSession.builder()
                .model(model())
                .systemPrompt(systemPrompt)
                .allowedTools(opts.allowedTools());
        if (opts.mcpBridge() != null) {
            builder.inProcessMcpServer(opts.mcpBridge());
        }

        ChatResult chatResult;
        long startedAt = System.currentTimeMillis();
        try (ClaudeSession session = builder.build()) {
            chatResult = session.query(scenario);
        }
        long durationMs = System.currentTimeMillis() - startedAt;

        long totalTokens = chatResult.inputTokens() + chatResult.outputTokens();
        assertTrue(totalTokens > 0, "Provider must return token usage");
        assertTrue(durationMs > 0, "Duration must be positive");

        GoldenChecker.Result checks = GoldenChecker.run(chatResult.text(), spec, chatResult.toolCallCounts());
        List<String> goldenFailures = checks.details().entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .toList();

        Judge.Result judgeResult;
        if (goldenFailures.isEmpty()) {
            Judge judge = new Judge(model());
            judgeResult = judge.judge(agent.frontmatter().description(), scenario, chatResult.text(),
                    criteria, judgeThreshold(), spec.referenceAnswer());
        } else {
            judgeResult = new Judge.Result(0, Map.of(), "Skipped: golden check failures", "", false);
        }

        String timestamp = Instant.now().toString();
        String safeTs = timestamp.replaceAll("[:.]", "-");
        String transcriptFileName = agentName() + "-" + scenarioName + "-" + safeTs + ".json";

        writeResult(scenarioName, goldenFailures, checks, chatResult, durationMs, judgeResult,
                timestamp, safeTs, transcriptFileName);
        writeTranscript(scenarioName, chatResult, safeTs);

        System.out.println("  Deterministic: " + checks.passed() + "/" + checks.total());
        System.out.println("  Judge: " + judgeResult.score() + "/5");
        judgeResult.dimensions().forEach((dim, score) -> System.out.println("    " + dim + ": " + score + "/5"));
        System.out.println("  Rationale: " + judgeResult.rationale());
        System.out.println("  Tokens: in=" + chatResult.inputTokens() + " out=" + chatResult.outputTokens()
                + " cost=$" + chatResult.totalCostUsd());
        System.out.println("  Duration: " + durationMs + "ms");
        if (!chatResult.toolCallCounts().isEmpty()) {
            System.out.println("  Tools: " + chatResult.toolCallCounts());
        }

        assertBaselineInvariant(checks, "notContains_I_cannot", "Response must not contain \"I cannot\"");
        assertBaselineInvariant(checks, "notContains_I_don_t_have_access", "Response must not contain \"I don't have access\"");
        assertBaselineInvariant(checks, "notContains_I_will_skip", "Response must not contain \"I will skip\"");
        assertBaselineInvariant(checks, "toolNotUsed_Write", "Agent must not use Write tool");
        assertBaselineInvariant(checks, "toolNotUsed_Edit", "Agent must not use Edit tool");
        assertBaselineInvariant(checks, "toolNotUsed_Bash", "Agent must not use Bash tool");

        additionalAssertions(scenarioName, checks, judgeResult);

        assertTrue(judgeResult.passed(), "Judge score " + judgeResult.score() + "/5: " + judgeResult.rationale());
    }

    private static void assertBaselineInvariant(GoldenChecker.Result checks, String key, String message) {
        Boolean value = checks.details().get(key);
        if (value != null) {
            assertTrue(value, message);
        }
    }

    private void writeResult(String scenarioName, List<String> goldenFailures, GoldenChecker.Result checks,
                              ChatResult chatResult, long durationMs, Judge.Result judgeResult,
                              String timestamp, String safeTs, String transcriptFileName) throws IOException {
        ObjectNode result = NODES.objectNode();
        result.put("agent", agentName());
        result.put("provider", PROVIDER_NAME);
        result.put("judgeModel", PROVIDER_NAME);
        result.put("model", model());
        result.put("gitSha", currentGitSha());
        result.put("scenario", scenarioName);

        ArrayNode failuresNode = NODES.arrayNode();
        goldenFailures.forEach(failuresNode::add);
        result.set("goldenFailures", failuresNode);

        ObjectNode deterministic = NODES.objectNode();
        ObjectNode detailsNode = NODES.objectNode();
        checks.details().forEach(detailsNode::put);
        deterministic.set("details", detailsNode);
        deterministic.put("passed", checks.passed());
        deterministic.put("total", checks.total());
        result.set("deterministicChecks", deterministic);

        ObjectNode tokenUsage = NODES.objectNode();
        tokenUsage.put("inputTokens", chatResult.inputTokens());
        tokenUsage.put("outputTokens", chatResult.outputTokens());
        tokenUsage.put("cacheReadTokens", chatResult.cacheReadTokens());
        tokenUsage.put("cacheCreationTokens", chatResult.cacheCreationTokens());
        tokenUsage.put("estimatedCostUsd", chatResult.totalCostUsd());
        result.set("tokenUsage", tokenUsage);

        ObjectNode toolCallsNode = NODES.objectNode();
        chatResult.toolCallCounts().forEach(toolCallsNode::put);
        result.set("toolCalls", toolCallsNode);

        result.put("durationMs", durationMs);
        result.put("judgeScore", judgeResult.score());
        ObjectNode dimensionsNode = NODES.objectNode();
        judgeResult.dimensions().forEach(dimensionsNode::put);
        result.set("judgeDimensions", dimensionsNode);
        result.put("judgeRationale", judgeResult.rationale());
        result.put("judgeReasoning", judgeResult.reasoning());
        result.put("agentResponse", chatResult.text());
        result.put("transcriptFile", "transcripts/" + transcriptFileName);
        result.put("passed", isPassed(scenarioName, checks, judgeResult));
        result.put("timestamp", timestamp);

        Path historyFile = resultsDir().resolve("history").resolve(agentName() + "-" + scenarioName + "-" + safeTs + ".json");
        Files.writeString(historyFile, result.toPrettyString());
    }

    private void writeTranscript(String scenarioName, ChatResult chatResult, String safeTs) throws IOException {
        ObjectNode transcriptDoc = NODES.objectNode();
        transcriptDoc.put("agent", agentName());
        transcriptDoc.put("scenario", scenarioName);
        transcriptDoc.put("provider", PROVIDER_NAME);
        ArrayNode transcriptArray = NODES.arrayNode();
        chatResult.transcript().forEach(transcriptArray::add);
        transcriptDoc.set("transcript", transcriptArray);

        Path transcriptFile = resultsDir().resolve("transcripts")
                .resolve(agentName() + "-" + scenarioName + "-" + safeTs + ".json");
        Files.writeString(transcriptFile, transcriptDoc.toPrettyString());
    }

    private static synchronized String currentGitSha() {
        if (gitShaResolved) {
            return cachedGitSha;
        }
        gitShaResolved = true;
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            cachedGitSha = process.exitValue() == 0 ? output : null;
        } catch (Exception e) {
            cachedGitSha = null;
        }
        return cachedGitSha;
    }
}
