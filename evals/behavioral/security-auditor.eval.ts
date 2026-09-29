import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { runEval, EvalConfig } from './lib/eval-runner.js';

const __dirname = dirname(fileURLToPath(import.meta.url));

const securityAuditorEval: EvalConfig = {
  agentName: 'security-auditor',
  agentPath: join(__dirname, '../../agents/security-auditor.md'),
  fixturesDir: join(__dirname, 'fixtures/security-auditor'),
  resultsDir: join(__dirname, '../results'),
  scenarios: ['scenario-01-code-findings'],
  judgeCriteria: {
    completeness:   'Does the report identify all 4 planted vulnerabilities — Math.random() token (CWE-338), SQL injection (CWE-89), no Helmet (CWE-693), no rate limiting (CWE-770)?',
    classification: 'Are catalog titles, severity levels (Critical/High), OWASP categories (A02/A03/A04/A05), and CWE numbers exactly correct per the fixed catalog?',
    exploitation:   'Are Exploit sections present for findings, with Status (Confirmed/Not Confirmed) matching the pre-baked results provided?',
    handoff_block:  'Is the HANDOFF BLOCK valid JSON with schema "security-handoff/v1" and a codeFindings array containing all 4 findings with correct severities?',
  },
  prescannedPrefix: {
    trigger: '## Pre-scanned project (Steps 1–3 complete)',
    prefix: `[EVAL MODE] The scenario contains "## Pre-scanned project (Steps 1–3 complete)" and "## Pre-baked exploitation results (Step 5)" sections. Those ARE your complete Steps 1–3 and Step 5 outputs. Do NOT call Bash, Grep, or any filesystem or network tools. Proceed directly to Step 4 (assign severities), then include the pre-baked Step 5 exploitation results verbatim in your report, then produce the full Step 6 report.\n\n`,
  },
  isPassed: (_, checks, judge) =>
    (checks.details.hasHandoffBlock ?? false)
    && (checks.details.hasCriticalFinding ?? false)
    && judge.passed,
  additionalAssertions: (_, checks) => {
    expect(checks.details.notContains_I_don_t_have_informa, 'Response must not contain "I don\'t have information"').toBe(true);
    expect(checks.details.hasReportHeader, 'Response must include report header').toBe(true);
    expect(checks.details.hasHandoffBlock, 'Response must include HANDOFF BLOCK with schema').toBe(true);
    expect(checks.details.hasCriticalFinding, 'Response must include CWE-338 (Math.random critical finding)').toBe(true);
    expect(checks.details.hasSqlInjectionFinding, 'Response must include CWE-89 (SQL injection)').toBe(true);
    expect(checks.details.hasNoHelmetFinding, 'Response must include CWE-693 (no Helmet)').toBe(true);
    expect(checks.details.hasNoRateLimitFinding, 'Response must include CWE-770 (no rate limiting)').toBe(true);
    expect(checks.details.hasExploitSection, 'Response must include Exploit sections').toBe(true);
  },
};

runEval(securityAuditorEval);
