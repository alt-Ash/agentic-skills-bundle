#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const packageRoot = path.join(__dirname, '..');
const jarPath = path.join(__dirname, 'agentic-skills-cli', 'target', 'agentic-skills-cli.jar');

try {
  execFileSync(
    'java',
    ['-jar', jarPath, '--package-root', packageRoot, '--uninstall', ...process.argv.slice(2)],
    { stdio: 'inherit' },
  );
} catch (err) {
  if (err.code === 'ENOENT') {
    console.error('Java 21+ JRE required — install one (e.g. https://adoptium.net) and retry.');
    process.exit(1);
  }
  process.exit(err.status ?? 1);
}
