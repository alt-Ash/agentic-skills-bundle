#!/usr/bin/env node
/**
 * check-node-compat.js <target-version>
 *
 * Reads package.json in the current working directory and reports which
 * direct dependencies declare an engines.node range that does not satisfy
 * the target Node.js version.
 *
 * Uses the project's own semver package if available, otherwise falls back
 * to a built-in coarse range check.
 *
 * Exit codes:
 *   0 — all packages compatible (or no engines.node declared)
 *   1 — one or more incompatible packages found
 *   2 — usage error
 */

const path = require('path');
const fs = require('fs');

// ── Args ──────────────────────────────────────────────────────────────────────

const target = process.argv[2];

if (!target) {
  console.error('Usage: node check-node-compat.js <target-version>');
  console.error('Example: node check-node-compat.js 20.0.0');
  process.exit(2);
}

// ── Semver ────────────────────────────────────────────────────────────────────

let semver;
try {
  semver = require(path.join(process.cwd(), 'node_modules', 'semver'));
} catch {
  // Minimal fallback: handles ">=X", "^X", "~X", ">X", exact versions.
  // Good enough for engines.node ranges in the wild.
  semver = {
    satisfies(version, range) {
      const clean = version.replace(/^v/, '').split('-')[0]; // strip pre-release
      const [major] = clean.split('.').map(Number);

      // Normalize range entries (handles " || " unions)
      const parts = range.split('||').map(s => s.trim());
      return parts.some(part => checkPart(major, part));
    },
  };

  function checkPart(major, part) {
    const m = part.match(/^([><=^~*]*)\s*v?(\d+)/);
    if (!m) return true; // unparseable — assume ok
    const op = m[1];
    const ver = parseInt(m[2], 10);
    if (op === '>=' || op === '^' || op === '~' || op === '')
      return major >= ver;
    if (op === '>') return major > ver;
    if (op === '<=') return major <= ver;
    if (op === '<') return major < ver;
    if (op === '=') return major === ver;
    if (op === '*' || part === '*') return true;
    return true;
  }
}

// ── Read project package.json ─────────────────────────────────────────────────

const pkgPath = path.join(process.cwd(), 'package.json');
if (!fs.existsSync(pkgPath)) {
  console.error(`Error: no package.json found in ${process.cwd()}`);
  process.exit(2);
}

const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
const deps = {
  ...pkg.dependencies,
  ...pkg.devDependencies,
};

if (Object.keys(deps).length === 0) {
  console.log('No dependencies found in package.json.');
  process.exit(0);
}

// ── Check each dependency ─────────────────────────────────────────────────────

const results = {
  compatible: [],
  incompatible: [],
  noEngines: [],
  missing: [],
};

for (const name of Object.keys(deps)) {
  const depPkgPath = path.join(
    process.cwd(),
    'node_modules',
    name,
    'package.json',
  );

  if (!fs.existsSync(depPkgPath)) {
    results.missing.push(name);
    continue;
  }

  let depPkg;
  try {
    depPkg = JSON.parse(fs.readFileSync(depPkgPath, 'utf8'));
  } catch {
    results.missing.push(name);
    continue;
  }

  const range = depPkg.engines?.node;

  if (!range) {
    results.noEngines.push({ name, version: depPkg.version });
    continue;
  }

  const ok = semver.satisfies(target, range);
  const entry = { name, version: depPkg.version, range };
  if (ok) {
    results.compatible.push(entry);
  } else {
    results.incompatible.push(entry);
  }
}

// ── Output ────────────────────────────────────────────────────────────────────

const targetDisplay = target.startsWith('v') ? target : `v${target}`;

console.log(`\nNode.js compatibility check — target: ${targetDisplay}`);
console.log('═'.repeat(60));

if (results.incompatible.length > 0) {
  console.log(`\n🚨 INCOMPATIBLE (${results.incompatible.length})`);
  for (const { name, version, range } of results.incompatible) {
    console.log(`   ${name}@${version}  engines.node="${range}"`);
  }
}

if (results.missing.length > 0) {
  console.log(
    `\n⚠️  NOT INSTALLED — run npm install first (${results.missing.length})`,
  );
  for (const name of results.missing) {
    console.log(`   ${name}`);
  }
}

if (results.noEngines.length > 0) {
  console.log(
    `\n⚪ NO engines.node DECLARED — assume compatible, verify manually (${results.noEngines.length})`,
  );
  for (const { name, version } of results.noEngines) {
    console.log(`   ${name}@${version}`);
  }
}

if (results.compatible.length > 0) {
  console.log(`\n✅ COMPATIBLE (${results.compatible.length})`);
  for (const { name, version, range } of results.compatible) {
    console.log(`   ${name}@${version}  engines.node="${range}"`);
  }
}

console.log('');

// ── Exit code ─────────────────────────────────────────────────────────────────

if (results.incompatible.length > 0 || results.missing.length > 0) {
  process.exit(1);
}
process.exit(0);
