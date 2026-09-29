#!/usr/bin/env tsx
/**
 * Token-efficiency report.
 *
 * Reads evals/results/history/*.json, groups by agent+scenario, and compares the
 * most-recent window of runs against the window before it. Surfaces token/cost
 * deltas alongside quality (judge score + pass rate) so a prompt change can be
 * judged on "did it get cheaper without getting worse".
 *
 * Token-efficiency loop:
 *   1. npm run eval:select -- --runs=10   (or EVAL_MODEL=... npm run eval)
 *   2. npm run report                      record the current window
 *   3. trim the agent prompt in agents/<agent>.md
 *   4. re-run 10x, npm run report          compare new window vs previous
 *   5. keep the edit on EFFICIENCY WIN; iterate/revert on QUALITY REGRESSION
 *
 * Alerts are advisory — they never fail a test.
 *
 * Usage:
 *   tsx evals/report.ts [--window=N] [--save-baseline]
 */
import { readdirSync, readFileSync, writeFileSync, existsSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const RESULTS_DIR = join(__dirname, 'results');
const HISTORY_DIR = join(RESULTS_DIR, 'history');
const REPORT_MD = join(RESULTS_DIR, 'REPORT.md');
const REPORT_JSON = join(RESULTS_DIR, 'report.json');
const BASELINE_FILE = join(RESULTS_DIR, 'baselines.json');

const DEFAULT_WINDOW = 10;
const TOKEN_PCT_THRESHOLD = 10; // % change in mean total tokens to flag
const JUDGE_DROP_THRESHOLD = 0.3; // absolute judge-score drop to flag
const PASS_DROP_THRESHOLD = 0.1; // pass-rate drop (fraction) to flag

interface RunRecord {
  agent?: string;
  scenario: string;
  model?: string;
  gitSha?: string;
  passed: boolean;
  judgeScore: number;
  deterministicChecks?: { passed: number; total: number };
  tokenUsage: { inputTokens: number; outputTokens: number; totalTokens: number; estimatedCostUsd?: number };
  durationMs: number;
  timestamp: string;
}

interface WindowStats {
  runs: number;
  passRate: number;
  meanJudge: number;
  meanTotalTokens: number;
  meanInputTokens: number;
  meanOutputTokens: number;
  meanCostUsd: number;
  meanDurationMs: number;
  tokensPerPass: number;
  costPerPass: number;
  gitShas: string[];
}

interface GroupReport {
  agent: string;
  scenario: string;
  heldOut: boolean;
  current: WindowStats;
  previous: WindowStats | null;
  deltas: {
    tokenPct: number;
    costPct: number;
    judgeDelta: number;
    passRateDelta: number;
  } | null;
  alerts: string[];
}

function parseWindow(): number {
  const arg = process.argv.find(token => token.startsWith('--window='));
  if (!arg) return DEFAULT_WINDOW;
  const parsed = parseInt(arg.split('=')[1], 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_WINDOW;
}

function mean(values: number[]): number {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
}

function loadHistory(): RunRecord[] {
  if (!existsSync(HISTORY_DIR)) return [];
  return readdirSync(HISTORY_DIR)
    .filter(file => file.endsWith('.json'))
    .map(file => {
      try {
        return JSON.parse(readFileSync(join(HISTORY_DIR, file), 'utf-8')) as RunRecord;
      } catch {
        return null;
      }
    })
    .filter((record): record is RunRecord => record != null && typeof record.scenario === 'string' && typeof record.timestamp === 'string');
}

function computeWindow(records: RunRecord[]): WindowStats {
  const passes = records.filter(record => record.passed).length;
  const totalTokens = records.map(record => record.tokenUsage?.totalTokens ?? 0);
  const costs = records.map(record => record.tokenUsage?.estimatedCostUsd ?? 0);
  const summedTokens = totalTokens.reduce((sum, value) => sum + value, 0);
  const summedCost = costs.reduce((sum, value) => sum + value, 0);
  return {
    runs: records.length,
    passRate: records.length ? passes / records.length : 0,
    meanJudge: mean(records.map(record => record.judgeScore ?? 0)),
    meanTotalTokens: mean(totalTokens),
    meanInputTokens: mean(records.map(record => record.tokenUsage?.inputTokens ?? 0)),
    meanOutputTokens: mean(records.map(record => record.tokenUsage?.outputTokens ?? 0)),
    meanCostUsd: mean(costs),
    meanDurationMs: mean(records.map(record => record.durationMs ?? 0)),
    tokensPerPass: passes ? summedTokens / passes : Infinity,
    costPerPass: passes ? summedCost / passes : Infinity,
    gitShas: [...new Set(records.map(record => record.gitSha).filter((sha): sha is string => !!sha))],
  };
}

function pct(current: number, previous: number): number {
  if (previous === 0) return current === 0 ? 0 : Infinity;
  return ((current - previous) / previous) * 100;
}

function buildGroupReport(agent: string, scenario: string, records: RunRecord[], window: number): GroupReport {
  const sorted = [...records].sort((left, right) => left.timestamp.localeCompare(right.timestamp));
  const currentRecords = sorted.slice(-window);
  const previousRecords = sorted.slice(Math.max(0, sorted.length - window * 2), sorted.length - window);

  const current = computeWindow(currentRecords);
  const previous = previousRecords.length ? computeWindow(previousRecords) : null;

  const deltas = previous
    ? {
        tokenPct: pct(current.meanTotalTokens, previous.meanTotalTokens),
        costPct: pct(current.meanCostUsd, previous.meanCostUsd),
        judgeDelta: current.meanJudge - previous.meanJudge,
        passRateDelta: current.passRate - previous.passRate,
      }
    : null;

  const alerts: string[] = [];
  if (deltas) {
    const qualityRegressed =
      deltas.judgeDelta <= -JUDGE_DROP_THRESHOLD || deltas.passRateDelta <= -PASS_DROP_THRESHOLD;
    const tokensDown = deltas.tokenPct <= -TOKEN_PCT_THRESHOLD;
    const tokensUp = deltas.tokenPct >= TOKEN_PCT_THRESHOLD;

    if (qualityRegressed) {
      const cheaper = tokensDown ? ' (and cheaper — do not accept blindly)' : '';
      alerts.push(
        `QUALITY REGRESSION: judge ${deltas.judgeDelta.toFixed(2)}, pass-rate ${(deltas.passRateDelta * 100).toFixed(0)}%${cheaper}`,
      );
    } else if (tokensDown) {
      alerts.push(`EFFICIENCY WIN: tokens ${deltas.tokenPct.toFixed(1)}% with quality held`);
    }
    if (tokensUp) {
      alerts.push(`TOKEN SPIKE: tokens ${signedPct(deltas.tokenPct)} vs previous window`);
    }
  }

  return {
    agent,
    scenario,
    heldOut: scenario.includes('holdout'),
    current,
    previous,
    deltas,
    alerts,
  };
}

function fmt(value: number): string {
  if (!Number.isFinite(value)) return '—';
  return value >= 1000 ? Math.round(value).toLocaleString() : value.toFixed(value < 10 ? 2 : 0);
}

function signedPct(value: number): string {
  if (!Number.isFinite(value)) return '—';
  if (value > 999.9) return '>+999%';
  if (value < -999.9) return '<-999%';
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`;
}

function renderMarkdown(reports: GroupReport[], window: number): string {
  const lines: string[] = [];
  lines.push('# Eval token-efficiency report');
  lines.push('');
  lines.push(`Generated: ${new Date().toISOString()}`);
  lines.push(`Window: last ${window} runs vs the previous ${window} (per agent + scenario).`);
  lines.push('');

  const allAlerts = reports.flatMap(report => report.alerts.map(alert => ({ report, alert })));
  lines.push('## Alerts');
  lines.push('');
  if (allAlerts.length === 0) {
    lines.push('_No alerts. Not enough history for a previous window, or no significant change._');
  } else {
    for (const { report, alert } of allAlerts) {
      lines.push(`- **${report.agent} / ${report.scenario}** — ${alert}`);
    }
  }
  lines.push('');

  lines.push('## Windows');
  lines.push('');
  lines.push('| Agent | Scenario | Held-out | Runs | Pass | Judge | Tokens (mean) | Token Δ | Cost Δ | Judge Δ | Tokens/pass |');
  lines.push('|---|---|:--:|--:|--:|--:|--:|--:|--:|--:|--:|');
  for (const report of reports) {
    const { current: cur, deltas } = report;
    lines.push(
      `| ${report.agent} | ${report.scenario} | ${report.heldOut ? 'yes' : ''} | ${cur.runs} | ${(cur.passRate * 100).toFixed(0)}% | ${cur.meanJudge.toFixed(2)} | ${fmt(cur.meanTotalTokens)} | ${deltas ? signedPct(deltas.tokenPct) : '—'} | ${deltas ? signedPct(deltas.costPct) : '—'} | ${deltas ? (deltas.judgeDelta >= 0 ? '+' : '') + deltas.judgeDelta.toFixed(2) : '—'} | ${fmt(cur.tokensPerPass)} |`,
    );
  }
  lines.push('');
  lines.push(
    '_Token Δ / Cost Δ / Judge Δ compare the current window to the previous one. EFFICIENCY WIN = tokens down ≥' +
      `${TOKEN_PCT_THRESHOLD}% while quality holds; QUALITY REGRESSION = judge drop ≥${JUDGE_DROP_THRESHOLD} or pass-rate drop ≥${PASS_DROP_THRESHOLD * 100}%._`,
  );
  lines.push('');
  return lines.join('\n');
}

function main(): void {
  const window = parseWindow();
  const saveBaseline = process.argv.includes('--save-baseline');
  const history = loadHistory();

  if (history.length === 0) {
    console.error(`No history found in ${HISTORY_DIR}. Run \`npm run eval\` first.`);
    process.exit(1);
  }

  const groups = new Map<string, RunRecord[]>();
  for (const record of history) {
    const agent = record.agent ?? 'unknown';
    const key = `${agent}::${record.scenario}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(record);
  }

  const reports: GroupReport[] = [...groups.entries()]
    .map(([key, records]) => {
      const [agent, scenario] = key.split('::');
      return buildGroupReport(agent, scenario, records, window);
    })
    .sort((left, right) => left.agent.localeCompare(right.agent) || left.scenario.localeCompare(right.scenario));

  writeFileSync(REPORT_MD, renderMarkdown(reports, window));
  writeFileSync(REPORT_JSON, JSON.stringify({ generated: new Date().toISOString(), window, reports }, null, 2));

  if (saveBaseline) {
    const baseline = Object.fromEntries(reports.map(report => [`${report.agent}::${report.scenario}`, report.current]));
    writeFileSync(BASELINE_FILE, JSON.stringify({ saved: new Date().toISOString(), window, baseline }, null, 2));
    console.log(`Baseline saved to ${BASELINE_FILE}`);
  }

  const alertCount = reports.reduce((sum, report) => sum + report.alerts.length, 0);
  console.log(`Report written: ${REPORT_MD}`);
  console.log(`Groups: ${reports.length} | Alerts: ${alertCount}`);
  for (const report of reports) {
    for (const alert of report.alerts) {
      console.log(`  [${report.agent}/${report.scenario}] ${alert}`);
    }
  }
}

main();
