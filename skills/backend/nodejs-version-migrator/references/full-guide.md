---
name: nodejs-version-migrator
description:
  Guides safe Node.js version upgrades by auditing breaking changes, running
  automated codemods, and updating configs. Use when the user wants to upgrade
  Node.js versions, migrate from one Node version to another, or asks about
  Node.js breaking changes between versions.
version: "1.1.0"
category: backend
---

# Node.js Version Migrator

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## Workflow

Copy this checklist and track progress:

```
Migration Progress:
- [ ] 1. Identify current and target Node.js versions
- [ ] 2. Read the relevant migration guide
- [ ] 3. Audit the codebase for affected patterns
- [ ] 4. Run automated codemods
- [ ] 5. Audit npm packages for Node.js version compatibility
- [ ] 6. Update engine field in package.json
- [ ] 7. Update CI/CD and tooling configs (.nvmrc, .node-version, Dockerfile, etc.)
- [ ] 8. Run tests and verify
- [ ] 9. Run build and verify
- [ ] 10. Run Docker build and verify
- [ ] 11. Check for license violations (if project has a license)
```

## Available migration guides

| From → To | Guide                                                |
| --------- | ---------------------------------------------------- |
| v14 → v16 | [migrations/v14-to-v16.md](migrations/v14-to-v16.md) |
| v16 → v20 | [migrations/v16-to-v20.md](migrations/v16-to-v20.md) |
| v20 → v22 | [migrations/v20-to-v22.md](migrations/v20-to-v22.md) |
| v22 → v24 | [migrations/v22-to-v24.md](migrations/v22-to-v24.md) |

## Multi-hop migrations

If the gap between current and target versions is not covered by a single guide,
chain the available guides in order. Complete all 10 steps for each hop before
starting the next.

**Example: v14 → v20 with guides for v14→v16 and v16→v20**

```
Hop 1: v14 → v16  (follow all 10 steps, run tests + build + docker, confirm green)
Hop 2: v16 → v20  (follow all 10 steps, run tests + build + docker, confirm green)
```

Rules:

