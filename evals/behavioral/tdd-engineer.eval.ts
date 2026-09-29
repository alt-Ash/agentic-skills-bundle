import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { runEval, EvalConfig } from './lib/eval-runner.js';
import { expect } from 'vitest';

const __dirname = dirname(fileURLToPath(import.meta.url));

const tddEngineerEval: EvalConfig = {
  agentName: 'tdd-engineer',
  agentPath: join(__dirname, '../../agents/tdd-engineer.md'),
  fixturesDir: join(__dirname, 'fixtures/tdd-engineer'),
  resultsDir: join(__dirname, '../results'),
  scenarios: ['scenario-01-new-api-endpoint'],
  judgeCriteria: {
    test_quality:    'Does the test file include 4 cases using supertest: (1) valid body returns 200 with user object containing id/name/email, (2) missing name returns 400 with error message, (3) missing email returns 400 with error message, (4) invalid email format returns 400?',
    red_phase:       'Does the response explain why the test suite failed to run (Cannot find module — production file does not exist yet)?',
    implementation:  'Is the POST /api/users route correct? It must: return 200 with { id, name, email } on valid input, return 400 for missing name, 400 for missing email, 400 for invalid email format.',
    handoff_quality: 'Is the HANDOFF BLOCK present with Status: completed, correct file paths for both test and route files, and check rows for tests/lint/typecheck/build all marked as passed?',
  },
  prescannedPrefix: {
    trigger: '## Pre-baked execution results',
    prefix: `[EVAL MODE] The scenario contains "## Pre-baked execution results" with pre-run test suite and project check outputs. Those ARE your Phase 1 test run, Phase 3 full suite, and Phase 5 check outputs. Do NOT call Bash, Edit, or Write. Show test code and production code as code blocks in your response. The pre-baked results confirm: Phase 1 test suite fails to run (Cannot find module — production file missing), Phase 3 all 4 tests pass, Phase 5 all checks exit 0.\n\n`,
  },
  isPassed: (_, checks, judge) =>
    (checks.details.hasHandoffBlock ?? false)
    && (checks.details.hasRedConfirmation ?? false)
    && (checks.details.hasSupertestUsage ?? false)
    && judge.passed,
  additionalAssertions: (_, checks) => {
    expect(checks.details.hasHandoffBlock, 'Response must include HANDOFF BLOCK').toBe(true);
    expect(checks.details.hasRedConfirmation, 'Response must include Phase 2 red confirmation').toBe(true);
    expect(checks.details.hasSupertestUsage, 'Response must include supertest in test code').toBe(true);
    expect(checks.details.hasTestFile, 'Response must reference users.test.ts').toBe(true);
    expect(checks.details.has200Response, 'Response must include status(200) in production code').toBe(true);
    expect(checks.details.hasCompletedStatus, 'HANDOFF BLOCK must have Status: completed').toBe(true);
  },
};

runEval(tddEngineerEval);
