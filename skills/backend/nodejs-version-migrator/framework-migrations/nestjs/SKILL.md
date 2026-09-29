---
name: nestjs-version-migrator
description: Guides safe NestJS version upgrades by applying official migration guides step by step. Use when the user wants to upgrade NestJS, migrate between NestJS versions, or asks about NestJS breaking changes between versions.
---

# NestJS Version Migrator

## Token Discipline

Load only the guide(s) for required NestJS hops. Do not load all version files up front.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## Workflow

Copy this checklist and track progress:

```
NestJS Migration Progress:
- [ ] 1. Identify current and target NestJS versions
- [ ] 2. Read the relevant migration guide(s)
- [ ] 3. Audit the codebase for affected patterns
- [ ] 4. Apply breaking-change fixes
- [ ] 5. Upgrade @nestjs/* packages
- [ ] 6. Update peer dependencies (Express, Fastify, TypeScript, etc.)
- [ ] 7. Run tests and verify
- [ ] 8. Run build and verify
```

## Available migration guides

| From → To | Guide |
|---|---|
| v8 → v9 | [v8-to-v9.md](v8-to-v9.md) |
| v9 → v10 | [v9-to-v10.md](v9-to-v10.md) |
| v10 → v11 | [v10-to-v11.md](v10-to-v11.md) |

Emit compact context after version detection:

```text
Context: nestjs-version-migrator; NestJS app; pkg=<manager>; ts=<yes|no>; versions=NestJS <from> -> <to>; files=<read>; gaps=<items>
```

## Multi-hop migrations

If the gap between current and target versions spans multiple guides, chain them in order. Complete all steps for each hop before starting the next.

**Example: v8 → v11**

```
Hop 1: v8 → v9   (complete all steps, run tests + build, confirm green)
Hop 2: v9 → v10  (complete all steps, run tests + build, confirm green)
Hop 3: v10 → v11 (complete all steps, run tests + build, confirm green)
```

Rules:

- Never skip an intermediate version that has a guide.
- Do not proceed to the next hop until tests pass for the current one.

## Step 1 — Identify versions

```bash
node -e "const p = require('./package.json'); console.log(p.dependencies?.['@nestjs/core'] || p.devDependencies?.['@nestjs/core'] || 'not found')"
```

## Step 2 — Read the migration guide

Load the appropriate file for the version pair. It contains breaking changes and required code updates.

## Step 3 — Audit codebase

Before making changes, grep for the patterns listed as breaking changes in the guide. This gives a clear picture of the migration scope.

## Step 4 — Apply breaking-change fixes

Work through each breaking change in the guide and update the codebase. Pay special attention to:

- Route wildcard syntax (Express v5 in NestJS v11)
- Query string parsing behavior
- Module-level imports that moved packages
- Deprecated APIs that have been removed

## Step 5 — Upgrade @nestjs/* packages

```bash
npx npm-check-updates "@nestjs/*" -u
npm install
```

Or target a specific version:

```bash
npm install @nestjs/common@<version> @nestjs/core@<version> @nestjs/platform-express@<version>
```

Upgrade all other `@nestjs/*` packages to the same major version.

## Step 6 — Update peer dependencies

Each NestJS major version may change its peer dependency requirements. Check the migration guide for:

- Express / Fastify version changes
- TypeScript minimum version
- Node.js minimum version

## Step 7 — Verify tests

```bash
npm test
```

## Step 8 — Verify build

```bash
npm run build
```

Compact handoff:

```text
Handoff: nestjs-version-migrator; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=NestJS <from> -> <to>, platform=<express|fastify>
```
