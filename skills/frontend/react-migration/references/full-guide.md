---
name: react-migration
description: Migrates React applications to React 18 or 19. Handles breaking changes, deprecated APIs, codemods, TypeScript type updates, and testing configuration. Use when upgrading a React app from an older version, or when asked to migrate to React 18 or React 19.
version: "1.1.0"
category: frontend
---

# React Migration Skill

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

Guides step-by-step migration of React apps to v18 or v19. Always determine the current React version first before proceeding.

## Detect current version

```bash
node -e "console.log(require('./node_modules/react/package.json').version)"
```

After detecting the current version and before starting the migration steps, emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : react-migration
Timestamp   : <ISO-8601 date>

### Project
- Type            : React SPA
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <version>
- Framework       : React <current version>
- UI library      : <MUI | Chakra | none>
- Build tool      : <Vite | CRA | webpack>
- Test runner     : <vitest | jest | none>
- Linter          : <eslint | biome | none>

### Migration context
- Current version : React <vX>
- Target version  : React <18 | 19>
- Migration hops  : <single | React X → 18 → 19>

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no | unknown>
- Storybook       : <yes | no>

### Files read
- package.json
- node_modules/react/package.json

### Gaps / unknowns
- Incompatible peer deps found: <list or "none — scan required in Step 1">
***END CONTEXT BLOCK***
```

---

## Migration path

- **< 18** → migrate to 18 first, then to 19
- **18.x** → migrate directly to 19 (upgrade to 18.3 as intermediate step recommended)
- **18.3** → migrate directly to 19

---

## Migrating to React 18

### 1. Audit peer dependencies before upgrading

**Do this before touching `react` or `react-dom` versions.** Many packages declare peer deps that cap at React 16 or 17 and npm will hard-fail on install with React 18 unless the conflicts are resolved first. `--legacy-peer-deps` is not an acceptable fix — it silently installs incompatible versions that can cause subtle runtime failures.

> **Critical ordering:** Run the scan while the **old** node_modules are still installed (before updating `package.json`). The scan reads from `node_modules/` — if you update `package.json` first and `npm install` fails due to peer dep conflicts, you won't have node_modules to scan.

#### Scan for incompatible peer deps

```bash
node -e "
const fs = require('fs');
const pkg = JSON.parse(fs.readFileSync('./package.json', 'utf8'));
const all = { ...pkg.dependencies, ...pkg.devDependencies };
for (const name of Object.keys(all)) {
  try {
    const p = JSON.parse(fs.readFileSync('./node_modules/' + name + '/package.json', 'utf8'));
    const peer = p.peerDependencies && p.peerDependencies.react;
    if (peer) console.log(name + '@' + p.version + '  peerDeps.react=' + peer);
  } catch(e) {}
}
"
```

Review every line. Any package whose `peerDeps.react` range **excludes `^18`** (e.g. `^16.0.0`, `0.14.x || ^15.0.0 || ^16.0.0`, `^16.8.0 || ^17.0.0`) must be resolved before proceeding.

#### Decision tree for each flagged package

```
Does a newer version of this package support React 18?
├── YES → Upgrade it. Check changelog for breaking changes first.
└── NO  → Is the package actively maintained?
          ├── YES (but no React 18 release yet) → Pin with --legacy-peer-deps ONLY as a temporary measure;
          │                                        open a tracking issue; do not ship to production.
          └── NO (abandoned) → Find and migrate to a maintained replacement.
                               Flag as BLOCKED until resolved.