- Never skip an intermediate version that has a guide.
- Do not proceed to the next hop until tests pass for the current one.
- Update `package.json engines`, `.nvmrc`, and tooling configs only on the final
  hop (or keep them in sync at each hop — user's choice, ask if unclear).

If no guide exists for an intermediate hop, note the gap to the user and proceed
with manual review of the Node.js changelog for that version range.

## Framework migrations

Some frameworks have their own Node.js version requirements and breaking changes that must be handled alongside the Node.js upgrade. If the project uses a supported framework, follow its dedicated migration skill **after** completing Node.js steps 1–4 (breaking-change fixes) but **before** running tests.

| Framework | Migration skill |
|---|---|
| NestJS | [framework-migrations/nestjs/SKILL.md](framework-migrations/nestjs/SKILL.md) |

**How to detect if a framework migration is needed:**

1. Check `package.json` for framework packages (e.g. `@nestjs/core`, `@nestjs/common`).
2. Check the framework's migration guide for the Node.js version requirements of each major release.
3. If the target Node.js version requires a framework upgrade (e.g. NestJS v11 requires Node ≥ 20), include the framework migration as part of this workflow.

When a framework migration is needed, expand the checklist:

```
- [ ] 5a. Run framework migration (read framework skill, follow its steps)
```

Complete the framework migration and confirm tests pass before continuing with step 6.

## Step 1 — Identify versions

If not told explicitly, check:

```bash
node --version
cat package.json | grep '"node"'
cat .nvmrc 2>/dev/null || cat .node-version 2>/dev/null
```

After identifying versions, emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : nodejs-version-migrator
Timestamp   : <ISO-8601 date>

### Project
- Type            : <Node.js API | NestJS | Express | other>
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <current version>
- Framework       : <NestJS | Express | Fastify | none> <version>
- UI library      : —
- Build tool      : <tsc | webpack | none>
- Test runner     : <jest | vitest | mocha | none>
- Linter          : <eslint | biome | none>

### Migration context
- Current version : Node <vX>
- Target version  : Node <vY>
- Migration hops  : <e.g. "v16 → v20 (single)" or "v14 → v16 → v20 (two hops)">

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no>
- Storybook       : —

### Files read
- package.json
- .nvmrc / .node-version

### Gaps / unknowns
- Framework migration needed: <NestJS upgrade required: yes | no | unknown>
- Native addons present: <yes | no | unknown — run check in Step 7>
***END CONTEXT BLOCK***
```

## Step 2 — Read the migration guide

Load the appropriate file from `migrations/` for the version pair. It contains
breaking changes and codemod commands.

## Step 3 — Audit codebase

Grep for the deprecated patterns listed in the migration guide before running
codemods.

## Step 4 — Run codemods

Execute the `npx codemod run` commands from the guide. Codemods are automated
and safe to run — they make targeted AST-based changes.

## Step 5 — Audit npm packages for Node.js compatibility

For every direct dependency, verify its declared `engines.node` range includes
the target version.

### 1. Find incompatible packages

Run the compatibility script from the project root:

```bash
node <skill-dir>/scripts/check-node-compat.js <TARGET_VERSION>
# Example:
node <skill-dir>/scripts/check-node-compat.js 20.0.0
```

Replace `<skill-dir>` with the path where this skill is installed (e.g.
`.claude/skills/nodejs-version-migrator` or wherever your agent resolves it).

The script outputs four groups:

- `🚨 INCOMPATIBLE` — declared `engines.node` excludes the target version
- `⚠️ NOT INSTALLED` — package not in `node_modules`; run `npm install` first
- `⚪ NO engines.node` — no range declared; assume compatible but verify
  manually
- `✅ COMPATIBLE` — declared range includes the target version

Exit code `0` = all clear. Exit code `1` = action required.

Complement with npm tooling:

```bash
npm outdated   # current vs latest vs wanted
npm audit      # known CVEs
```

### 2. For each incompatible package — follow this decision tree

```
Is a newer version of the package available?
├── YES → Does the latest version support the target Node version?
│         ├── YES → Bump to that version.
│         │         Check the package's npm page or GitHub for a migration guide.
│         │         URL pattern: https://www.npmjs.com/package/<name>
│         │         Look for: CHANGELOG.md, MIGRATION.md, or GitHub releases.
│         └── NO  → Flag as BLOCKED. Note it in the completion report.
└── NO  → Package is abandoned.
          Find a maintained replacement (search npm, check GitHub issues).
          Flag as BLOCKED if no replacement exists.
```

### 3. Bump compatible packages

```bash
npm install <package>@latest   # or pin to a specific version
```

Always check the package changelog for breaking changes before bumping a major
version. Do not bump major versions blindly — read the migration guide first.

### 4. Completion report

After auditing all packages, produce a summary table:

```
| Package         | Old version | New version | Status              | Notes                        |
|-----------------|-------------|-------------|---------------------|------------------------------|
| some-package    | 1.2.3       | 2.0.0       | ✅ Bumped           | Migration: <url>             |
| other-package   | 3.1.0       | 3.1.0       | ✅ Compatible       |                              |
| legacy-lib      | 0.9.0       | —           | 🚨 BLOCKED          | Abandoned, no replacement    |
| another-lib     | 5.0.0       | 6.0.0       | ⚠️ Needs review     | Major bump, check changelog  |
```

Do not proceed to step 6 if any package is marked 🚨 BLOCKED — raise it with the
user and agree on a path forward first.

## Step 6 — Update package.json engines

```json
"engines": { "node": ">=20.0.0" }
```

## Step 7 — Update tooling configs

- `.nvmrc` / `.node-version` — set target version
- CI config (`.github/workflows/*.yml`, etc.) — update `node-version`

### Dockerfile updates

**1. Bump the base image**

```dockerfile
# Before
FROM node:16-buster
FROM node:16-alpine

# After — match the major version, prefer slim or alpine for production
FROM node:20-bookworm-slim
FROM node:20-alpine
```

Use the same variant (slim/alpine/buster/bookworm) unless you have a reason to
change it. Alpine reduces image size but has musl libc — some native addons
require glibc (use `-slim` or `-bookworm-slim` in that case).

**2. Verify system dependencies are still satisfied**

Native npm packages (bcrypt, canvas, sharp, node-sass, etc.) need OS-level
libraries. After bumping the base image, check that all required packages are
still installed via `apt-get` / `apk`.

Grep for native addons in the project:

```bash
grep -r "\"gypfile\": true" node_modules/*/package.json 2>/dev/null | cut -d/ -f2
# or
find node_modules -name "binding.gyp" -maxdepth 3 | cut -d/ -f2
```

Cross-reference each native addon against its known system dependencies:

| Package               | Required OS libs                                                  |
| --------------------- | ----------------------------------------------------------------- |
| `bcrypt` / `bcryptjs` | `python3`, `make`, `g++` (build only)                             |
| `canvas`              | `libcairo2-dev`, `libpango1.0-dev`, `libjpeg-dev`, `libgif-dev`   |
| `sharp`               | `libvips-dev` (or bundled — check version)                        |
| `node-sass`           | `python3`, `make`, `g++`, `libsass` — replace with `sass` instead |
| `node-gyp` (any)      | `python3`, `make`, `g++`                                          |

Example Dockerfile pattern for build-time deps:

```dockerfile
FROM node:20-bookworm-slim

# Install OS-level build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    make \
    g++ \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY . .
```

**3. Rebuild and verify the image**

```bash
docker build -t app:node20-test .
docker run --rm app:node20-test node --version
docker run --rm app:node20-test npm run build
docker run --rm app:node20-test npm test
```

If native addons fail to compile, the error will surface at `npm ci`. Read the
error to identify the missing OS library, add it to the `apt-get` / `apk add`
block, and rebuild.

## Step 8 — Verify tests

```bash
npm install   # Rebuild native deps if any
npm test
```

Flag any test failures caused by the version change and relate them back to the
breaking changes in the migration guide.

## Step 9 — Verify build

If the project has a build step, run it and confirm it produces no errors:

```bash
npm run build
```

Common issues after a Node.js upgrade:

- **TypeScript** — bump `target` / `lib` in `tsconfig.json` to match the new
  runtime (e.g. `ES2022` for Node 20). Older targets may cause type errors for
  APIs that are now natively available.
- **Webpack / esbuild / Rollup** — bundler plugins that relied on polyfills for
  APIs now native in Node 20 (e.g. `fetch`, `AbortController`) may conflict.
  Remove the polyfill and let the runtime provide the API.
- **`--openssl-legacy-provider`** — if the build tool sets this flag (common in
  webpack 4 on Node 18+), either upgrade the bundler or remove the flag once
  OpenSSL-dependent deps are updated.

If no `build` script exists, skip this step.

## Step 10 — Verify Docker build

Build the image locally and confirm the container starts correctly:

```bash
# Build
docker build -t app:node-upgrade-test .

# Confirm Node version inside the image matches the target
docker run --rm app:node-upgrade-test node --version

# Run the build step inside the container (if applicable)
docker run --rm app:node-upgrade-test npm run build

# Run tests inside the container
docker run --rm app:node-upgrade-test npm test
```

Common issues:

- **Native addons fail to compile** — the error appears at `npm ci` or
  `npm install`. Identify the missing OS library from the error message and add
  it to the `apt-get` / `apk add` block in the Dockerfile. See the native addon
  table in Step 7 for known dependencies.
- **Wrong base image variant** — if you switched from a glibc image (debian,
  bookworm, buster) to an Alpine (musl libc) image, native addons that require
  glibc will fail at runtime. Use `-slim` or `-bookworm-slim` instead of
  `-alpine` when native addons are present.
- **`npm ci` vs `npm install`** — prefer `npm ci` in Dockerfiles for
  reproducible installs. If the lockfile is out of date after bumping deps, run
  `npm install` locally first to regenerate it, then commit the updated
  `package-lock.json` before rebuilding the image.

## Step 11 — Check for license violations

Skip this step if the project has no `license` field in `package.json`.

Bumping dependencies during a Node.js upgrade can silently introduce packages
with incompatible licenses. Run this check after all dependency changes are
final.

### 1. Detect the project license

```bash
node -e "const p = require('./package.json'); console.log(p.license || 'NOT SET')"
```

If the output is `NOT SET` or `UNLICENSED`, skip this step.

### 2. Scan dependencies for violations

Read the project license from `package.json` and run `scan --allowOnly` with
that license. This ensures every dependency uses a license compatible with the
project's own.

```bash
npx @your-org/license-checker scan --allowOnly <project-license> --ignoreRootPackageLicense
# Example for an MIT project:
npx @your-org/license-checker scan --allowOnly MIT --ignoreRootPackageLicense
```

The process fails (exit code 1) if any dependency uses a license not in the
allowed list. An error report (`license-error-<timestamp>.md`) is generated
listing all offending packages.

### 3. Handle violations

If the scan exits with code 1, it generates an error report file
(`license-error-<timestamp>.md`) listing the offending packages.

For each violating package:

```
Is a license-compliant version available?
├── YES → Bump to that version and re-run the scan.
└── NO  → Find a replacement package or request a legal exception.
          Flag as BLOCKED and raise with the team before proceeding.
```

Do not proceed past this step if any violation remains unresolved.

---

## Handoff

After completing all steps, emit:

```
***HANDOFF BLOCK***
Skill/Agent : nodejs-version-migrator
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated Node.js from <vX> to <vY>
- Codemods run: <list or "none">
- Package audit completed: <N> packages checked, <N> bumped, <N> blocked
- Framework migration (NestJS etc.): <completed | skipped | not needed>
- Docker base image updated: <yes | no | n/a>
- CI/CD updated: <yes | no | n/a>
- License check: <passed | blocked: <reason> | skipped>

### Artifacts produced
| File | Change |
|------|--------|
| package.json | modified — updated engines field and package versions |
| .nvmrc / .node-version | modified — set to <target version> |
| Dockerfile | modified — updated base image to node:<vY> |
| .github/workflows/*.yml | modified — updated node-version |
| <other files> | modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <package name — reason — next: suggested action | "—">

### For the next agent or step
Node.js migrated from <vX> to <vY>. TypeScript: <yes|no>. Framework: <NestJS | Express | none>.
Package manager: <npm | pnpm | yarn>. Docker: updated base image to node:<vY>.
Any blocked packages are listed above and require team sign-off before proceeding.
***END HANDOFF BLOCK***
```
