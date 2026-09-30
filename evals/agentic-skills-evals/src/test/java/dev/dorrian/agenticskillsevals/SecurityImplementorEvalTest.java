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
                "fix_correctness", "Are the code fixes for all 10 findings technically correct? Key fixes: parameterized Spring Data query (not string-concatenated native SQL) for C-01, SecureRandom for C-02, @Value-injected secret (not hardcoded literal) for C-03, no SpEL/ScriptEngine evaluation of user input for C-04, path normalization/allowlist for C-05, explicit signature algorithm on the JWT parser for C-06, a SecurityFilterChain .headers(...) configuration for C-07, a rate limiter (e.g. Bucket4j/Resilience4j) for C-08, no exception message/stack trace in the response body for C-09, a specific CORS origin (not a wildcard) for C-10",
                "completeness", "Does the response address all 10 findings (C-01 through C-10)?",
                "handoff_update", "Is the HANDOFF BLOCK updated with \"fixed\" status for all findings?",
                "summary_quality", "Is the Fix Summary section present and well-formed with before/after vulnerability counts?");
    }

    @Override
    protected PrescannedPrefix prescannedPrefix() {
        return new PrescannedPrefix(
                "## Pre-baked execution results",
                "[EVAL MODE] The scenario contains \"## Pre-baked execution results\" with pre-run OWASP Dependency-Check and dependency-install outputs. Those ARE your complete Steps 2, 3, and 5 outputs. Do NOT call Bash, Edit, or Write. For Step 4: show each code fix as a before/after code block in your response. Proceed to Step 6 after all fixes are shown.\n\n");
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
        assertTrue(bool(checks, "hasCryptoFix"), "Response must include SecureRandom fix for C-02");
        assertTrue(bool(checks, "hasEnvSecretFix"), "Response must include @Value-injected secret fix for C-03");
        assertTrue(bool(checks, "hasHeadersFix"), "Response must include a .headers(...) security configuration fix for C-07");
    }
}
