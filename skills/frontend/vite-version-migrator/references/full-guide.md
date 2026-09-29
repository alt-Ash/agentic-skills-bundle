---
name: vite-version-migrator
description: Guides safe Vite version upgrades by auditing breaking changes, updating config and plugins, and verifying builds. Use when the user wants to upgrade Vite versions, migrate from one Vite version to another, or asks about Vite breaking changes between versions.
version: "1.1.0"
category: frontend
---

# Vite Version Migrator

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## Workflow

Copy this checklist and track progress:

```
Migration Progress:
- [ ] 1. Identify current and target Vite versions
- [ ] 2. Read the relevant migration guide
- [ ] 3. Audit the codebase for affected patterns
- [ ] 4. Update Vite and peer dependencies
- [ ] 5. Update vite.config.js/ts for breaking config changes
- [ ] 6. Update Node.js version requirement if needed
- [ ] 7. Update CI/CD configs
- [ ] 8. Run dev server and verify
- [ ] 9. Run build and verify
- [ ] 10. Check for license violations (if project has a license)
```

## Available migration guides

| From → To | Guide                                                    |
| --------- | -------------------------------------------------------- |
| v4 → v5   | [migrations/v4-to-v5.md](migrations/v4-to-v5.md)        |
| v5 → v6   | [migrations/v5-to-v6.md](migrations/v5-to-v6.md)        |
| v6 → v7   | [migrations/v6-to-v7.md](migrations/v6-to-v7.md)        |
| v7 → v8   | [migrations/v7-to-v8.md](migrations/v7-to-v8.md)        |

## Multi-hop migrations

If the gap between current and target versions is not covered by a single guide,
chain the available guides in order. Complete all steps for each hop before
starting the next.

**Example: v4 → v6**

```
Hop 1: v4 → v5  (follow all steps, run dev + build, confirm green)
Hop 2: v5 → v6  (follow all steps, run dev + build, confirm green)
```

Rules:

- Never skip an intermediate version that has a guide.
- Do not proceed to the next hop until the build passes for the current one.

If no guide exists for an intermediate hop, note the gap to the user and proceed
with manual review of the Vite changelog for that version range.

## Step 1 — Identify versions

If not told explicitly, check:

```bash
cat package.json | grep '"vite"'
npx vite --version
```

After identifying versions, emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : vite-version-migrator
Timestamp   : <ISO-8601 date>

### Project
- Type            : <React SPA | Vue SPA | other>
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <version>
- Framework       : <React | Vue> <version>
- UI library      : <MUI | none>
- Build tool      : Vite <current version>
- Test runner     : <vitest | jest | none>
- Linter          : <eslint | biome | none>

### Migration context
- Current version : Vite <vX>
- Target version  : Vite <vY>
- Migration hops  : <e.g. "v5 → v6 (single)" or "v4 → v5 → v6 (two hops)">

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no | unknown>
- Storybook       : <yes | no>

### Files read
- package.json
- vite.config.(js|ts)

### Gaps / unknowns
- <Plugin versions that need updating | "none">
***END CONTEXT BLOCK***
```

## Step 2 — Read the migration guide

Load the appropriate file from `migrations/` for the version pair. It contains
breaking changes, required config updates, and dependency changes.

## Step 3 — Audit codebase

Grep for deprecated or removed patterns listed in the migration guide before
making any changes.

## Step 4 — Update Vite and peer dependencies

```bash
npm install vite@<target-version> --save-dev
```

Check the migration guide for related peer dependency bumps (e.g. Rollup,
PostCSS loaders, framework plugins like `@vitejs/plugin-react`).

Always check plugin changelogs before bumping major versions.

## Step 5 — Update vite.config.js/ts

Apply the config changes documented in the migration guide. Common areas:

- `resolve.conditions` and `ssr.resolve.conditions`
- `worker.plugins` (must be a function in v5+)
- `build.target` default value changes
- Removed or renamed options

## Step 6 — Update Node.js requirement

Each Vite major may raise the minimum Node.js version. Check the migration guide
and update:

- `.nvmrc` / `.node-version`
- `package.json` `engines` field
- CI/CD node-version setting

## Step 7 — Update CI/CD configs

Update `.github/workflows/*.yml` (or equivalent) to use the new minimum Node.js
version if it changed.

## Step 8 — Verify dev server

```bash
npm run dev
```

Check the browser console for errors or deprecation warnings related to the
upgrade.

## Step 9 — Verify build

```bash
npm run build
npm run preview   # if available
```

Flag any build failures and relate them back to the breaking changes in the
migration guide.

## Step 10 — Check for license violations

Skip this step if the project has no `license` field in `package.json`.

```bash
node -e "const p = require('./package.json'); console.log(p.license || 'NOT SET')"
```

If a license is set, scan for violations after all dependency changes are final:

```bash
npx @your-org/license-checker scan --allowOnly <project-license> --ignoreRootPackageLicense
```

Handle violations following the same decision tree as in the Node.js migrator:
bump to a compliant version, find a replacement, or flag as BLOCKED and raise
with the team.

---

## Handoff

After completing all steps, emit:

```
***HANDOFF BLOCK***
Skill/Agent : vite-version-migrator
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated Vite from <vX> to <vY>
- Updated vite.config: <yes | no>
- Updated plugins: <list or "none">
- Node.js requirement updated: <yes | no | n/a>
- CI/CD updated: <yes | no | n/a>
- License check: <passed | blocked: <reason> | skipped>

### Artifacts produced
| File | Change |
|------|--------|
| package.json | modified — updated vite and plugin versions |
| vite.config.(js|ts) | modified — <description of changes> |
| .nvmrc | modified — <new node version> / skipped |
| <other files> | modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <item or "—">

### For the next agent or step
Vite migrated from <vX> to <vY>. Node minimum: <version>. TypeScript: <yes|no>.
Framework: <React | Vue> <version>. Test runner: <vitest | jest | none>.
Vite config at: vite.config.(js|ts).
***END HANDOFF BLOCK***
```
