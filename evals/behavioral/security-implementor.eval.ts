import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { runEval, EvalConfig } from './lib/eval-runner.js';
import { expect } from 'vitest';

const __dirname = dirname(fileURLToPath(import.meta.url));

const securityImplementorEval: EvalConfig = {
  agentName: 'security-implementor',
  agentPath: join(__dirname, '../../agents/security-implementor.md'),
  fixturesDir: join(__dirname, 'fixtures/security-implementor'),
  resultsDir: join(__dirname, '../results'),
  scenarios: ['scenario-01-apply-code-fixes'],
  judgeCriteria: {
    fix_correctness: 'Are the code fixes for all 10 findings technically correct? Key fixes: parameterized query for C-01, crypto.randomBytes for C-02, process.env secret for C-03, no eval() for C-04, path.normalize/join for C-05, jwt algorithms array for C-06, specific CORS origin for C-07, helmet() for C-08, rateLimit middleware for C-09, no err.stack in response for C-10',
    completeness:    'Does the response address all 10 findings (C-01 through C-10)?',
    handoff_update:  'Is the HANDOFF BLOCK updated with "fixed" status for all findings?',
    summary_quality: 'Is the Fix Summary section present and well-formed with before/after vulnerability counts?',
  },
  prescannedPrefix: {
    trigger: '## Pre-baked execution results',
    prefix: `[EVAL MODE] The scenario contains "## Pre-baked execution results" with pre-run npm audit and install outputs. Those ARE your complete Steps 2, 3, and 5 outputs. Do NOT call Bash, Edit, or Write. For Step 4: show each code fix as a before/after code block in your response. Proceed to Step 6 after all fixes are shown.\n\n`,
  },
  isPassed: (_, checks, judge) =>
    (checks.details.hasFixSummary ?? false)
    && (checks.details.hasHandoffBlock ?? false)
    && (checks.details.hasFindingsFixed ?? false)
    && judge.passed,
  additionalAssertions: (_, checks) => {
    expect(checks.details.notContains_I_don_t_have_informa, 'Response must not contain "I don\'t have information"').toBe(true);
    expect(checks.details.hasFixSummary, 'Response must include Fix Summary section').toBe(true);
    expect(checks.details.hasHandoffBlock, 'Response must include HANDOFF BLOCK with schema').toBe(true);
    expect(checks.details.hasFindingsFixed, 'Response must include findings marked "fixed"').toBe(true);
    expect(checks.details.hasCryptoFix, 'Response must include crypto.randomBytes fix for C-02').toBe(true);
    expect(checks.details.hasEnvSecretFix, 'Response must include process.env fix for C-03').toBe(true);
    expect(checks.details.hasHelmetFix, 'Response must include helmet() fix for C-08').toBe(true);
  },
};

runEval(securityImplementorEval);
