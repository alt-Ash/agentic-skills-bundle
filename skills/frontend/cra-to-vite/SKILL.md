---
name: cra-to-vite
description: Migrates a Create React App (react-scripts) project to Vite and migrates tests from Jest to Vitest. Handles dependency swap, config creation, index.html restructuring, env variable renaming, TypeScript setup, script updates, and Jest-to-Vitest migration. Use when asked to migrate from CRA to Vite, replace react-scripts with vite, modernize the build tooling of a React app, or migrate Jest tests to Vitest.
---

# CRA to Vite Migration

## Token Discipline

Load this primary skill first. Load reference docs only when the matching condition is true.

| Condition | Load |
|---|---|
| Need full legacy checklist or edge-case detail | [references/full-guide.md](references/full-guide.md) |
| Jest tests exist or test migration fails | [JEST-TO-VITEST.md](JEST-TO-VITEST.md) |
| Build/dev errors occur | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Confirm project uses CRA or webpack-era React tooling: `react-scripts`, `public/index.html`, CRA scripts, or webpack configs.
2. Read `package.json`, `public/index.html`, entry file, `tsconfig.json` if present, `.env*`, and deployment files only if they exist.
3. Emit compact context: `Context: cra-to-vite; React CRA; pkg=<manager>; ts=<yes|no>; versions=react-scripts <v>, node <v>; files=<read>; gaps=<items>`.

## Migration Phases

| Phase | Action | Load extra docs? |
|---|---|---|
| 1. Audit | Find entry file, env vars, proxy needs, `require()`, `module.exports`, JSX in `.js`, casing mismatches, absolute imports | Full guide only if any edge case appears |
| 2. Runtime | Move to Node 20 baseline and update `.nvmrc` / `.node-version` if project uses one | Full guide for package engine compatibility detail |
| 3. Dependencies | Remove `react-scripts` or webpack packages; install `vite` and `@vitejs/plugin-react` | No |
| 4. Vite config | Create `vite.config.(js|ts)`; add aliases/proxy only when audit found need | Full guide for proxy/env details |
| 5. HTML/env | Move `public/index.html` to root, add module script, rename `REACT_APP_*` to `VITE_*`, replace source `process.env.REACT_APP_*` with `import.meta.env.VITE_*` | Full guide for server-injected globals |
| 6. TypeScript | Add `src/vite-env.d.ts`; update module settings only when TS project exists | Full guide if TS errors surface |
| 7. Tests | Migrate Jest to Vitest only when tests/Jest config exist | `JEST-TO-VITEST.md` |
| 8. Cleanup | Remove CRA-only files; update scripts and `.gitignore` from `build/` to `dist/` | No |
| 9. Deployment | Update CI, Docker, Helm/K8s only when those files exist | Full guide deployment section |
| 10. Verify | Run dev/build/preview/test commands available in `package.json` | `TROUBLESHOOTING.md` on failure |

## Critical Rules

- Vite exposes browser env vars as `import.meta.env.VITE_*`; rename both `.env*` keys and source references.
- If relative API paths, CRA proxy, or `setupProxy.js` exist, configure `server.proxy`; Vite otherwise returns `index.html` for API calls.
- Prefer `127.0.0.1` over `localhost` in proxy targets when backend may listen only on IPv4.
- `.js` files containing JSX must become `.jsx` for Vite 8/Rolldown.
- Convert browser-source `require()` / `module.exports` to ESM before verifying build.
- Do not keep stale CRA/webpack config unless project still uses it for another build target.
- Do not migrate tests blindly; if no Jest tests/config exist, skip test migration.

## Minimal Commands

```bash
npm uninstall react-scripts
npm install -D vite @vitejs/plugin-react
npm install -D vitest jsdom   # only when migrating tests
```

Use the actual package manager found in the project.

## Done When

- CRA/webpack build path is replaced by Vite.
- Entry HTML is at project root and points to the real entry file.
- Env vars, scripts, output folder, and deployment artifacts are updated where present.
- Available checks pass or blockers are reported.

Compact handoff:

```text
Handoff: cra-to-vite; status=<completed|partial|blocked>; changed=<files>; checks=<dev/build/test>; blockers=<none|items>; next=<summary>
```
