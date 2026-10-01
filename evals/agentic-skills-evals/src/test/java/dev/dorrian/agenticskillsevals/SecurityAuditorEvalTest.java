package dev.dorrian.agenticskillsevals;

import dev.dorrian.agenticskillsevals.eval.AbstractEvalTest;
import dev.dorrian.agenticskillsevals.eval.PrescannedPrefix;
import dev.dorrian.agenticskillsevals.golden.GoldenChecker;
import dev.dorrian.agenticskillsevals.judge.Judge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code evals/behavioral/security-auditor.eval.ts}. */
class SecurityAuditorEvalTest extends AbstractEvalTest {

    @Override
    protected String agentName() {
        return "security-auditor";
    }

    @Override
    protected Path agentPath() {
        return Path.of("../../agents/security-auditor.md");
    }

    @Override
    protected Path fixturesDir() {
        return Path.of("target/test-classes/fixtures/security-auditor");
    }

    @Override
    protected List<String> scenarios() {
        return List.of("scenario-01-code-findings");
    }

    @Override
    protected Map<String, String> judgeCriteria() {
        return Map.of(
                "completeness", "Does the report identify all 4 planted vulnerabilities — java.util.Random token (CWE-338), SQL injection (CWE-89), no security headers config (CWE-693), no rate limiting (CWE-770)?",
                "classification", "Are catalog titles, severity levels (Critical/High), OWASP categories (A02/A03/A04/A05), and CWE numbers exactly correct per the fixed catalog?",
                "exploitation", "Are Exploit sections present for findings, with Status (Confirmed/Not Confirmed) matching the pre-baked results provided?",
                "handoff_block", "Is the HANDOFF BLOCK valid JSON with schema \"security-handoff/v1\" and a codeFindings array containing all 4 findings with correct severities?");
    }

    @Override
    protected PrescannedPrefix prescannedPrefix() {
        return new PrescannedPrefix(
                "## Pre-scanned project (Steps 1–3 complete)",
                "[EVAL MODE] The scenario contains \"## Pre-scanned project (Steps 1–3 complete)\" and \"## Pre-baked exploitation results (Step 5)\" sections. Those ARE your complete Steps 1–3 and Step 5 outputs. Do NOT call Bash, Grep, or any filesystem or network tools. Proceed directly to Step 4 (assign severities), then include the pre-baked Step 5 exploitation results verbatim in your report, then produce the full Step 6 report.\n\n");
    }

    @Override
    protected boolean isPassed(String scenarioName, GoldenChecker.Result checks, Judge.Result judge) {
        return bool(checks, "hasHandoffBlock") && bool(checks, "hasCriticalFinding") && judge.passed();
    }

    @Override
    protected void additionalAssertions(String scenarioName, GoldenChecker.Result checks, Judge.Result judge) {
        assertTrue(bool(checks, "notContains_I_don_t_have_informa"), "Response must not contain \"I don't have information\"");
        assertTrue(bool(checks, "hasReportHeader"), "Response must include report header");
        assertTrue(bool(checks, "hasHandoffBlock"), "Response must include HANDOFF BLOCK with schema");
        assertTrue(bool(checks, "hasCriticalFinding"), "Response must include CWE-338 (java.util.Random critical finding)");
        assertTrue(bool(checks, "hasSqlInjectionFinding"), "Response must include CWE-89 (SQL injection)");
        assertTrue(bool(checks, "hasNoHelmetFinding"), "Response must include CWE-693 (no security headers config)");
        assertTrue(bool(checks, "hasNoRateLimitFinding"), "Response must include CWE-770 (no rate limiting)");
        assertTrue(bool(checks, "hasExploitSection"), "Response must include Exploit sections");
    }
}
