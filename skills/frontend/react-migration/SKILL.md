---
name: react-migration
description: Migrates React applications to React 18 or 19. Handles breaking changes, deprecated APIs, codemods, TypeScript type updates, and testing configuration. Use when upgrading a React app from an older version, or when asked to migrate to React 18 or React 19.
---

# React Migration Skill

## Token Discipline

Load this primary router first. Load [references/full-guide.md](references/full-guide.md) only when the chosen migration path needs detailed examples, known package tables, or troubleshooting.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Detect current React version from `package.json` or `node_modules/react/package.json`.
2. Confirm target version from user prompt. If absent, ask one short question.
3. Read build/test/TypeScript config only as needed to scope migration.
4. Emit compact context: `Context: react-migration; React app; pkg=<manager>; ts=<yes|no>; versions=React <from> -> <to>; files=<read>; gaps=<items>`.

## Path Router

| Current -> target | Required path | Load detail? |
|---|---|---|
| `<18` -> `18` | Peer dep audit, install React 18, replace root/hydration APIs, TS children fixes, test env setup | Full guide if peer conflicts or TS errors appear |
| `<18` -> `19` | Migrate to 18 first, verify, then migrate to 19 | Full guide; multi-hop needs detailed ordering |
| `18.x` -> `19` | Prefer 18.3 warning pass, codemod, install React 19, fix removed APIs/types/tests | Full guide for removed APIs and TS fixes |
| `18.3` -> `19` | Codemod, install React 19, fix removed APIs/types/tests | Full guide only if warnings/errors appear |

## Non-Negotiable Order

1. Audit peer dependencies before changing `react` or `react-dom`.
2. Resolve incompatible or abandoned packages before installing the target React version.
3. Run codemods before broad manual edits.
4. Verify build and tests after each major hop.

## Critical Checks

- React 18: `ReactDOM.render` / `hydrate` / `unmountComponentAtNode`, implicit `children` types, StrictMode effect cleanup, `act` environment.
- React 19: propTypes/defaultProps on function components, legacy context, string refs, `findDOMNode`, `act` import, `useRef` argument, ref callback returns, `react-test-renderer`.
- TypeScript: run typecheck after codemods and before final handoff.

## Minimal Commands

```bash
npm install react react-dom
npm install --save-dev @types/react @types/react-dom  # TypeScript only
npx codemod@latest react/19/migration-recipe        # React 19 only
npx types-react-codemod@latest preset-19 ./src      # React 19 + TypeScript only
```

Use the project package manager and exact target versions.

## Done When

- React packages and types are on target version.
- Deprecated APIs for the target version are removed or intentionally blocked.
- Available tests, typecheck, lint, and build pass or blockers are reported.

Compact handoff:

```text
Handoff: react-migration; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=React <from> -> <to>, ts=<yes|no>, remaining=<items>
```
