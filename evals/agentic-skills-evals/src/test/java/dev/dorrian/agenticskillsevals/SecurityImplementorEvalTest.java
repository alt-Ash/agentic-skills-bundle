package dev.dorrian.agenticskillsevals;

import dev.dorrian.agenticskillsevals.eval.AbstractEvalTest;
import dev.dorrian.agenticskillsevals.eval.PrescannedPrefix;
import dev.dorrian.agenticskillsevals.golden.GoldenChecker;
import dev.dorrian.agenticskillsevals.judge.Judge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code evals/behavioral/security-implementor.eval.ts}. */
class SecurityImplementorEvalTest extends AbstractEvalTest {

    @Override
    protected String agentName() {
        return "security-implementor";
    }

    @Override
    protected Path agentPath() {
        return Path.of("../../agents/security-implementor.md");
    }

    @Override
    protected Path fixturesDir() {
        return Path.of("target/test-classes/fixtures/security-implementor");
    }

    @Override
    protected List<String> scenarios() {
        return List.of("scenario-01-apply-code-fixes");
    }

    @Override
    protected Map<String, String> judgeCriteria() {
        return Map.of(
                "fix_correctness", "Are the code fixes for all 10 findings technically correct? Key fixes: parameterized query for C-01, crypto.randomBytes for C-02, process.env secret for C-03, no eval() for C-04, path.normalize/join for C-05, jwt algorithms array for C-06, specific CORS origin for C-07, helmet() for C-08, rateLimit middleware for C-09, no err.stack in response for C-10",
                "completeness", "Does the response address all 10 findings (C-01 through C-10)?",
                "handoff_update", "Is the HANDOFF BLOCK updated with \"fixed\" status for all findings?",
                "summary_quality", "Is the Fix Summary section present and well-formed with before/after vulnerability counts?");
    }

    @Override
    protected PrescannedPrefix prescannedPrefix() {
        return new PrescannedPrefix(
                "## Pre-baked execution results",
                "[EVAL MODE] The scenario contains \"## Pre-baked execution results\" with pre-run npm audit and install outputs. Those ARE your complete Steps 2, 3, and 5 outputs. Do NOT call Bash, Edit, or Write. For Step 4: show each code fix as a before/after code block in your response. Proceed to Step 6 after all fixes are shown.\n\n");
    }

    @Override
    protected boolean isPassed(String scenarioName, GoldenChecker.Result checks, Judge.Result judge) {
        return bool(checks, "hasFixSummary") && bool(checks, "hasHandoffBlock")
                && bool(checks, "hasFindingsFixed") && judge.passed();
    }

    @Override
    protected void additionalAssertions(String scenarioName, GoldenChecker.Result checks, Judge.Result judge) {
        assertTrue(bool(checks, "notContains_I_don_t_have_informa"), "Response must not contain \"I don't have information\"");
        assertTrue(bool(checks, "hasFixSummary"), "Response must include Fix Summary section");
        assertTrue(bool(checks, "hasHandoffBlock"), "Response must include HANDOFF BLOCK with schema");
        assertTrue(bool(checks, "hasFindingsFixed"), "Response must include findings marked \"fixed\"");
        assertTrue(bool(checks, "hasCryptoFix"), "Response must include crypto.randomBytes fix for C-02");
        assertTrue(bool(checks, "hasEnvSecretFix"), "Response must include process.env fix for C-03");
        assertTrue(bool(checks, "hasHelmetFix"), "Response must include helmet() fix for C-08");
    }
}
