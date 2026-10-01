package dev.dorrian.agenticskillsevals;

import dev.dorrian.agenticskillsevals.eval.AbstractEvalTest;
import dev.dorrian.agenticskillsevals.eval.PrescannedPrefix;
import dev.dorrian.agenticskillsevals.golden.GoldenChecker;
import dev.dorrian.agenticskillsevals.judge.Judge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code evals/behavioral/tdd-engineer.eval.ts}. */
class TddEngineerEvalTest extends AbstractEvalTest {

    @Override
    protected String agentName() {
        return "tdd-engineer";
    }

    @Override
    protected Path agentPath() {
        return Path.of("../../agents/tdd-engineer.md");
    }

    @Override
    protected Path fixturesDir() {
        return Path.of("target/test-classes/fixtures/tdd-engineer");
    }

    @Override
    protected List<String> scenarios() {
        return List.of("scenario-01-new-api-endpoint");
    }

    @Override
    protected Map<String, String> judgeCriteria() {
        return Map.of(
                "test_quality", "Does the test file include 4 cases using MockMvc (@WebMvcTest): (1) valid body returns 200 with user object containing id/name/email, (2) missing name returns 400 with error message, (3) missing email returns 400 with error message, (4) invalid email format returns 400?",
                "red_phase", "Does the response explain why the build failed to compile (cannot find symbol — production class does not exist yet)?",
                "implementation", "Is the POST /api/users route correct? It must: return 200 with { id, name, email } on valid input, return 400 for missing name, 400 for missing email, 400 for invalid email format.",
                "handoff_quality", "Is the HANDOFF BLOCK present with Status: completed, correct file paths for both test and route files, and check rows for tests/lint/typecheck/build all marked as passed?");
    }

    @Override
    protected PrescannedPrefix prescannedPrefix() {
        return new PrescannedPrefix(
                "## Pre-baked execution results",
                "[EVAL MODE] The scenario contains \"## Pre-baked execution results\" with pre-run test suite and project check outputs. Those ARE your Phase 1 test run, Phase 3 full suite, and Phase 5 check outputs. Do NOT call Bash, Edit, or Write. Show test code and production code as code blocks in your response. The pre-baked results confirm: Phase 1 build fails to compile (cannot find symbol — production class missing), Phase 3 all 5 tests pass, Phase 5 all checks exit 0.\n\n");
    }

    @Override
    protected boolean isPassed(String scenarioName, GoldenChecker.Result checks, Judge.Result judge) {
        return bool(checks, "hasHandoffBlock") && bool(checks, "hasRedConfirmation")
                && bool(checks, "hasMockMvcUsage") && judge.passed();
    }

    @Override
    protected void additionalAssertions(String scenarioName, GoldenChecker.Result checks, Judge.Result judge) {
        assertTrue(bool(checks, "hasHandoffBlock"), "Response must include HANDOFF BLOCK");
        assertTrue(bool(checks, "hasRedConfirmation"), "Response must include Phase 2 red confirmation");
        assertTrue(bool(checks, "hasMockMvcUsage"), "Response must include MockMvc in test code");
        assertTrue(bool(checks, "hasTestFile"), "Response must reference UserControllerTest");
        assertTrue(bool(checks, "has200Response"), "Response must include isOk() in test code");
        assertTrue(bool(checks, "hasCompletedStatus"), "HANDOFF BLOCK must have Status: completed");
    }
}
