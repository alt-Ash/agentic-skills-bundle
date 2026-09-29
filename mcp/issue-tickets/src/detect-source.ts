/**
 * detect-source.ts — Auto-detect the ticket source from project config files.
 *
 * Detection order:
 *   1. .github/ directory → "github"
 *   2. azure-pipelines.yml or .azure/ directory → "azure"
 *   3. .git/config remote URL containing dev.azure.com or visualstudio.com → "azure"
 *   4. .git/config remote URL containing github.com → "github"
 *   5. package.json repository field → "azure" or "github"
 *   6. Default → null (no signal, or both providers credentialed with no config signal)
 *
 * If detection is ambiguous and multiple providers are credentialed, returns null
 * to indicate that the caller should try all providers in parallel.
 */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import type { TicketSource } from './types.js';
import { hasAzureCredentials } from './providers/azure.js';
import { hasGithubCredentials } from './providers/github.js';

const cwd = process.cwd();

async function exists(p: string): Promise<boolean> {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

async function readText(p: string): Promise<string | null> {
  try {
    return await fs.readFile(p, 'utf8');
  } catch {
    return null;
  }
}

/**
 * Detect the ticket source from project config files.
 * Returns a TicketSource string, or null when detection is ambiguous
 * (caller should use all-provider fallback).
 */
export async function detectSource(): Promise<TicketSource | null> {
  // 1. .github/ directory → github
  if (await exists(path.join(cwd, '.github'))) {
    return 'github';
  }

  // 2. azure-pipelines.yml or .azure/ → azure
  if (
    await exists(path.join(cwd, 'azure-pipelines.yml')) ||
    await exists(path.join(cwd, '.azure'))
  ) {
    return 'azure';
  }

  // 3 & 4. Parse .git/config remote URL
  const gitConfig = await readText(path.join(cwd, '.git', 'config'));
  if (gitConfig) {
    if (/dev\.azure\.com|visualstudio\.com/i.test(gitConfig)) return 'azure';
    if (/github\.com/i.test(gitConfig)) return 'github';
  }

  // 5. package.json repository field
  const pkgText = await readText(path.join(cwd, 'package.json'));
  if (pkgText) {
    try {
      const pkg = JSON.parse(pkgText);
      const repoField: string =
        typeof pkg.repository === 'string'
          ? pkg.repository
          : pkg.repository?.url ?? '';
      if (/dev\.azure\.com|visualstudio\.com/i.test(repoField)) return 'azure';
      if (/github\.com/i.test(repoField)) return 'github';
    } catch {
      // malformed package.json — ignore
    }
  }

  // 6. Ambiguous — if multiple providers are credentialed, return null-equivalent
  //    by checking which are available. If only one is credentialed, use it.
  const azureAvailable = hasAzureCredentials();
  const githubAvailable = hasGithubCredentials();

  if (azureAvailable && !githubAvailable) return 'azure';
  if (githubAvailable && !azureAvailable) return 'github';

  // No config signal, and either neither or both providers are credentialed.
  return null;
}

/**
 * Returns true when source detection cannot confidently select one source
 * and multiple providers have credentials.
 */
export async function isAmbiguous(): Promise<boolean> {
  const source = await detectSource();
  if (source !== null) return false;
  return hasAzureCredentials() && hasGithubCredentials();
}
