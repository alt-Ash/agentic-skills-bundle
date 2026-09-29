import { readFileSync } from 'fs';
import yaml from 'js-yaml';

export interface GoldenCheck {
  pattern: string;
  label: string;
}

export interface YamlBlockCheck {
  startPattern: string;
  label: string;
  requiredFields: string[];
}

export interface GoldenSpec {
  required: GoldenCheck[];
  forbidden?: string[];
  tools?: {
    expected?: string[];
    forbidden?: string[];
  };
  yamlBlocks?: YamlBlockCheck[];
  referenceAnswer?: string;
}

export interface GoldenResult {
  details: Record<string, boolean>;
  passed: number;
  total: number;
}

export function loadGolden(filePath: string): GoldenSpec {
  const raw = readFileSync(filePath, 'utf-8');
  return yaml.load(raw) as GoldenSpec;
}

export function runGoldenChecks(
  response: string,
  spec: GoldenSpec,
  toolCalls: Record<string, number> = {},
): GoldenResult {
  const details: Record<string, boolean> = {};

  for (const check of spec.required) {
    details[check.label] = response.includes(check.pattern);
  }

  for (const forbidden of spec.forbidden ?? []) {
    const key = `notContains_${forbidden.slice(0, 20).replace(/\W+/g, '_')}`;
    details[key] = !response.includes(forbidden);
  }

  for (const tool of spec.tools?.expected ?? []) {
    details[`toolUsed_${tool}`] = (toolCalls[tool] ?? 0) > 0;
  }

  for (const tool of spec.tools?.forbidden ?? []) {
    details[`toolNotUsed_${tool}`] = (toolCalls[tool] ?? 0) === 0;
  }

  for (const check of spec.yamlBlocks ?? []) {
    const startIdx = response.indexOf(check.startPattern);
    if (startIdx === -1) {
      details[check.label] = false;
      continue;
    }
    const afterStart = response.slice(startIdx);
    // Prefer closing code fence boundary; fall back to blank-line split for bare YAML
    const codeFenceEnd = afterStart.indexOf('\n```');
    const block = codeFenceEnd > 0 ? afterStart.slice(0, codeFenceEnd) : afterStart.split(/\n\n/)[0];
    try {
      const parsed = yaml.load(block) as Record<string, unknown>;
      const topKey = Object.keys(parsed ?? {})[0];
      const content = (parsed?.[topKey] as Record<string, unknown>) ?? {};
      details[check.label] = check.requiredFields.every(field => field in content);
    } catch {
      details[check.label] = false;
    }
  }

  const values = Object.values(details);
  return {
    details,
    passed: values.filter(Boolean).length,
    total: values.length,
  };
}
