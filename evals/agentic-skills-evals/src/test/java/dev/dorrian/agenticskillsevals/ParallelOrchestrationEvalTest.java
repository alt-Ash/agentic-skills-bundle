package dev.dorrian.agenticskillsevals;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dorrian.agenticskillsevals.agent.AgentParser;
import dev.dorrian.agenticskillsevals.parallel.DispatchAnalysis;
import dev.dorrian.agenticskillsevals.protocol.ChatResult;
import dev.dorrian.agenticskillsevals.protocol.ClaudeSession;
import dev.dorrian.agenticskillsevals.protocol.HookDecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral eval (REAL BILLED model calls) for dev-orchestrator + the parallel-feature-build
 * skill: does the model actually fan slices out as concurrent sub-agents when the Phase 0 gate
 * passes, and refuse to when it fails?
 *
 * <p>"Concurrent" means several {@code Agent} spawns in one assistant turn (see
 * {@link DispatchAnalysis}). A PreToolUse hook denies every spawn, shell and write so the eval
 * records the model's dispatch decision without running workers or touching this repo - one
 * orchestrator turn per scenario, so it is cheap.
 *
 * <p>Scope: the skill text is placed in the system prompt (as if already loaded), so this measures
 * whether the model <em>follows</em> the skill. Whether it chooses to load it is a separate eval.
 * Model: {@code EVAL_MODEL}, default {@code claude-sonnet-5-5} (orchestration needs more than Haiku).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParallelOrchestrationEvalTest {

    private static final Path FIXTURES = Path.of("target/test-classes/fixtures/parallel-orchestration");

    private static final String EVAL_PREFIX = """
            [EVAL MODE] The `parallel-feature-build` skill is already loaded (below). No ticket tool, \
            OpenSpec or user is available: skip Phase 2/3 and any planning question, and act on the \
            request. Shell, Write, Edit and Agent calls are intercepted and denied so your dispatch can \
            be recorded; a denial is expected - do not retry the same call, and state your decision.

            """;

    private String systemPrompt;

    @BeforeAll
    void load() throws Exception {
        var orchestrator = AgentParser.parse(Path.of("../../agents/dev-orchestrator.md"));
        String skillMd = Files.readString(Path.of("../../skills/workflow/parallel-feature-build/SKILL.md"));
        systemPrompt = EVAL_PREFIX + orchestrator.systemPrompt() + "\n\n---\n# Loaded skill\n" + skillMd;
    }

    private static final String JAVA_SPECIALIST = "spring-boot-backend-engineer";
    private static final String GENERAL = "general-purpose";

    @Test
    void javaSlicesRouteToTheSpringSpecialistInConcurrentWorktrees() throws Exception {
        Run run = run("scenario-01-disjoint-slices");
        assertConcurrentWorktreeBatch(run, 3);
        assertEquals(List.of(JAVA_SPECIALIST, JAVA_SPECIALIST, JAVA_SPECIALIST), types(run),
                "every Java/Spring slice should go to the specialist\n" + run.text());
        assertCoversSlices(run, "OrderController", "OrderService", "OrderRepository");
    }

    @Test
    void sliceWithNoMatchingSpecialistFallsBackToGeneralPurpose() throws Exception {
        Run run = run("scenario-03-mixed-slices");
        assertConcurrentWorktreeBatch(run, 3);
        assertEquals(List.of(GENERAL, JAVA_SPECIALIST, JAVA_SPECIALIST), types(run),
                "two Java slices -> specialist, the Markdown docs slice -> general-purpose\n" + run.text());
        assertTrue(run.dispatch().largestBatch().stream()
                        .filter(i -> GENERAL.equals(type(i)))
                        .allMatch(i -> i.path("prompt").asText("").contains("docs/api/orders.md")),
                "the general-purpose worker must be the docs slice");
    }

    @Test
    void missingSpecialistFallsBackToGeneralPurposeWithoutLosingParallelism() throws Exception {
        Run run = run("scenario-04-no-specialist-installed");
        assertConcurrentWorktreeBatch(run, 3);
        assertEquals(List.of(GENERAL, GENERAL, GENERAL), types(run),
                "only general-purpose is installed, so it must be used - never an unavailable agent\n" + run.text());
    }

    private static String type(JsonNode input) {
        String t = input.path("subagent_type").asText("");
        return t.isEmpty() ? GENERAL : t; // omitted subagent_type == general-purpose
    }

    private static List<String> types(Run run) {
        return run.dispatch().largestBatch().stream().map(ParallelOrchestrationEvalTest::type).sorted().toList();
    }

    /** Concurrency + the skill's worktree/commit contract, independent of which agent type was chosen. */
    private static void assertConcurrentWorktreeBatch(Run run, int expectedWorkers) {
        List<JsonNode> batch = run.dispatch().largestBatch();
        assertEquals(expectedWorkers, batch.size(), "expected one concurrent turn with " + expectedWorkers
                + " workers; batches=" + run.dispatch().batches().size() + "\n" + run.text());
        for (JsonNode input : batch) {
            assertEquals("worktree", input.path("isolation").asText(""),
                    "every worker must use isolation: \"worktree\"");
        }
        // Claude Code creates and cleans up the worktree itself: pre-creating one with git as well
        // yields a second, unused worktree/branch and an empty merge.
        assertTrue(run.bashCommands().stream().noneMatch(c -> c.contains("worktree add")),
                "must not run `git worktree add` when isolation: \"worktree\" is used: " + run.bashCommands());
        // Isolated workers hand back a branch to merge, so each brief must tell the worker to commit.
        for (JsonNode input : batch) {
            assertTrue(input.path("prompt").asText("").toLowerCase().contains("commit"),
                    "worker brief must instruct the worker to commit its work:\n" + input.path("prompt").asText(""));
        }
        assertFalse(hasDuplicatePrompts(batch), "worker prompts must differ per slice");
    }

    private static void assertCoversSlices(Run run, String... classes) {
        for (String cls : classes) {
            assertTrue(run.dispatch().largestBatch().stream().anyMatch(i -> i.path("prompt").asText("").contains(cls)),
                    "no worker covers " + cls);
        }
    }

    @Test
    void slicesSharingPomAreRejectedAndNotRunConcurrently() throws Exception {
        Run run = run("scenario-02-shared-pom");

        assertFalse(run.dispatch().isParallel(),
                "overlap on pom.xml must trigger sequential fallback; got concurrent batch of "
                        + run.dispatch().largestBatch().size() + "\n" + run.text());
        assertTrue(Pattern.compile("pom\\.xml").matcher(run.text()).find()
                        && Pattern.compile("(?i)sequential").matcher(run.text()).find(),
                "response must name pom.xml as the overlap and the sequential fallback\n" + run.text());
    }

    // ---- plumbing ------------------------------------------------------------------------

    private record Run(String text, DispatchAnalysis dispatch, List<String> bashCommands) {
    }

    private Run run(String scenario) throws Exception {
        String prompt = Files.readString(FIXTURES.resolve(scenario + ".md"));
        List<String> denied = new CopyOnWriteArrayList<>();
        List<String> bash = new CopyOnWriteArrayList<>();
        ChatResult result;
        try (ClaudeSession session = ClaudeSession.builder()
                .model(System.getenv("EVAL_MODEL") != null && !System.getenv("EVAL_MODEL").isBlank()
                        ? System.getenv("EVAL_MODEL") : "claude-sonnet-5-5")
                .systemPrompt(systemPrompt)
                .registerPreToolUseHook(null, (tool, input) -> switch (tool) {
                    case "Agent", "Task", "Bash", "Write", "Edit" -> {
                        denied.add(tool);
                        if (tool.equals("Bash")) bash.add(input.path("command").asText(""));
                        yield HookDecision.deny("EVAL: " + tool + " call recorded and not executed.");
                    }
                    default -> HookDecision.allow();
                })
                .build()) {
            result = session.query(prompt);
        }
        Path out = Path.of("results", "parallel-orchestration");
        Files.createDirectories(out);
        Files.writeString(out.resolve(scenario + ".json"), new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                        "text", result.text(), "dispatchBatches", DispatchAnalysis.of(result.transcript()).batches())));
        System.out.println("  [" + scenario + "] denied=" + denied + " cost=$" + result.totalCostUsd());
        return new Run(result.text(), DispatchAnalysis.of(result.transcript()), List.copyOf(bash));
    }

    private static boolean hasDuplicatePrompts(List<JsonNode> batch) {
        List<String> prompts = new ArrayList<>();
        batch.forEach(i -> prompts.add(i.path("prompt").asText("")));
        return prompts.stream().distinct().count() < prompts.size();
    }
}
