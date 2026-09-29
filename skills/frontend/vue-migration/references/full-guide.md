---
name: vue-migration
description: >
  Guides migration of Vue 2 applications to Vue 3. Handles breaking changes,
  removed APIs, ecosystem upgrades (Vue Router, Vuex/Pinia, Vite), and the
  optional @vue/compat migration build strategy.
  Use when asked to upgrade a Vue 2 app to Vue 3, migrate Vue components,
  or update the Vue ecosystem (router, store, tooling) to Vue 3-compatible versions.
version: "1.1.0"
category: frontend
---

# Vue Migration Skill

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## What this skill does

Guides step-by-step migration of Vue 2 applications to Vue 3. Covers strategy selection
(direct vs `@vue/compat` incremental path), breaking changes, and ecosystem upgrades.
Always detects the current Vue version first. Does NOT handle Vue 3 internal features
unrelated to migration — consult the Vue 3 docs for those.

---

## Phase 0 — Gather context

1. Detect current Vue version:

```bash
node -e "console.log(require('./node_modules/vue/package.json').version)"
```

2. Check `package.json` for: `vue-router`, `vuex`, `pinia`, `vite`, `@vue/cli-service`.
3. Check for TypeScript: `tsconfig.json` + `vue-tsc` present?
4. Check for IE11 requirement in project docs or `browserslist`.
5. Choose migration strategy based on the table below.

Emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : vue-migration
Timestamp   : <ISO-8601 date>

### Project
- Type            : Vue SPA
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <version or "unknown">
- Framework       : Vue <current version>
- UI library      : <Vuetify | Quasar | Element Plus | none>
- Build tool      : <Vite | Vue CLI | webpack>
- Test runner     : <vitest | jest | none>
- Linter          : <eslint | none>

### Migration context
- Current version : Vue <vX>
- Target version  : Vue 3
- Migration hops  : <direct | via @vue/compat | via Vue 2.7 then @vue/compat>

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no | unknown>
- Storybook       : <yes | no>

### Files read
- package.json
- <any other files read>

### Gaps / unknowns
- <IE11 requirement? | "none">
***END CONTEXT BLOCK***
```

---

## Migration strategies

| App complexity | Recommended strategy |
|---|---|
| Small / greenfield | Direct upgrade — install Vue 3, fix breaking changes |
| Medium / many dependencies | Use `@vue/compat` migration build, resolve warnings incrementally |
| Large / IE11 required | Stay on Vue 2 (Vue 3 dropped IE11 support) |

## Migration path

```
Vue 2.x → (Vue 2.7 optional) → Vue 3 via @vue/compat → Vue 3 proper
```

Vue 2.7 backported Composition API and `<script setup>` — upgrading to 2.7 first
makes the Vue 3 transition smaller.

---

## Reference Files

Load the relevant reference for each area:

- Breaking changes (Global API, directives, components, render functions, removed APIs):
  See [v2-to-v3.md](v2-to-v3.md)
- Ecosystem upgrades (Vue Router 4, Pinia/Vuex 4, Vite, Volar, DevTools):
  See [ecosystem.md](ecosystem.md)

---

## General Migration Checklist

- [ ] Read [v2-to-v3.md](v2-to-v3.md) for all breaking changes
- [ ] Read [ecosystem.md](ecosystem.md) for ecosystem upgrade steps
- [ ] Audit peer dependencies before touching Vue version
- [ ] (Optional) Upgrade to Vue 2.7 first to adopt Composition API gradually
- [ ] Install `@vue/compat` and alias `vue` → `@vue/compat` (incremental path)
  OR install Vue 3 directly (direct path)
- [ ] Fix compiler errors and runtime warnings one category at a time
- [ ] Upgrade `vue-router` to v4 and `vuex` to v4 (or migrate to Pinia)
- [ ] Migrate build tooling: Vue CLI → Vite (recommended)
- [ ] Replace IDE plugin: Vetur → Volar
- [ ] Run TypeScript checks if applicable (`vue-tsc`)
- [ ] Run full test suite and build after each major step

## Install commands

```bash
# Direct Vue 3 install
npm install vue@^3 @vue/compiler-sfc

# Migration build (incremental)
npm install vue@^3 @vue/compat@^3 @vue/compiler-sfc
npm uninstall vue-template-compiler

# Vue 2.7 (optional intermediate step)
npm install vue@^2.7
```

## Critical Rules

- **Do NOT skip versions** — if using `@vue/compat`, resolve all warnings before switching to Vue 3 proper
- **IE11**: Vue 3 has no IE11 support — do not migrate if IE11 is required
- **vue-router and vuex** must be upgraded ALONGSIDE Vue, not independently
- **`vue-template-compiler`** must be replaced with `@vue/compiler-sfc` in Vue 3
- **`$on / $off / $once`** are removed — EventBus patterns must be replaced with mitt or Pinia
- **Filters** are removed — replace with computed properties or methods

---

## Handoff

After completing the migration, emit:

```
***HANDOFF BLOCK***
Skill/Agent : vue-migration
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated Vue from <vX> to Vue 3 via <strategy>
- Upgraded vue-router to v4: <yes | no>
- Upgraded vuex / migrated to Pinia: <yes | no>
- Migrated build tooling: <yes | no>
- <other actions>

### Artifacts produced
| File | Change |
|------|--------|
| package.json | modified — updated Vue package versions |
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
Vue migrated from <vX> to Vue 3 using the <direct | @vue/compat> strategy. TypeScript: <yes|no>.
Router: vue-router v4. State: <Pinia | Vuex 4>. Build: <Vite | Vue CLI>. Any remaining
warnings or blocked packages are listed above.
***END HANDOFF BLOCK***
```
