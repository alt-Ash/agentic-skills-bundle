#!/usr/bin/env tsx
import checkbox from '@inquirer/checkbox';
import { readdirSync, existsSync } from 'fs';
import { join, basename, dirname } from 'path';
import { fileURLToPath } from 'url';
import { spawnSync } from 'child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const BEHAVIORAL_DIR = join(__dirname, 'behavioral');
const FIXTURES_DIR = join(BEHAVIORAL_DIR, 'fixtures');

interface ScenarioChoice {
  agent: string;
  scenario: string;
  evalFile: string;
}

function discoverChoices(): ScenarioChoice[] {
  const evalFiles = readdirSync(BEHAVIORAL_DIR)
    .filter(file => file.endsWith('.eval.ts'))
    .sort();

  const choices: ScenarioChoice[] = [];

  for (const evalFile of evalFiles) {
    const agentName = evalFile.replace('.eval.ts', '');
    const agentFixturesDir = join(FIXTURES_DIR, agentName);

    if (existsSync(agentFixturesDir)) {
      const scenarios = readdirSync(agentFixturesDir)
        .filter(file => file.startsWith('scenario-') && file.endsWith('.md') && !file.includes('.golden.'))
        .map(file => basename(file, '.md'))
        .sort();

      for (const scenario of scenarios) {
        choices.push({ agent: agentName, scenario, evalFile });
      }
    } else {
      choices.push({ agent: agentName, scenario: '(all)', evalFile });
    }
  }

  return choices;
}

function parseRunCount(): number {
  const runsArg = process.argv.find(arg => arg.startsWith('--runs='));
  if (!runsArg) return 1;
  const parsed = parseInt(runsArg.split('=')[1], 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 1;
}

async function main() {
  const choices = discoverChoices();

  if (choices.length === 0) {
    console.error('No eval files found in behavioral/');
    process.exit(1);
  }

  const selected = await checkbox<ScenarioChoice>({
    message: 'Select scenarios to run  (space to toggle, a to select all, enter to run)',
    choices: choices.map(choice => ({
      name: `${choice.agent.padEnd(24)} ${choice.scenario}`,
      value: choice,
    })),
    pageSize: 20,
  });

  if (selected.length === 0) {
    console.log('Nothing selected. Exiting.');
    return;
  }

  const runCount = parseRunCount();

  const byAgent = new Map<string, { evalFile: string; scenarios: string[] }>();
  for (const choice of selected) {
    if (!byAgent.has(choice.agent)) {
      byAgent.set(choice.agent, { evalFile: choice.evalFile, scenarios: [] });
    }
    const agentEntry = byAgent.get(choice.agent)!;
    if (choice.scenario !== '(all)') {
      agentEntry.scenarios.push(choice.scenario);
    }
  }

  for (const [agentName, { evalFile, scenarios }] of byAgent) {
    console.log(`\n▶ Running ${agentName}${scenarios.length ? ` [${scenarios.join(', ')}]` : ''}${runCount > 1 ? ` ×${runCount}` : ''}`);
    const args = ['vitest', 'run', `behavioral/${evalFile}`];
    if (scenarios.length > 0) {
      args.push('--testNamePattern', scenarios.join('|'));
    }

    if (runCount === 1) {
      const spawnResult = spawnSync('npx', args, { stdio: 'inherit', cwd: __dirname });
      if (spawnResult.status !== 0) process.exitCode = 1;
    } else {
      const outcomes: boolean[] = [];
      for (let runIndex = 0; runIndex < runCount; runIndex++) {
        console.log(`  run ${runIndex + 1}/${runCount}`);
        const spawnResult = spawnSync('npx', args, { stdio: 'inherit', cwd: __dirname });
        outcomes.push(spawnResult.status === 0);
      }
      const passCount = outcomes.filter(Boolean).length;
      const passAtK = passCount >= 1;
      const passAllK = passCount === runCount;
      console.log(`  pass@${runCount}: ${passAtK} | pass^${runCount}: ${passAllK} | rate: ${passCount}/${runCount}`);
      if (!passAllK) process.exitCode = 1;
    }
  }
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
