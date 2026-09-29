import { describe, it, expect, beforeAll } from 'vitest';
import { readFileSync, mkdirSync, writeFileSync } from 'fs';
import { join } from 'path';
import { execSync } from 'child_process';
import { parseAgent } from './parse-agent.js';
import { createProvider, createJudgeProvider, type LLMProvider, type McpStdioConfig } from './provider.js';
import { judgeResponse, type JudgeResult } from './judge.js';
import { loadGolden, runGoldenChecks, type GoldenResult } from './golden.js';

export type { GoldenResult, JudgeResult };

export interface EvalConfig {
  agentName: string;
  agentPath: string;
  fixturesDir: string;
  resultsDir: string;
  scenarios: readonly string[];
  judgeCriteria: Record<string, string>;
  judgeThreshold?: number;
  prescannedPrefix?: {
    trigger: string;
    prefix: string;
  };
  isPassed: (scenarioName: string, checks: GoldenResult, judgeResult: JudgeResult) => boolean;
  scenarioOptions?: (scenarioName: string) => {
    allowedTools?: string[];
    mcpServers?: Record<string, McpStdioConfig>;
    judgeCriteria?: Record<string, string>;
  };
  additionalAssertions?: (scenarioName: string, checks: GoldenResult, judgeResult: JudgeResult) => void;
}

let cachedSha: string | null = null;

function currentGitSha(): string | null {
  if (cachedSha !== null) return cachedSha;
  try {
    cachedSha = execSync('git rev-parse --short HEAD', { encoding: 'utf-8' }).trim();
  } catch {
    cachedSha = null;
  }
  return cachedSha;
}

export function runEval(config: EvalConfig): void {
  let provider: LLMProvider;
  let judgeProvider: LLMProvider;
  let agent: ReturnType<typeof parseAgent>;

  describe(`${config.agentName} behavioral eval`, () => {
    beforeAll(async () => {
      provider = await createProvider();
      judgeProvider = await createJudgeProvider();
      agent = parseAgent(config.agentPath);
      mkdirSync(config.resultsDir, { recursive: true });
      mkdirSync(join(config.resultsDir, 'history'), { recursive: true });
      mkdirSync(join(config.resultsDir, 'transcripts'), { recursive: true });
      console.log(`Provider: ${provider.name}`);
      console.log(`Judge:    ${judgeProvider.name}`);
    });

    for (const scenarioName of config.scenarios) {
      it(scenarioName, async () => {
        const scenario = readFileSync(join(config.fixturesDir, `${scenarioName}.md`), 'utf-8');
        const spec = loadGolden(join(config.fixturesDir, `${scenarioName}.golden.yaml`));

        const systemPrompt =
          config.prescannedPrefix && scenario.includes(config.prescannedPrefix.trigger)
            ? config.prescannedPrefix.prefix + agent.systemPrompt
            : agent.systemPrompt;

        const scenarioOpts = config.scenarioOptions?.(scenarioName) ?? {};
        const allowedTools = scenarioOpts.allowedTools ?? [];
        const mcpServers = scenarioOpts.mcpServers;
        const criteria = scenarioOpts.judgeCriteria ?? config.judgeCriteria;

        const chatResult = await provider.chat(systemPrompt, scenario, {
          allowedTools,
          agentName: config.agentName,
          ...(mcpServers ? { mcpServers } : {}),
        });
        const { response } = chatResult;

        expect(chatResult.usage.totalTokens, 'Provider must return token usage').toBeGreaterThan(0);
        expect(chatResult.durationMs, 'Duration must be positive').toBeGreaterThan(0);

        const checks = runGoldenChecks(response, spec, chatResult.toolCalls);

        const goldenFailures = Object.entries(checks.details)
          .filter(([, ok]) => !ok)
          .map(([label]) => label);

        const judgeResult =
          goldenFailures.length === 0
            ? await judgeResponse(
                judgeProvider,
                agent.frontmatter.description,
                scenario,
                response,
                criteria,
                config.judgeThreshold,
                spec.referenceAnswer,
              )
            : { passed: false, score: 0, dimensions: {}, rationale: 'Skipped: golden check failures', reasoning: '' };

        const timestamp = new Date().toISOString();
        const safeTs = timestamp.replace(/[:.]/g, '-');
        const result = {
          agent: chatResult.agent,
          provider: provider.name,
          judgeModel: judgeProvider.name,
          model: process.env.EVAL_MODEL ?? 'claude-haiku-4-5-20251001',
          gitSha: currentGitSha(),
          scenario: scenarioName,
          goldenFailures,
          deterministicChecks: { details: checks.details, passed: checks.passed, total: checks.total },
          tokenUsage: chatResult.usage,
          toolCalls: chatResult.toolCalls,
          durationMs: chatResult.durationMs,
          judgeScore: judgeResult.score,
          judgeDimensions: judgeResult.dimensions,
          judgeRationale: judgeResult.rationale,
          judgeReasoning: judgeResult.reasoning,
          agentResponse: response,
          transcriptFile: `transcripts/${config.agentName}-${scenarioName}-${safeTs}.json`,
          passed: config.isPassed(scenarioName, checks, judgeResult),
          timestamp,
        };

        writeFileSync(
          join(config.resultsDir, 'history', `${config.agentName}-${scenarioName}-${safeTs}.json`),
          JSON.stringify(result, null, 2),
        );
        writeFileSync(
          join(config.resultsDir, 'transcripts', `${config.agentName}-${scenarioName}-${safeTs}.json`),
          JSON.stringify({
            agent: chatResult.agent,
            scenario: scenarioName,
            provider: provider.name,
            transcript: chatResult.transcript,
          }, null, 2),
        );

        console.log(`  Deterministic: ${checks.passed}/${checks.total}`);
        const dimWidth = Math.max(...Object.keys(judgeResult.dimensions).map(dimName => dimName.length), 1);
        console.log(`  Judge: ${judgeResult.score}/5`);
        for (const [dim, score] of Object.entries(judgeResult.dimensions)) {
          console.log(`    ${dim.padEnd(dimWidth)}: ${score}/5`);
        }
        console.log(`  Rationale: ${judgeResult.rationale}`);
        console.log(`  Tokens: in=${chatResult.usage.inputTokens} out=${chatResult.usage.outputTokens} total=${chatResult.usage.totalTokens}${chatResult.usage.estimatedCostUsd != null ? ` cost=$${chatResult.usage.estimatedCostUsd.toFixed(4)}` : ''}`);
        console.log(`  Duration: ${chatResult.durationMs}ms`);
        if (Object.keys(chatResult.toolCalls).length > 0) {
          console.log(`  Tools: ${JSON.stringify(chatResult.toolCalls)}`);
        }

        expect(checks.details.notContains_I_cannot, 'Response must not contain "I cannot"').toBe(true);
        expect(checks.details.notContains_I_don_t_have_access, 'Response must not contain "I don\'t have access"').toBe(true);
        expect(checks.details.notContains_I_will_skip, 'Response must not contain "I will skip"').toBe(true);

        expect(checks.details.toolNotUsed_Write, 'Agent must not use Write tool').toBe(true);
        expect(checks.details.toolNotUsed_Edit, 'Agent must not use Edit tool').toBe(true);
        expect(checks.details.toolNotUsed_Bash, 'Agent must not use Bash tool').toBe(true);

        config.additionalAssertions?.(scenarioName, checks, judgeResult);

        expect(judgeResult.passed, `Judge score ${judgeResult.score}/5: ${judgeResult.rationale}`).toBe(true);
      });
    }
  });
}