```

#### Known packages that require upgrading for React 18

| Package | Incompatible version | React 18-compatible version | Notes |
|---|---|---|---|
| `react-select` | `^3.x`, `^4.x` | `^5.0.0` | v5 drops the Emotion peer dep (now bundled). Core props (`value`, `onChange`, `options`, `isDisabled`, `className`) are unchanged — no code edits needed for standard usage. Complex custom styles using the `styles` prop or emotion internals may need updating. |
| `react-tooltip` | `^4.x` | `^5.0.0` or `^6.0.0` | v5 rewrote the API; check your usage |
| `react-router-dom` | `^5.x` | `^6.0.0` | v6 has significant API changes |
| `react-notification-system` | `0.4.0` | **No compatible version — abandoned** | Replace with `react-toastify`, `notistack`, or `react-hot-toast` |
| `react-dnd` | `^11.x` | `^16.0.0` | |
| `styled-components` | `^5.x` | `^6.0.0` | |

> This table covers known cases. Always run the scan above — do not assume the table is exhaustive.

#### Upgrading packages

For each package with a compatible newer version, upgrade it:

```bash
npm install <package>@<react18-compatible-version>
```

Read the package's changelog/migration guide before bumping a major version — API changes will require source code updates.

#### Replacing abandoned packages

If a package has no React 18-compatible version and is abandoned, you must replace it. Steps:

1. Search npm for a maintained alternative (check weekly downloads, last publish date, open issues).
2. Update `package.json` to remove the old package and add the replacement.
3. Update all import paths and usage in source code.
4. Do not proceed to the React upgrade until all replacements are done.

**Shim adapter pattern for imperative-API libraries**

If the abandoned package exposed an imperative ref-based API (e.g. notification systems, toast managers, modals) that is passed as a prop through multiple component layers, writing a thin shim object minimises the blast radius — child components keep calling the old API and need zero changes:

```ts
// utils/notificationShim.ts — wraps react-toastify behind the old addNotification interface
import { toast, ToastOptions } from 'react-toastify';

const notificationShim = {
    addNotification: ({ message, level = 'info', autoDismiss }: {
        message: string; level?: string; autoDismiss?: number;
    }) => {
        const options: ToastOptions = autoDismiss ? { autoClose: autoDismiss * 1000 } : {};
        if (level === 'success') toast.success(message, options);
        else if (level === 'warning') toast.warning(message, options);
        else if (level === 'error') toast.error(message, options);
        else toast.info(message, options);
    }
};
export default notificationShim;
```

Then in the root component: replace the ref-based `<NotificationSystem ref={c => this.notification = c} />` with:
1. Assign `this.notification = notificationShim` in the constructor (no ref needed).
2. Replace the rendered notification component with `<ToastContainer />` (from react-toastify).

All child components that receive `notification` as a prop and call `.addNotification(...)` require zero changes.

**Do not start the React 18 install until all peer dep conflicts are resolved.**

---

### 2. Install packages

```bash
npm install react react-dom
npm install --save-dev @types/react @types/react-dom  # if using TypeScript
```

### 3. Replace root API (breaking)

```js
// Before
import { render } from 'react-dom';
render(<App />, document.getElementById('root'));

// After
import { createRoot } from 'react-dom/client';
const root = createRoot(document.getElementById('root'));
root.render(<App />);
```

Replace `unmountComponentAtNode(container)` → `root.unmount()`.

### 4. Replace hydration API (SSR only)

```js
// Before
import { hydrate } from 'react-dom';
hydrate(<App />, container);

// After
import { hydrateRoot } from 'react-dom/client';
hydrateRoot(container, <App />);
```

### 5. Replace deprecated server APIs (SSR only)

- `renderToNodeStream` → `renderToPipeableStream` (Node) or `renderToReadableStream` (edge)
- `renderToString` / `renderToStaticMarkup` still work but with limited Suspense support

### 6. TypeScript: add explicit `children` prop

```ts
// Before — implicit children were allowed
interface MyProps { color: string }

// After — must be explicit
interface MyProps {
  color: string;
  children?: React.ReactNode;
}
```

Run automated codemod:
```bash
npx types-react-codemod preset-18 ./src
```

### 7. Automatic batching awareness

React 18 batches all state updates (including inside `setTimeout`, promises, native events). If any code relied on separate renders per update, use `flushSync` to opt out:

```js
import { flushSync } from 'react-dom';
flushSync(() => setCount(c => c + 1));
// DOM updated here
flushSync(() => setFlag(f => !f));
```

### 8. Strict Mode double-invoke

React 18 Strict Mode unmounts and remounts every component in development to surface effect cleanup bugs. Effects must be idempotent. Fix any cleanup issues surfaced — do not remove `<StrictMode>`.

### 9. Configure test environment

```js
// test setup file (e.g. jest.setup.js)
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
```

### 10. Verify

```bash
npm run build
npm test
```

Fix any remaining warnings about `ReactDOM.render`, `hydrate`, or `unmountComponentAtNode`.

---

## Migrating to React 19

### 0. Intermediate step (recommended)

Upgrade to React 18.3 first — it adds deprecation warnings for everything removed in 19:

```bash
npm install react@18.3 react-dom@18.3
```

Fix all warnings, then proceed.

### 1. Run the automated codemod

```bash
npx codemod@latest react/19/migration-recipe
```

This handles: `ReactDOM.render` → `createRoot`, string refs, `act` import, `useFormState`, `propTypes` → TypeScript.

For TypeScript type changes:
```bash
npx types-react-codemod@latest preset-19 ./src
```

### 2. Install React 19

```bash
npm install --save-exact react@^19.0.0 react-dom@^19.0.0
npm install --save-exact @types/react@^19.0.0 @types/react-dom@^19.0.0  # TypeScript
```

Ensure the new JSX transform is enabled in your bundler/tsconfig (`"jsx": "react-jsx"`).

### 3. Remove propTypes and defaultProps (function components)

```js
// Before
Heading.propTypes = { text: PropTypes.string };
Heading.defaultProps = { text: 'Hello' };

