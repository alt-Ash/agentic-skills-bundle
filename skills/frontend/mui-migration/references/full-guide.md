---
name: mui-migration
description: >
  Guides migration between Material UI (MUI) versions (v3→v4, v4→v5, v5→v6, v6→v7, v7→v9)
  and Grid v2 upgrade. Use when asked to upgrade MUI, migrate @material-ui packages, or update
  MUI component APIs to a newer major version.
version: "1.1.0"
category: frontend
---

# MUI Migration Skill

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## What this skill does

Handles all Material UI major version migrations. Routes to the correct version-specific
reference file and provides the general migration strategy. Always migrate sequentially —
do not skip versions. Does NOT cover MUI X packages (Data Grid, Date Pickers) — those follow
their own versioning.

---

## Phase 0 — Gather context

1. Check `package.json` for the current MUI version: look for `@mui/material` or `@material-ui/core`.
2. Confirm the target version from the user prompt.
3. Check for TypeScript: `tsconfig.json` present?
4. Check if MUI X packages are installed: `@mui/x-data-grid`, `@mui/x-date-pickers`.

Emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : mui-migration
Timestamp   : <ISO-8601 date>

### Project
- Type            : React SPA
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <version or "unknown">
- Framework       : React <version>
- UI library      : MUI <current version> → <target version>
- Build tool      : <Vite | CRA | webpack>
- Test runner     : <vitest | jest | none>
- Linter          : <eslint | biome | none>

### Migration context
- Current version : MUI <vX>
- Target version  : MUI <vY>
- Migration hops  : <e.g. "v4 → v5 → v6 (two hops)">

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no | unknown>
- Storybook       : <yes | no>

### Files read
- package.json

### Gaps / unknowns
- <description or "none">
***END CONTEXT BLOCK***
```

---

## Migration Path

```
v3 → v4 → v5 → v6 → v7 → v9
```

Note: There is no v8 — the version went from v7 directly to v9.

## Reference Files

Load the relevant reference for the target migration:

- v3 → v4: See [v3-to-v4.md](v3-to-v4.md)
- v4 → v5: See [v4-to-v5.md](v4-to-v5.md)
- v5 → v6: See [v5-to-v6.md](v5-to-v6.md)
- v6 → v7: See [v6-to-v7.md](v6-to-v7.md)
- v7 → v9: See [v7-to-v9.md](v7-to-v9.md)
- Grid v1 → Grid v2: See [grid-v2.md](grid-v2.md)

## General Strategy

- [ ] Read the relevant reference file for the target version
- [ ] Update package.json dependencies first
- [ ] Run official codemods (where available) before manual changes
- [ ] Verify app runs after each step — commit before continuing
- [ ] Apply manual breaking changes from the reference
- [ ] Run TypeScript checks if the project uses TypeScript

## Codemods (General Pattern)

```bash
npx @mui/codemod@latest <version>/<codemod-name> <path>
```

The `preset-safe` codemod covers the bulk of v4→v5 changes:
```bash
npx @mui/codemod@latest v5.0.0/preset-safe <path>
```

## Package Rename (v4 → v5)

```
@material-ui/core      → @mui/material
@material-ui/icons     → @mui/icons-material
@material-ui/styles    → @mui/styles
@material-ui/lab       → @mui/lab
@material-ui/system    → @mui/system
```

## MUI X Packages

MUI X packages (`@mui/x-data-grid`, `@mui/x-date-pickers`, etc.) do NOT follow the same versioning as `@mui/material`. Do not update them during a Material UI version migration unless explicitly required.

---

## Handoff

After completing the migration, emit:

```
***HANDOFF BLOCK***
Skill/Agent : mui-migration
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated MUI from <vX> to <vY>
- Ran codemods: <list or "none">
- <other actions>

### Artifacts produced
| File | Change |
|------|--------|
| package.json | modified — updated MUI package versions |
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
MUI migrated from <vX> to <vY>. TypeScript: <yes|no>. Any remaining manual steps
or blocked packages are listed above. The project is on React <version>.
***END HANDOFF BLOCK***
```
