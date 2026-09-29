---
name: mui-migration
description: >
  Guides migration between Material UI (MUI) versions (v3→v4, v4→v5, v5→v6, v6→v7, v7→v9)
  and Grid v2 upgrade. Use when asked to upgrade MUI, migrate @material-ui packages, or update
  MUI component APIs to a newer major version.
---

# MUI Migration Skill

## Token Discipline

Load only docs for required hops. Load [references/full-guide.md](references/full-guide.md) only for the previous full primary workflow.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Read `package.json` for `@mui/material` or `@material-ui/core` and target version.
2. Check TypeScript and MUI X packages only to avoid accidental unsupported upgrades.
3. Determine sequential hops. MUI has no v8: `v7 -> v9` is direct.
4. Emit compact context: `Context: mui-migration; React+MUI; pkg=<manager>; ts=<yes|no>; versions=MUI <from> -> <to>; files=<read>; gaps=<items>`.

## Guide Router

| Hop | Load |
|---|---|
| v3 -> v4 | [v3-to-v4.md](v3-to-v4.md) |
| v4 -> v5 | [v4-to-v5.md](v4-to-v5.md) |
| v5 -> v6 | [v5-to-v6.md](v5-to-v6.md) |
| v6 -> v7 | [v6-to-v7.md](v6-to-v7.md) |
| v7 -> v9 | [v7-to-v9.md](v7-to-v9.md) |
| Grid v1 -> Grid v2 | [grid-v2.md](grid-v2.md) |

## Workflow

1. Load only required hop docs.
2. Update dependencies for one hop.
3. Run official codemods when available.
4. Apply manual breaking changes from loaded docs.
5. Run typecheck/build/tests before next hop.

## Critical Rules

- Always migrate sequentially; do not skip documented major hops.
- Do not update MUI X packages unless explicitly required or their peer range blocks the migration.
- Only use v4 -> v5 package rename guidance when that hop is in scope.
- Commit/checkpoint between hops when possible.

Compact handoff:

```text
Handoff: mui-migration; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=MUI <from> -> <to>, hops=<list>, remaining=<items>
```
