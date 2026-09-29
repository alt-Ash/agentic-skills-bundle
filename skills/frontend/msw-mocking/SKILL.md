---
name: msw-mocking
description: >
  Mock Service Worker setup and usage for frontend projects. Covers installation, handler
  authoring, browser dev setup, and test patterns for REST backends.
  Apply when writing tests that involve network requests, adding new API mocks, or setting
  up MSW for the first time in a project.
---

# MSW Mocking

## Token Discipline

Load [references/full-guide.md](references/full-guide.md) only when detailed browser/test wiring examples or MSW API examples are needed.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Determine usage context: browser dev, tests, or both.
2. Read `package.json`, build/test config, existing setup files, and API service/repository files.
3. Emit compact context: `Context: msw-mocking; <React|Vue>; pkg=<manager>; ts=<yes|no>; versions=msw <v|none>, test=<runner>; files=<read>; gaps=<items>`.

## Endpoint Inventory Before Code

Do not invent schemas. Build this inventory from actual callers before writing handlers:

| Endpoint | Method | Request | Response fields used | Edge cases |
|---|---|---|---|---|
| `<url>` | `<GET|POST|...>` | `<body/query>` | `<fields>` | `<values>` |

Search likely locations: `src/repository/`, `src/api/`, `src/services/`, `src/hooks/`, `src/store/`; patterns: `fetch(`, `axios.`, `apiClient.`, `useQuery`, `useMutation`, `/api/`.

## Setup Router

| Needed | Do | Load detail? |
|---|---|---|
| Browser dev mocks | Install MSW, run `npx msw init public/ --save`, create `handlers` + `browser`, start worker before app render | Full guide for entry-point wiring |
| Test mocks | Install MSW, create `handlers` + `server`, configure setup file with listen/reset/close | Full guide for Vitest/Jest config |
| Both | Share `handlers`; keep browser and server setup separate | Full guide if either setup fails |

## Rules

- Happy paths live in shared handlers; error/edge cases use per-test `server.use()` overrides.
- Register specific paths before broad prefixes.
- Use relative handler URLs when app uses Vite proxy; use the same env-var base URL when app sends absolute URLs.
- Do not use wildcards for multi-backend apps; they can match wrong hosts.
- Start browser worker before app render so initial requests are intercepted.
- Always call `server.resetHandlers()` after each test.

## Minimal File Shape

```text
src/mocks/handlers.(js|ts)  # shared happy paths
src/mocks/browser.(js|ts)   # setupWorker, browser dev only
src/mocks/server.(js|ts)    # setupServer, tests only
```

## Done When

- Endpoint inventory matches code usage.
- Handlers exist for required happy paths.
- Browser and/or test setup is wired only for requested contexts.
- Available tests/build pass or blockers are reported.

Compact handoff:

```text
Handoff: msw-mocking; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=MSW contexts=<browser|tests|both>, handlers=<N>, base-url=<strategy>
```
