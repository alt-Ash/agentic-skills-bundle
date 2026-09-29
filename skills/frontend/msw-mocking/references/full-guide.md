---
name: msw-mocking
description: >
  Mock Service Worker setup and usage for frontend projects. Covers installation, handler
  authoring, browser dev setup, and test patterns for REST backends.
  Apply when writing tests that involve network requests, adding new API mocks, or setting
  up MSW for the first time in a project.
version: "1.1.0"
category: frontend
---

# MSW Mocking

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## What this skill does

Sets up Mock Service Worker (MSW 2.x) for REST HTTP interception in browser dev mode and/or
test runners. Covers installation, handler authoring from real endpoint discovery, browser
wiring, test setup, and test patterns. HTTP-only, REST-only. No GraphQL, no WebSocket.

---

## Phase 0 — Gather context

Before doing anything else, determine:

1. **Usage context** — is MSW needed for browser dev, tests, or both?
2. Read `package.json` to find: test runner (`vitest` / `jest`), build tool (`vite` / CRA),
   whether `msw` is already installed.
3. Check for a Vite proxy config in `vite.config.js/ts` — this affects handler URL strategy.
4. Find base URL env vars: search for `VITE_*_HOST`, `REACT_APP_*_HOST`, or similar in `.env*`.

Emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : msw-mocking
Timestamp   : <ISO-8601 date>

### Project
- Type            : React SPA | Vue SPA
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <version or "unknown">
- Framework       : <React | Vue> <version>
- UI library      : <MUI | none>
- Build tool      : <Vite | CRA>
- Test runner     : <vitest | jest | none>
- Linter          : <eslint | none>

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
- vite.config.js/ts (if present)
- .env* (if present)

