---
name: react-best-practices
description: >
  React best practices for frontend projects. Covers functional components, Context API state
  management, hooks, re-render optimization, data fetching, and routing patterns.
  Apply when writing, reviewing, or refactoring React components, context providers, routes,
  or API calls in a React project.
version: "1.2.0"
category: frontend
---

# React Best Practices

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## What this skill does

Provides opinionated, evidence-backed rules for writing maintainable React code. Covers
components, hooks, context, data fetching, routing, performance, and storage patterns.
Does NOT cover testing (use the `tdd-engineer` agent) or UI library specifics (use
`mui-best-practices`).

## When to use it

- Writing, reviewing, or refactoring React components or hooks in any React project
- Authoring context providers or custom hooks
- Reviewing data-fetching patterns or routing logic

## Do not use when

- The project has its own coding standards that conflict — always check project conventions first
  and treat this skill as a baseline, not an override
- The task is specific to a UI library (use `mui-best-practices` instead)
- The task is test implementation (use the `tdd-engineer` agent instead)

---

## Phase 0 — Gather context

Before applying any pattern from this skill, read enough of the project to answer:

1. What React version is installed? (`package.json` → `react`)
2. What router is in use? (`react-router-dom` version or other router)
3. Is TypeScript enabled? (`tsconfig.json` present)
4. What data-fetching approach is in use? (Axios, fetch, React Query, SWR, project repository layer…)
5. What state management is in use? (Context API, Zustand, Redux…)
6. Is there an existing component to use as a style reference?

Emit the CONTEXT BLOCK before applying any patterns:

```
***CONTEXT BLOCK***
Skill/Agent : react-best-practices
Timestamp   : <ISO-8601 date>

### Project
- Type            : React SPA
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <version or "unknown">
- Framework       : React <version>
- UI library      : <MUI vX | Chakra | none>
- Build tool      : <Vite | CRA | webpack>
- Test runner     : <vitest | jest | none>
- Linter          : <eslint | biome | none>

### Migration context
- Current version : —
- Target version  : —
- Migration hops  : —

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no | unknown>
- Storybook       : <yes | no>

### Files read
- package.json
- <any other files read>

### Gaps / unknowns
- <description or "none">
***END CONTEXT BLOCK***
```

---

## Components

- Functional components only — no class components
- Never define components inside other components — causes remount on every render
- Use ternary for conditional rendering, not `&&` (avoids rendering `0`)
- Extract static JSX outside the component when it never changes

## Hooks

- Derive state during render instead of syncing in `useEffect`
- Use primitive values as effect/memo/callback dependencies, not objects or arrays
- Use `useCallback` for functions passed as props or used as effect deps
- Use `useMemo` for expensive computations or stable non-primitive values — not by default
- Use `useRef` for values that change frequently but must not trigger re-renders
- Pass a function to `useState` for expensive initial values: `useState(() => compute())`
- Use functional setState when new state depends on previous: `setCount(c => c + 1)`
- Put interaction logic in event handlers, not effects

## Context

- Memoize context value with `useMemo`; wrap actions in `useCallback`
- Keep contexts focused: split by domain concern (e.g. auth state separate from feature data)
- Don't subscribe to a context value only needed inside a callback — use a ref instead

## Data Fetching

- Never import HTTP clients directly in components — route through a dedicated repository or
  service layer (e.g. `src/repository/`, `src/api/`, or equivalent found in the project)
- Use `Promise.all` for independent parallel requests to avoid sequential waterfalls:

```js
// bad
const user = await get('me');
const items = await get('items/list');

// good
const [user, items] = await Promise.all([get('me'), get('items/list')]);
```

- Handle loading and error states explicitly in the calling context, not silently

## Routing

> Adapt to whichever router version the project uses. Check `package.json` in Phase 0.

- Use declarative guards/redirects for auth and permission checks, not imperative navigation in effects
- Lazy-load screens with `React.lazy` + `Suspense`
- Keep route guards in dedicated wrapper components, not inside individual screens

**React Router v5 specifics:** use `<Redirect>` for guards; `history.push` in effects is fragile.

**React Router v6 specifics:** use `<Navigate>` for guards; `useNavigate` is acceptable in event handlers but avoid in effects.

## Performance

- Avoid inline object/array props — new reference on every render breaks memoization
- Use `React.memo` on components that receive stable props but re-render due to parent updates
- Skip `useMemo`/`useCallback` for simple primitives — the overhead outweighs the benefit

## Storage

- Access `localStorage` and `sessionStorage` via a dedicated utility module — never read/write
  tokens or sensitive data directly in components
- Store only what is necessary; derive derived state from the API response, not from storage

## References

