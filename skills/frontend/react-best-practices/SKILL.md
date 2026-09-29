---
name: react-best-practices
description: >
  React best practices for frontend projects. Covers functional components, Context API state
  management, hooks, re-render optimization, data fetching, and routing patterns.
  Apply when writing, reviewing, or refactoring React components, context providers, routes,
  or API calls in a React project.
---

# React Best Practices

## Token Discipline

Load [references/full-guide.md](references/full-guide.md) only when detailed rule tables, external rule URLs, or examples are needed.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Read enough project code to identify React version, router, TypeScript, data fetching, state management, and nearby style conventions.
2. Treat project conventions as source of truth; this skill is fallback guidance.
3. Emit compact context: `Context: react-best-practices; React app; pkg=<manager>; ts=<yes|no>; versions=React <v>, router=<name>; files=<read>; gaps=<items>`.

## Core Rules

- Functional components only; do not introduce class components.
- Do not define components inside components; it remounts on each render.
- Prefer derived render-time values over syncing state in `useEffect`.
- Put interaction logic in event handlers, not effects.
- Use ternaries for conditional rendering when `0` could render accidentally.
- Route HTTP through existing repository/service layers; do not import clients directly in components unless project convention already does.
- Use declarative route guards/redirects; avoid imperative navigation in effects.
- Access storage through utilities; do not read/write sensitive values directly in components.

## Performance Rules

- Do not add `useMemo`/`useCallback` by default. Use them only for expensive computation, stable non-primitive values required by memoization/effect deps, or when project convention/compiler guidance supports it.
- Use primitive effect dependencies where possible.
- Use `React.memo` only when props are stable and parent re-renders are proven or likely.
- Use `useRef` for mutable values that must not trigger re-render.
- Parallelize independent async calls with `Promise.all`.

## Reference Router

| Need | Load full guide section |
|---|---|
| Hooks/effect cleanup/deps | Hooks, Re-render optimization, Advanced patterns |
| Fetching/waterfalls/Suspense | Data Fetching, Async patterns |
| Router guards/lazy screens | Routing |
| Bundle/render performance | Bundle optimization, Rendering |
| Server/Next-like patterns | Server patterns |
| External Vercel rule URLs | References table in full guide |

## Done When

- Changes follow existing project patterns.
- No avoidable effect-sync, inline component definitions, or direct sensitive storage access were introduced.
- Available checks pass or blockers are reported.

Compact handoff:

```text
Handoff: react-best-practices; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=<patterns applied>
```
