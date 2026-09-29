/**
 * Target-authorization gate. This is the module that keeps this tool from
 * ever being pointed at a production service — every scan tool call must go
 * through checkTarget() before issuing a single request.
 */

import { readFileSync } from 'fs';
import path from 'path';
import { z } from 'zod';

// 'prod'/'production' is deliberately not a member of this enum. There is no
// config value, typo, or override that authorizes scanning a production host.
const TargetSchema = z.object({
  host: z.string().min(1),
  environment: z.enum(['local', 'dev', 'staging', 'test']),
});

const AllowlistSchema = z.object({
  targets: z.array(TargetSchema),
});

export type AllowlistTarget = z.infer<typeof TargetSchema>;
export type Allowlist = z.infer<typeof AllowlistSchema>;

export interface AllowlistLoadResult {
  ok: boolean;
  allowlist?: Allowlist;
  error?: string;
}

function resolveAllowlistPath(): string {
  return process.env.SCANNER_ALLOWLIST_PATH
    ? path.resolve(process.env.SCANNER_ALLOWLIST_PATH)
    : path.resolve(process.cwd(), '.security-scanner', 'allowlist.json');
}

/**
 * Loads and validates the allowlist config. Fails closed: any problem at all
 * (missing file, bad JSON, schema violation, an unrecognized environment
 * value like "prod") returns ok: false with a clear reason — never an empty
 * allowlist that silently permits nothing while looking like a working state,
 * and never a partially-applied config that skips just the bad entry.
 */
export function loadAllowlist(): AllowlistLoadResult {
  const allowlistPath = resolveAllowlistPath();

  let raw: string;
  try {
    raw = readFileSync(allowlistPath, 'utf8');
  } catch {
    return {
      ok: false,
      error:
        `No allowlist config found at ${allowlistPath}. This tool refuses to scan ` +
        `anything until an explicit allowlist is created. Set SCANNER_ALLOWLIST_PATH ` +
        `or create .security-scanner/allowlist.json — see README.md for the format.`,
    };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    return {
      ok: false,
      error: `Allowlist config at ${allowlistPath} is not valid JSON: ${(err as Error).message}`,
    };
  }

  const result = AllowlistSchema.safeParse(parsed);
  if (!result.success) {
    const prodAttempt = result.error.issues.some(
      (issue) => issue.path.includes('environment'),
    );
    const suffix = prodAttempt
      ? ` — this tool refuses to scan production targets under any configuration; ` +
        `'environment' must be one of local|dev|staging|test`
      : '';
    return {
      ok: false,
      error: `Allowlist config at ${allowlistPath} is invalid: ${result.error.message}${suffix}`,
    };
  }

  return { ok: true, allowlist: result.data };
}

export interface TargetCheckResult {
  allowed: boolean;
  reason?: string;
  environment?: AllowlistTarget['environment'];
}

/**
 * Case-insensitive exact host match (host includes port if present, e.g.
 * "localhost:3000"). No wildcard/subdomain matching — exact match only is
 * the simplest safe behavior for a tool that sends real attack payloads.
 */
export function checkTarget(host: string, allowlist: Allowlist): TargetCheckResult {
  const needle = host.trim().toLowerCase();
  const match = allowlist.targets.find((t) => t.host.trim().toLowerCase() === needle);
  if (!match) {
    return {
      allowed: false,
      reason:
        `'${host}' is not in the allowlist. Add it to .security-scanner/allowlist.json ` +
        `with an explicit environment (local|dev|staging|test) before scanning it.`,
    };
  }
  return { allowed: true, environment: match.environment };
}

/**
 * Convenience wrapper: loads the allowlist and checks a target in one call.
 * Every tool handler in index.ts should call this first, before doing
 * anything else — including passive-only scans.
 */
export function authorizeTarget(host: string): TargetCheckResult & { configError?: string } {
  const loaded = loadAllowlist();
  if (!loaded.ok) {
    return { allowed: false, configError: loaded.error };
  }
  return checkTarget(host, loaded.allowlist!);
}