Each URL points to an in-depth rule definition from [vercel-labs/agent-skills](https://github.com/vercel-labs/agent-skills/tree/main/skills/react-best-practices/rules). Fetch the URL for the relevant rule when you need detailed guidance or examples.

### Advanced patterns

| Rule | URL |
|---|---|
| Effect event dependencies | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/advanced-effect-event-deps.md |
| Event handler refs | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/advanced-event-handler-refs.md |
| Initialize once | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/advanced-init-once.md |
| useLatest hook | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/advanced-use-latest.md |

### Async patterns

| Rule | URL |
|---|---|
| API routes | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/async-api-routes.md |
| Cheap condition before await | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/async-cheap-condition-before-await.md |
| Defer await | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/async-defer-await.md |
| Async dependencies | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/async-dependencies.md |
| Parallel async | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/async-parallel.md |
| Suspense boundaries | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/async-suspense-boundaries.md |

### Bundle optimization

| Rule | URL |
|---|---|
| Analyzable import paths | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/bundle-analyzable-paths.md |
| Barrel imports | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/bundle-barrel-imports.md |
| Conditional loading | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/bundle-conditional.md |
| Defer third-party scripts | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/bundle-defer-third-party.md |
| Dynamic imports | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/bundle-dynamic-imports.md |
| Preload resources | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/bundle-preload.md |

### Client-side patterns

| Rule | URL |
|---|---|
| Event listeners | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/client-event-listeners.md |
| localStorage schema | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/client-localstorage-schema.md |
| Passive event listeners | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/client-passive-event-listeners.md |
| SWR deduplication | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/client-swr-dedup.md |

### JavaScript performance

| Rule | URL |
|---|---|
| Batch DOM/CSS reads and writes | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-batch-dom-css.md |
| Cache function results | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-cache-function-results.md |
| Cache property access | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-cache-property-access.md |
| Cache storage | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-cache-storage.md |
| Combine iterations | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-combine-iterations.md |
| Early exit | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-early-exit.md |
| flatMap and filter | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-flatmap-filter.md |
| Hoist RegExp | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-hoist-regexp.md |
| Index maps | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-index-maps.md |
| Length check first | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-length-check-first.md |
| Min/max loop | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-min-max-loop.md |
| requestIdleCallback | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-request-idle-callback.md |
| Set and Map lookups | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-set-map-lookups.md |
| toSorted immutable | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/js-tosorted-immutable.md |

### Rendering

| Rule | URL |
|---|---|
| Activity component | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-activity.md |
| Animate SVG wrapper | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-animate-svg-wrapper.md |
| Conditional render | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-conditional-render.md |
| content-visibility | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-content-visibility.md |
| Hoist JSX | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-hoist-jsx.md |
| Hydration no flicker | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-hydration-no-flicker.md |
| Hydration suppress warning | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-hydration-suppress-warning.md |
| Resource hints | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-resource-hints.md |
| Script defer/async | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-script-defer-async.md |
| SVG precision | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-svg-precision.md |
| useTransition loading | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rendering-usetransition-loading.md |

### Re-render optimization

| Rule | URL |
|---|---|
| Defer reads | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-defer-reads.md |
| Dependencies | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-dependencies.md |
| Derived state without effect | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-derived-state-no-effect.md |
| Derived state | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-derived-state.md |
| Functional setState | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-functional-setstate.md |
| Lazy state init | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-lazy-state-init.md |
| memo with default value | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-memo-with-default-value.md |
| memo | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-memo.md |
| Move effect to event | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-move-effect-to-event.md |
| No inline components | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-no-inline-components.md |
| Simple expression in memo | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-simple-expression-in-memo.md |
| Split combined hooks | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-split-combined-hooks.md |
| Transitions | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-transitions.md |
| useDeferredValue | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-use-deferred-value.md |
| useRef for transient values | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/rerender-use-ref-transient-values.md |

### Server patterns

| Rule | URL |
|---|---|
| after() non-blocking | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-after-nonblocking.md |
| Auth in Server Actions | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-auth-actions.md |
| LRU cache | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-cache-lru.md |
| React cache() | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-cache-react.md |
| Deduplicate props | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-dedup-props.md |
| Hoist static I/O | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-hoist-static-io.md |
| No shared module state | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-no-shared-module-state.md |
| Parallel fetching | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-parallel-fetching.md |
| Parallel nested fetching | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-parallel-nested-fetching.md |
| Serialization | https://raw.githubusercontent.com/vercel-labs/agent-skills/main/skills/react-best-practices/rules/server-serialization.md |

---

## Done when

- All patterns applied are consistent with the existing project conventions (checked in Phase 0)
- No hardcoded values that belong in config or context
- No components defined inside other components
- `useEffect` is not used as a sync mechanism where derived state would suffice
- Verification checks pass (see Handoff)

---

## Handoff

After applying this skill, emit:

```
***HANDOFF BLOCK***
Skill/Agent : react-best-practices
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- <Reviewed / refactored / authored: file list>

### Artifacts produced
| File | Change |
|------|--------|
| <path> | modified — <description> |

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
<summary of what was changed and any patterns that were established or enforced>
***END HANDOFF BLOCK***
```