### Gaps / unknowns
- <Vite proxy in use: yes | no | unknown>
- <Base URL env var found: <VAR_NAME> | none>
***END CONTEXT BLOCK***
```

---

## Determine the usage context first

Before doing anything else, ask (or infer from context) whether MSW is needed for:

- **Browser dev** — mocking the API while running `npm run dev` so the app works without a real backend
- **Tests** — intercepting network calls inside Vitest/Jest unit/component tests

The setup is different for each. Both can coexist; they share `handlers.js` but need different wiring files.

---

## Endpoint Discovery — do this first

Before writing a single handler, analyse the codebase to find every API call and understand exactly what data each one sends and receives. Do **not** invent schemas; derive them from the code.

### Step 1 — locate all API calls

Search for network calls across the project. Common patterns to grep for:

```
fetch(          axios.get(      axios.post(      axios.patch(
api.get(        api.post(       useQuery(        useMutation(
apiClient.      repository.     /api/v1
```

Example search targets: `src/repository/`, `src/api/`, `src/services/`, `src/hooks/`, `src/store/`.

Collect every unique URL pattern, HTTP method, and any query parameters or path variables used.

### Step 2 — find where each endpoint is consumed

For each URL discovered, find the call site(s) and trace how the response is used:

- Which component or hook receives the data?
- Which fields are destructured, accessed, or rendered?
- Are there conditional branches that depend on specific field values?

### Step 3 — find what is sent for mutations

For POST / PATCH / PUT calls, check the request body shape and what the caller does with the response.

### Step 4 — build the schema inventory

Produce a table before writing any code:

| Endpoint            | Method | Response fields (types)                                         | Edge-case values              |
| ------------------- | ------ | --------------------------------------------------------------- | ----------------------------- |
| `/api/v1/me`        | GET    | `id: string, name: string, team: string\|null, active: boolean` | `team: null`, `active: false` |
| `/api/v1/user/team` | PATCH  | `{ team: string }`                                              | —                             |

---

## Installation

```bash
npm i msw --save-dev
```

### Browser dev — also run:

```bash
npx msw init public/ --save
```

This copies `mockServiceWorker.js` into `public/` and records the directory in `package.json` under `"msw": { "workerDirectory": "public" }`. The service worker file **must** be served from the same origin as the app. `--save` ensures it is re-copied automatically on MSW upgrades.

---

## File structure

```
src/mocks/
  handlers.js   # happy-path handlers — shared by browser and test setups
  browser.js    # browser dev: setupWorker (import in app entry point)
  server.js     # test runner: setupServer (import in setupTests.js)
```

---

## handlers.js — happy paths only

Define the successful baseline for every endpoint.

**If the project uses a Vite proxy** (e.g. `proxy: { '/api/v1': 'http://localhost:4000' }`), use relative paths.

**If the project constructs absolute URLs from environment variables** (e.g. `` `${process.env.VITE_BACKEND_HOST}/v1/projects` ``), use those same env vars in your handlers. This keeps interceptor URLs in sync with what Axios actually sends — no wildcards needed, no risk of accidentally matching the wrong host in a multi-backend project.

```js
// src/mocks/handlers.js
import { http, HttpResponse } from 'msw';

// Mirror the env vars the service files use. Fall back to localhost defaults
// so the setup works without a .env file (CI, fresh clones, etc.).
const API = import.meta.env.VITE_BACKEND_HOST ?? 'http://localhost:4000';

export const handlers = [
	http.get(`${API}/api/v1/me`, () =>
		HttpResponse.json({ id: '1', name: 'Ana', team: 'engineering', active: true })
	),
	http.patch(`${API}/api/v1/user/team`, () => HttpResponse.json({ team: 'engineering' })),
];
```

- Never put error/edge-case responses here — use `server.use()` overrides in individual tests
- Do not include query parameters in the path predicate
- When a project has **multiple backends** (different env vars), declare a `const` for each one at the top and use them in the appropriate handler groups
- Register more-specific paths **before** shorter ones that share a prefix (e.g. `/survey/schedule` before `/survey`, `/project/:id` before `/project`) to prevent shadowing

---

## browser.js — browser dev setup

```js
// src/mocks/browser.js
import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);
```

### Wiring into the app entry point

Start the worker **before** the app renders. Use a dynamic import so the mock code is never bundled into production. Gate on `import.meta.env.DEV` (Vite) rather than `process.env.NODE_ENV` — the latter is not always defined in Vite projects.

```js
// src/main.jsx (or index.jsx)
async function enableMocking() {
	if (import.meta.env.DEV) {
		const { worker } = await import('./mocks/browser');
		// onUnhandledRequest: 'bypass' lets real requests through without warnings
		return worker.start({ onUnhandledRequest: 'bypass' });
	}
}

enableMocking().then(() => {
	ReactDOM.createRoot(document.getElementById('root')).render(<App />);
});
```

If the app entry point has async setup that must complete before `ReactDOM.createRoot` (e.g. MSAL `initialize()`), wrap both inside the `enableMocking().then(...)` callback so the worker is always running before any component mounts and fires a request.

---

## server.js — test runner setup

```js
// src/mocks/server.js
import { setupServer } from 'msw/node';
import { handlers } from './handlers';

export const server = setupServer(...handlers);
```

### Test setup file (setupTests.js)

```js
import { server } from './mocks/server';

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers()); // resets per-test overrides
afterAll(() => server.close());
```

Configure Vitest to auto-load it via `setupFiles` in `vite.config.js`:

```js
test: {
  environment: 'jsdom',
  setupFiles: ['./src/setupTests.js'],
  globals: true,
},
```

---

## Test patterns

### Happy path — no extra setup needed

```js
it('renders the user name', async () => {
	render(<App />);
	expect(await screen.findByText('Ana')).toBeVisible();
});
```

### Error/edge case — override for one test

```js
it('shows error state when /me fails', async () => {
	server.use(http.get(`${API}/api/v1/me`, () => new HttpResponse(null, { status: 401 })));
	render(<App />);
	expect(await screen.findByRole('alert')).toBeVisible();
});
```

### One-time override

```js
server.use(
	http.get(`${API}/api/v1/me`, () => new HttpResponse(null, { status: 500 }), { once: true })
);
```

---

## Rules

- Happy paths live in `handlers.js`; error/edge cases go in `server.use()` inside tests
- **Browser dev requires `npx msw init public/ --save`** — without the service worker file in `public/`, the worker silently fails to register and all requests go through unintercepted
- **Start the worker before the app renders** — if any component fires a request on mount before `worker.start()` resolves, that request bypasses MSW
- Gate the worker start on `import.meta.env.DEV`, not `process.env.NODE_ENV` — Vite does not guarantee `process.env.NODE_ENV` is defined at runtime
- Use `onUnhandledRequest: 'bypass'` to silently pass through any requests not covered by a handler (e.g. CDN assets, auth endpoints)
- **Never use wildcard patterns** (`*/v1/projects`) when the project uses env-var base URLs — use the same env var so the interceptor matches precisely; wildcards can silently match the wrong backend in a multi-backend project
- Don't mock Axios; MSW intercepts at the network level so `src/services/` is exercised as-is
- Always call `server.resetHandlers()` in `afterEach` — never let overrides leak between tests
- Use `{ once: true }` when you need an override to fire exactly once then fall back to the baseline

---

## Handoff

After completing MSW setup, emit:

```
***HANDOFF BLOCK***
Skill/Agent : msw-mocking
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Created src/mocks/handlers.js with <N> endpoint handlers
- Created src/mocks/browser.js (browser dev): <yes | no>
- Created src/mocks/server.js (test runner): <yes | no>
- Wired into entry point: <yes | no>
- Wired into test setup: <yes | no>

### Artifacts produced
| File | Change |
|------|--------|
| src/mocks/handlers.js | created — <N> handlers |
| src/mocks/browser.js  | created / skipped |
| src/mocks/server.js   | created / skipped |
| src/main.jsx          | modified — added worker start |
| src/setupTests.js     | modified — added server lifecycle |

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
MSW 2.x is installed and configured. Handlers are in src/mocks/handlers.js.
Base URL strategy: <env-var VITE_BACKEND_HOST | proxy relative paths>.
Test runner: <vitest | jest>. Build tool: <Vite | CRA>.
***END HANDOFF BLOCK***
```
