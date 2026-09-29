---
name: vite-version-migrator
description: Guides safe Vite version upgrades by auditing breaking changes, updating config and plugins, and verifying builds. Use when the user wants to upgrade Vite versions, migrate from one Vite version to another, or asks about Vite breaking changes between versions.
---

# Vite Version Migrator

## Token Discipline

Load only the guide(s) for required hops. Load [references/full-guide.md](references/full-guide.md) only for full legacy workflow, license details, or CI/Docker examples.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Identify current and target Vite versions from prompt, `package.json`, and Vite CLI if available.
2. Determine hops. Never skip an intermediate guide when one exists.
3. Read `vite.config.(js|ts)`, package manager files, and CI/Docker files only when relevant.
4. Emit compact context: `Context: vite-version-migrator; <React|Vue|other>; pkg=<manager>; ts=<yes|no>; versions=Vite <from> -> <to>; files=<read>; gaps=<items>`.

## Guide Router

| Hop | Load |
|---|---|
| v4 -> v5 | [migrations/v4-to-v5.md](migrations/v4-to-v5.md) |
| v5 -> v6 | [migrations/v5-to-v6.md](migrations/v5-to-v6.md) |
| v6 -> v7 | [migrations/v6-to-v7.md](migrations/v6-to-v7.md) |
| v7 -> v8 | [migrations/v7-to-v8.md](migrations/v7-to-v8.md) |

## Workflow

1. Load required hop docs.
2. Audit config/code for listed deprecated or removed patterns.
3. Update Vite and related plugins/peers.
4. Apply config changes from loaded docs.
5. Update Node minimum and CI only if the target Vite requires it.
6. Run dev server, build, preview if available, and tests if relevant.
7. Run license scan only when project has a license and dependency changes are final.

## Critical Rules

- Do not proceed to next hop until current hop build passes or is blocked with reason.
- Check framework plugins (`@vitejs/plugin-react`, Vue plugin), Rollup/Rolldown, Sass, PostCSS, and test runner compatibility when major versions change.
- For Vite 8/Rolldown, `.js` files containing JSX and Rollup-specific config may need migration.
- Relate failures back to loaded migration docs before inventing fixes.

Compact handoff:

```text
Handoff: vite-version-migrator; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=Vite <from> -> <to>, node-min=<version>, config=<path>
```
