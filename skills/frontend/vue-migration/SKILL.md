---
name: vue-migration
description: >
  Guides migration of Vue 2 applications to Vue 3. Handles breaking changes,
  removed APIs, ecosystem upgrades (Vue Router, Vuex/Pinia, Vite), and the
  optional @vue/compat migration build strategy.
  Use when asked to upgrade a Vue 2 app to Vue 3, migrate Vue components,
  or update the Vue ecosystem (router, store, tooling) to Vue 3-compatible versions.
---

# Vue Migration Skill

## Token Discipline

Load [references/full-guide.md](references/full-guide.md) only for the previous full primary workflow. Load `v2-to-v3.md` only for Vue API/component changes. Load `ecosystem.md` only when router/store/tooling migration is in scope.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Detect Vue version from `package.json` or `node_modules/vue/package.json`.
2. Check `vue-router`, `vuex`, `pinia`, `vite`, `@vue/cli-service`, TypeScript, and IE11 requirements.
3. Choose direct migration or `@vue/compat` path.
4. Emit compact context: `Context: vue-migration; Vue app; pkg=<manager>; ts=<yes|no>; versions=Vue <from> -> 3; files=<read>; gaps=<items>`.

## Strategy Router

| Project | Strategy |
|---|---|
| Small/simple app | Direct Vue 3 migration |
| Medium/large or many dependencies | Use `@vue/compat`, resolve warnings incrementally |
| IE11 required | Do not migrate; Vue 3 dropped IE11 support |

## Reference Router

| Need | Load |
|---|---|
| Vue API/directive/component/render/removed API changes | [v2-to-v3.md](v2-to-v3.md) |
| Router/store/Vite/Volar/DevTools/SSR/JSX changes | [ecosystem.md](ecosystem.md) |
| Previous full primary checklist | [references/full-guide.md](references/full-guide.md) |

## Critical Rules

- Do not migrate when IE11 support is required.
- If using `@vue/compat`, resolve warnings before switching to Vue 3 proper.
- Upgrade `vue-router` and `vuex`/Pinia alongside Vue when used.
- Replace `vue-template-compiler` with `@vue/compiler-sfc` for Vue 3.
- Replace `$on/$off/$once` event bus and filters; they are removed.

## Workflow

1. Audit peer dependencies before changing Vue.
2. Install Vue 3 directly or install `@vue/compat` and alias `vue` to it.
3. Fix compiler/runtime warnings by category.
4. Upgrade ecosystem packages when present.
5. Run `vue-tsc`, tests, and build when available.

Compact handoff:

```text
Handoff: vue-migration; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=Vue <from> -> 3, strategy=<direct|compat>, router=<v>, state=<tool>
```