// After — use TypeScript or ES6 default params
function Heading({ text = 'Hello' }: { text?: string }) { ... }
```

### 4. Remove legacy Context API

Replace `contextTypes` / `getChildContext` with `React.createContext` + `contextType`.

### 5. Remove string refs

```js
// Before
this.refs.input.focus();
render() { return <input ref="input" />; }

// After
this.input.focus();
render() { return <input ref={el => this.input = el} />; }
```

Codemod: `npx codemod@latest react/19/replace-string-ref`

### 6. Migrate `act` import

```diff
- import { act } from 'react-dom/test-utils';
+ import { act } from 'react';
```

Codemod: `npx codemod@latest react/19/replace-act-import`

### 7. Replace `ReactDOM.findDOMNode`

```js
// Before
const input = findDOMNode(this);

// After
const ref = useRef(null);
// use ref.current
```

### 8. Update error handling on root (if needed)

If production error tracking relied on React re-throwing errors, add explicit handlers:

```js
const root = createRoot(container, {
  onUncaughtError: (error, errorInfo) => { /* report */ },
  onCaughtError: (error, errorInfo) => { /* report */ },
});
```

### 9. TypeScript-specific fixes

- `useRef` now requires an argument: `useRef(null)` or `useRef(undefined)`
- Implicit ref callback returns are rejected — use explicit block bodies:
  ```diff
  - <div ref={el => (instance = el)} />
  + <div ref={el => { instance = el; }} />
  ```
- `useReducer`: remove explicit type parameter, annotate reducer params instead:
  ```diff
  - useReducer<React.Reducer<State, Action>>(reducer)
  + useReducer(reducer)  // or annotate: (state: State, action: Action) => state
  ```
- JSX namespace augmentation must be scoped:
  ```ts
  declare module "react" {
    namespace JSX {
      interface IntrinsicElements { "my-element": { ... } }
    }
  }
  ```

### 10. Migrate tests off react-test-renderer

`react-test-renderer` is deprecated. Migrate to `@testing-library/react`.

### 11. Verify

```bash
npm run build
npm test
```

---

## Common pitfalls

| Issue | Fix |
|---|---|
| App broken after upgrade | Check if `<StrictMode>` is exposing effect cleanup bugs |
| Tests fail with `act(...)` warning | Set `globalThis.IS_REACT_ACT_ENVIRONMENT = true` in setup |
| TypeScript errors on `children` | Add `children?: React.ReactNode` explicitly to prop interfaces |
| `ref` callback TS error | Use block body `ref={el => { ... }}` not implicit return |
| Peer dep conflicts on v18/v19 | Do **not** use `--legacy-peer-deps` as a fix. Run the peer dep audit in step 1 and upgrade or replace each incompatible package. `--legacy-peer-deps` silently installs incompatible versions and causes hard-to-debug runtime failures. |

---

## Handoff

After completing the migration, emit:

```
***HANDOFF BLOCK***
Skill/Agent : react-migration
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated React from <vX> to <v18 | v19>
- Root API updated (ReactDOM.render → createRoot): <yes | no | n/a>
- TypeScript children props updated: <yes | no | n/a>
- Codemods run: <list or "none">
- Peer deps resolved: <list or "none">
- Test environment updated: <yes | no | n/a>

### Artifacts produced
| File | Change |
|------|--------|
| package.json | modified — updated react, react-dom versions |
| src/index.(tsx|jsx) | modified — updated root API |
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
React migrated to <v18 | v19>. TypeScript: <yes|no>. Build tool: <Vite | CRA>.
Test runner: <vitest | jest>. Any blocked packages or remaining manual steps are listed above.
***END HANDOFF BLOCK***
```
