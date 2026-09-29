---
name: cra-to-vite
description: Migrates a Create React App (react-scripts) project to Vite and migrates tests from Jest to Vitest. Handles dependency swap, config creation, index.html restructuring, env variable renaming, TypeScript setup, script updates, and Jest-to-Vitest migration. Use when asked to migrate from CRA to Vite, replace react-scripts with vite, modernize the build tooling of a React app, or migrate Jest tests to Vitest.
version: "1.1.0"
category: frontend
---

# CRA to Vite Migration

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## Prerequisites

**Node.js ≥ 20 is required.** Vite 6 and Vitest 3 both require Node 18+ minimum, but Node 20 is the recommended baseline — it is the current LTS, has stricter ESM resolution that surfaces hidden import errors early, and is required by the latest versions of many testing and build tools.

If the project is on an older version, upgrade first:

```bash
# Using nvm:
nvm install 20
nvm use 20
```

Update `.nvmrc` to pin the project to Node 20:

```
20
```

## Migration checklist

Copy and track progress:

```
Migration Progress:
- [ ] Step 1: Audit the project (including require() calls and file casing)
- [ ] Step 2: Upgrade Node.js to 20 and audit package compatibility
- [ ] Step 3: Swap dependencies (remove webpack/CRA, install Vite)
- [ ] Step 4: Convert require() calls to ES imports
- [ ] Step 5: Create vite.config
- [ ] Step 6: Restructure index.html
- [ ] Step 7: Update env variables
- [ ] Step 8: Update package.json scripts
- [ ] Step 9: TypeScript setup (if applicable)
- [ ] Step 10: Migrate tests from Jest to Vitest
- [ ] Step 11: Remove CRA/webpack artifacts
- [ ] Step 12: Verify the build
- [ ] Step 13: Update .gitignore
- [ ] Step 14: Check for license violations
- [ ] Step 15: Update CI/CD pipelines, Dockerfiles, and Helm/Kubernetes templates
```

---

## Step 1: Audit the project

Read these files before doing anything:

- `package.json` — note: scripts, dependencies, devDependencies
- `public/index.html` — note: `%PUBLIC_URL%` usages, meta tags, scripts
- `src/index.tsx` or `src/index.jsx` or `src/index.js` — note: entry point extension
- `tsconfig.json` — if TypeScript project
- Any `.env*` files — list all `REACT_APP_*` variable names

Also check:

- **File extensions:** If `.js` files contain JSX, they **must be renamed to `.jsx`** on Vite 8 (Rolldown). Config-based workarounds do not work — see TROUBLESHOOTING.md.
- **Server-injected globals (`declare var X`):** When the project is embedded in a server-rendered host (e.g. ASP.NET MVC / Razor), the host view commonly injects runtime globals before the bundle loads — things like `baseURL`, `currentUser`, `appConfig`, etc. TypeScript files declare them with `declare var X` so the compiler accepts them, but their actual value comes from a `<script>var X = '...'</script>` block in the server-rendered HTML. When running standalone with Vite (no backend), those globals are never defined and cause `ReferenceError: X is not defined` at runtime. Scan for all such declarations:

  ```bash
  grep -rn "declare var" Scripts --include="*.tsx" --include="*.ts" | grep -v "bootbox\|require"
  ```

  For each one, add a dev-only definition in `index.html` **before** the module script tag:

  ```html
  <!--
    These globals are injected by the backend host in production.
    Stub values here keep the app renderable when running standalone.
    API calls will still fail without a running backend.
  -->
  <script>
    var baseURL = '';
    // var currentUser = { name: 'Dev User' };
  </script>
  <script type="module" src="/Scripts/TimesheetEntry/index.tsx"></script>
  ```

  > Use `''` (empty string) or a sensible stub. Do **not** use `undefined` — template literals like `` `${baseURL}/path` `` will render as `"undefined/path"` which is harder to debug than an empty string.

- **File casing:** Webpack on macOS is case-insensitive and silently accepts `import Loading from './Loading'` even if the file is named `loading.tsx`. Vite on Linux/CI is case-sensitive and will hard-fail. Run the following to catch mismatches before they surface in CI:
  ```bash
  # Find imports that may have casing mismatches (compare import path vs actual filename)
  grep -r "^import" Scripts --include="*.tsx" --include="*.ts" | grep "from '\./[A-Z]"
  ```
  Fix any mismatch by updating the import to match the exact on-disk filename.
- **CommonJS `require()` calls in source files:** Webpack polyfills `require()` transparently, so projects often use `var X = require('pkg')` inside `.ts`/`.tsx` files alongside ES `import` statements. Vite is pure ESM — `require` does not exist at runtime in the browser and causes `ReferenceError: require is not defined`. Scan for all occurrences:

  ```bash
  grep -rn "require(" Scripts --include="*.tsx" --include="*.ts"
  grep -rn "declare var require" Scripts --include="*.tsx" --include="*.ts"
  ```

  For each hit, convert to an ES `import`:

  ```ts
  // Before
  declare var require;
  var Update = require('immutability-helper');
  var Lodash  = require('lodash');

  // After
  import _update from 'immutability-helper';
  import Lodash from 'lodash';
  ```

  > **Watch out — unmasked TypeScript errors:** When `require()` returned `any`, TypeScript skipped type-checking usages. Switching to a typed import may surface pre-existing TS errors (e.g. `immutability-helper`'s strict generic types rejecting state keys not in the interface). The minimal fix that preserves the previous loose-typing behaviour:
  >
  > ```ts
  > import _update from 'immutability-helper';
  > // eslint-disable-next-line @typescript-eslint/no-explicit-any
  > const Update: (obj: any, spec: any) => any = _update;
  > ```
  >
  > Do not suppress all errors with `@ts-ignore` — use targeted casts only on the affected variable.

- **CommonJS modules:** Run `grep -r "module\.exports" src/` — any hits must be converted to ES module `export default` before the build will succeed (see [TROUBLESHOOTING.md](TROUBLESHOOTING.md)).
- **Absolute imports:** Run `grep -r "from '" src/ | grep -v '\.\/' | grep -v "node_modules"` — bare imports like `import X from 'components/X'` need resolve aliases (see Step 4).
- **Dev proxy:** A proxy is needed in **any** of these three cases — check all three, not just the first one:
  1. `package.json` has a `"proxy"` field (explicit CRA proxy).
  2. `src/setupProxy.js` exists (advanced CRA proxy).
  3. **Source code uses relative API paths** — run `grep -r "api/" src/` and look for patterns like `endpoint = 'api/v1'` or `fetch('/api/...')`. If the app hits `/api/...` relative to the dev server origin, Vite will intercept those requests and return `index.html` unless a proxy is configured. This is the most commonly missed case.

  For all three cases the fix is the same: add a `server.proxy` block in `vite.config` (see Step 4). Note the backend URL — if it is hardcoded (e.g. in a Dockerfile `ENV` instruction or `setupProxy.js`), move it into `.env` as `VITE_BACKEND_ENDPOINT` rather than hardcoding it in `vite.config`.

- **CI/CD pipelines:** Check for GitHub Actions (`.github/workflows/*.yml`), Azure Pipelines (`azure-pipelines.yml`), and any other pipeline files. Note Node version, build output folder references (`build/`), and env variable names (`REACT_APP_*`). All of these must be updated (see Step 14).
- **Dockerfiles:** Check for `FROM node:*` base image version, `COPY --from=builder /src/build` paths, and `ENV REACT_APP_*` variable declarations. All must be updated (see Step 14). Note any hardcoded backend URLs in `ENV` instructions — these are candidates for `.env` variables.
- **Helm / Kubernetes templates:** Check `templates/secrets.yaml`, `values.yaml`, and any `values-*.yaml` files for `REACT_APP_*` variable names. All must be renamed to `VITE_*` (see Step 14).

Check for test setup: if Jest is configured (via `react-scripts test`), see [JEST-TO-VITEST.md](JEST-TO-VITEST.md).

After completing this audit, emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : cra-to-vite
Timestamp   : <ISO-8601 date>

### Project
- Type            : React SPA (CRA)
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Node.js         : <current version> → 20 (target)
- Framework       : React <version>
- UI library      : <MUI | none>
- Build tool      : CRA (react-scripts <version>) → Vite (target)
- Test runner     : Jest → Vitest (if tests present)
- Linter          : <eslint | none>

### Migration context
- Current version : CRA / react-scripts <version>
- Target version  : Vite 6 + Vitest 3
- Migration hops  : single

### Infrastructure
- CI/CD           : <GitHub Actions | Azure Pipelines | none | unknown>
- Docker          : <yes | no>
- Storybook       : <yes | no>

### Files read
- package.json
- public/index.html
- src/index.(tsx|jsx|js)
- tsconfig.json (if present)
- .env* files

### Gaps / unknowns
- REACT_APP_* vars found: <list or "none">
- Server-injected globals (declare var): <list or "none">
- Dev proxy required: <yes | no | unknown>
- CommonJS require() calls found: <yes | no>
***END CONTEXT BLOCK***
```

---

## Step 2: Upgrade Node.js to 20 and audit package compatibility

### 1. Switch to Node 20

```bash
nvm install 20
nvm use 20
```

Update `.nvmrc`:

```
20
```

### 2. Check which packages declare Node engine constraints

```bash
node -e "
const pkg = require('./package.json');
const fs = require('fs');
const all = { ...pkg.dependencies, ...pkg.devDependencies };
for (const name of Object.keys(all)) {
  try {
    const p = JSON.parse(fs.readFileSync('./node_modules/' + name + '/package.json', 'utf8'));
    if (p.engines && p.engines.node) console.log(name + '@' + p.version + '  engines.node=' + p.engines.node);
  } catch(e) {}
}
"
```

For each package whose `engines.node` range excludes Node 20, follow this decision tree:

```
Is a newer version available that supports Node 20?
├── YES → Bump to that version. Check changelog for breaking changes before bumping a major.
└── NO  → Flag as BLOCKED. Do not proceed — raise with the team.
```

### 3. Known packages that need upgrading in CRA projects

The table below covers packages commonly found in CRA projects that require a version bump for Node 20. Check each one that appears in the project:

| Package                       | Min version for Node 20 | Notes                                                                                                                                                                      |
| ----------------------------- | ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@testing-library/jest-dom`   | `^6.0.0`                | The `/extend-expect` import path was removed in v6. Node 20's strict ESM resolution **hard-fails** at test collection time if the old path is used — upgrade is mandatory. |
| `@testing-library/react`      | `^13.0.0`               | v9/v10 work at runtime but are not tested against Node 20. Upgrade to v13+ (React 18) or v12 (React 17).                                                                   |
| `@testing-library/user-event` | `^14.0.0`               | v7/v8 predate modern async APIs. Upgrade to v14.                                                                                                                           |
| `axios`                       | `^1.0.0`                | v0.x has no active maintenance. Node 20 works at runtime but upgrade is recommended.                                                                                       |
| `eslint`                      | `^8.0.0`                | `^8.23+` covers Node 20. ESLint 9 requires config format changes — only upgrade if prepared.                                                                               |

> This table covers known cases. Always run the engine check in step 2 against the actual `package.json` — do not assume the table is exhaustive.

### 4. Upgrade flagged packages

```bash
npm install <package>@latest   # or pin to a specific compatible version
```

Always read the changelog before bumping a major version.

### 5. After upgrading — update `src/setupTests.js` (or `.ts`)

If the project uses `@testing-library/jest-dom`, the old import path must be updated:

```js
// Old — removed in v6, hard-fails on Node 20
import '@testing-library/jest-dom/extend-expect';

// New — works with v6+
import '@testing-library/jest-dom';
```

---

## Step 3: Swap dependencies

**If migrating from webpack (not CRA), identify all webpack-specific packages first:**

```bash
# List all webpack-related packages in the project
node -e "
const pkg = require('./package.json');
const all = { ...pkg.dependencies, ...pkg.devDependencies };
Object.keys(all).filter(k => k.includes('webpack') || ['ts-loader','babel-loader','style-loader','css-loader','file-loader','source-map-loader','clean-webpack-plugin','html-webpack-plugin','assets-webpack-plugin'].includes(k)).forEach(k => console.log(k));
"
```

Remove all webpack packages and config files:

```bash
# Remove webpack packages (adjust list to what the project actually has)
npm uninstall webpack webpack-cli webpack-dev-server webpack-merge \
  ts-loader babel-loader style-loader css-loader file-loader \
  source-map-loader clean-webpack-plugin html-webpack-plugin assets-webpack-plugin

# Delete webpack config files
rm -f webpack.config.js webpack.common.config.js webpack.production.config.js webpack.dev.config.js
```

> **Important:** Deleting webpack config files is intentional and complete — Vite replaces webpack entirely. Do not leave stale webpack configs in the repo; they cause confusion and may be picked up by tools that scan the project.

**If migrating from CRA (`react-scripts`):**

```bash
npm uninstall react-scripts
```

**Install Vite and the React plugin:**

```bash
npm install -D vite @vitejs/plugin-react
```

> If the project uses TypeScript also install: `npm install -D @types/node`

> **Note:** Latest versions of `vite` and `@vitejs/plugin-react` require Node ≥ 18. Ensure Node is upgraded before running these commands (see Prerequisites above).

---

## Step 4: Create vite.config

**JavaScript project (`.jsx` files)** — create `vite.config.js` at project root:

```js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
});
```

**JavaScript project (`.js` files containing JSX)** — **Rename them to `.jsx` first.** On Vite 8 (Rolldown), there is no config option to enable JSX parsing for `.js` files. Rename all `.js` files that contain JSX:

```bash
# Find files that need renaming
grep -rl '<[A-Za-z/]' src --include="*.js"

# Rename each one (example - do for all hits)
mv src/App.js src/App.jsx
mv src/index.js src/index.jsx
# ... etc.
```

Then update `index.html` to point to `index.jsx`:

```html
<script type="module" src="/src/index.jsx"></script>
```

Vite resolves extension-less imports automatically, so relative imports like `import App from './App'` need no changes.

After renaming, use the plain config:

```js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
});
```

> **Vite 5/6 only:** If you cannot rename files, add esbuild options — see TROUBLESHOOTING.md for the `esbuild.loader = 'jsx'` approach. This does NOT work on Vite 8.

**TypeScript project** — create `vite.config.ts` at project root:

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
});
```

**If the project uses absolute imports** (e.g. `import Button from 'components/Button'`), add resolve aliases for each top-level directory used as a bare import:

```js
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      components: path.resolve(__dirname, './src/components'),
      contexts: path.resolve(__dirname, './src/contexts'),
      // add all directories imported as bare names
    },
  },
});
```

> A single `src` alias only works if imports are written as `src/components/X`. If imports are bare (`components/X`), you need per-directory aliases. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

**If the project needs a dev proxy** — which is true if ANY of these are found: a `"proxy"` field in `package.json`, a `src/setupProxy.js` file, or relative API paths in source code (e.g. `endpoint = 'api/v1'` or `fetch('/api/...')`) — recreate it in `vite.config`. Without this, Vite intercepts every unmatched request and returns `index.html`, so API calls silently return HTML instead of JSON.

> **Critical — always configure the proxy, even without `"proxy"` or `setupProxy.js`.** The most common miss is a project that switches between a full URL (production) and a relative path (local dev). The relative path variant depends on the dev server proxying those requests — CRA did this implicitly; Vite does not.

**The proxy target must never be hardcoded.** Before writing the config:

1. Check whether a `VITE_BACKEND_ENDPOINT` (or equivalent) variable already exists in `.env`.
2. If not, add it — e.g. `VITE_BACKEND_ENDPOINT=http://127.0.0.1:<backend-port>` — taking the value from any hardcoded URL found in `package.json` (`"proxy"` field), `src/setupProxy.js`, or a Dockerfile `ENV` instruction.
3. Read it at config time using Vite's `loadEnv` utility (see below).

**`vite.config.js` — function form with `loadEnv`:**

```js
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backendUrl = env.VITE_BACKEND_ENDPOINT || 'http://127.0.0.1:4000';

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: backendUrl,
          changeOrigin: true,
        },
        '/auth': {
          target: backendUrl,
          changeOrigin: true,
        },
        // mirror every rule from setupProxy.js
      },
    },
  };
});
```

> **Why `loadEnv` and not `process.env`?** Vite only injects `VITE_*` variables into `import.meta.env` for the browser bundle. The config file runs in Node before that injection happens, so `process.env.VITE_BACKEND_ENDPOINT` is `undefined` unless you use `loadEnv`. Pass `''` as the third argument to load all variables regardless of prefix.

> **Why this matters:** Without the proxy, Vite's dev server intercepts all unmatched routes and returns `index.html`. API calls will silently receive HTML instead of JSON, causing runtime crashes that look like missing data rather than network errors.

> **Critical — use `127.0.0.1`, not `localhost`:** Node 18+ changed the default DNS resolution so that `localhost` resolves to `::1` (IPv6) instead of `127.0.0.1` (IPv4). If the backend only listens on IPv4, the proxy will get a connection refused and return a **502 Bad Gateway** — even when the backend is running and the port is correct. Store the full URL including `127.0.0.1` in the env var (e.g. `VITE_BACKEND_ENDPOINT=http://127.0.0.1:4000`), or ensure the fallback uses `127.0.0.1`.
>
> The shorthand string form (`'/api': 'http://localhost:4000'`) bypasses `changeOrigin` and does not allow specifying `127.0.0.1` easily, so always use the object form with an explicit `target`.

---

## Step 5: Restructure index.html

In Vite, `index.html` lives at the **project root**, not in `public/`.

1. Move `public/index.html` → `index.html` (project root)
2. Remove all `%PUBLIC_URL%` prefixes — Vite serves files from `public/` automatically:
   - `%PUBLIC_URL%/favicon.ico` → `/favicon.ico`
   - `%PUBLIC_URL%/manifest.json` → `/manifest.json`
3. Add the JS entry point script tag before `</body>`:

```html
<script type="module" src="/src/index.jsx"></script>
```

Use the actual extension of your entry file (`/src/index.js`, `/src/index.tsx`, etc.).

**Before / After example:**

```html
<!-- CRA (inside public/index.html) -->
<link rel="icon" href="%PUBLIC_URL%/favicon.ico" />
<div id="root"></div>

<!-- Vite (inside index.html at root) -->
<link rel="icon" href="/favicon.ico" />
<div id="root"></div>
<script type="module" src="/src/index.jsx"></script>
```

---

## Step 6: Update env variables

CRA uses `REACT_APP_*` prefix; Vite uses `VITE_*` prefix.
CRA exposes via `process.env`; Vite exposes via `import.meta.env`.

> **Critical:** You must rename variables in **both** the `.env` files **and** all source files. Missing either one causes `import.meta.env.VITE_*` to resolve as `undefined` at runtime — the browser will silently use `"undefined"` as a string in URLs and other interpolations, which is hard to debug.

**Also extract any hardcoded URLs.** CRA projects sometimes embed the backend URL directly in a Dockerfile `ENV` instruction or `setupProxy.js` rather than in `.env`. Before renaming, check:

```bash
# Hardcoded URLs in Dockerfiles:
grep -n "ENV REACT_APP_\|ENV VITE_" Dockerfile Dockerfile.* 2>/dev/null

# Hardcoded targets in setupProxy.js:
cat src/setupProxy.js 2>/dev/null
```

For any hardcoded URL found, add it to `.env` (and `.env.production` / `.env.local` as appropriate) as a `VITE_*` variable — e.g. `VITE_BACKEND_ENDPOINT=http://127.0.0.1:4000` — so `vite.config.js` can read it via `loadEnv` instead of having the URL appear twice (once in config, once in Dockerfile).

**1. In all `.env*` files**, rename every variable:

```
REACT_APP_API_URL=...  →  VITE_API_URL=...
```

Run this to do it in-place (repeat for each `.env*` file):

```bash
sed -i '' 's/REACT_APP_/VITE_/g' .env .env.local .env.production 2>/dev/null || true
```

**2. In all source files** (`.ts`, `.tsx`, `.js`, `.jsx`), replace references:

```
process.env.REACT_APP_API_URL  →  import.meta.env.VITE_API_URL
```

Use a global find-and-replace across `src/`:

```bash
# Find all usages to review:
grep -r "process\.env\.REACT_APP_" src/
grep -r "REACT_APP_" .env*
```

**3. In any non-src config files** that read env vars at build time (e.g. `cypress.config.js`, `vite.config.js`), also rename `REACT_APP_*` to `VITE_*`. These files use `process.env` (Node), not `import.meta.env`, but the variable names in `.env` must match.

Also replace any generic `process.env.NODE_ENV` references in source:

```
process.env.NODE_ENV  →  import.meta.env.MODE
```

> `import.meta.env.DEV` and `import.meta.env.PROD` are also available as booleans.

---

## Step 7: Update package.json scripts

Replace CRA scripts with Vite equivalents. Also remove the `eslintConfig: { extends: "react-app" }` field — it relied on react-scripts and is no longer valid:

```json
"scripts": {
  "start": "vite",
  "build": "vite build",
  "preview": "vite preview"
}
```

Remove from `package.json` if present:

```json
"eslintConfig": {
  "extends": "react-app"
}
```

The `test` script will be updated in Step 8.

> **Port change:** Vite's dev server runs on **port 5173** by default, not 3000. Search the project for hardcoded `localhost:3000` references and update them to `localhost:5173`. Common places:
>
> - `cypress.config.js` — `baseUrl` and any API base URL that points to the frontend
> - `.env*` files — if any var points to the frontend itself
> - README or docs
>
> To keep port 3000 instead, add `server: { port: 3000 }` to `vite.config`.

---

## Step 8: TypeScript setup (skip if JS project)

1. Create `src/vite-env.d.ts`:

```ts
/// <reference types="vite/client" />
```

2. Update `tsconfig.json` — ensure these compiler options are set:

```json
{
  "compilerOptions": {
    "target": "ESNext",
    "lib": ["DOM", "DOM.Iterable", "ESNext"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "isolatedModules": true,
    "allowImportingTsExtensions": true
  },
  "include": ["src"]
}
```

> CRA used `moduleResolution: "node"`. Vite expects `"Bundler"` or `"node16"`. If changing causes import errors, check [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

## Step 9: Migrate tests from Jest to Vitest

Follow the full guide in [JEST-TO-VITEST.md](JEST-TO-VITEST.md). Summary:

```bash
npm install -D vitest jsdom
```

> `@testing-library/jest-dom` should already be on v6+ from Step 2. Do not install an older version here.

Add `test` block to `vite.config.js` (add `/// <reference types="vitest" />` at the top):

```js
/// <reference types="vitest" />
// ... existing config ...
test: {
  globals: true,
  environment: 'jsdom',
  setupFiles: './src/setupTests.js',  // or .ts
}
```

Update `package.json`:

```json
"test": "vitest",
"test:run": "vitest run"
```

Replace `jest.*` with `vi.*` across test files (see [JEST-TO-VITEST.md](JEST-TO-VITEST.md) for full mapping).

Remove Jest dependencies:

```bash
npm uninstall jest jest-environment-jsdom babel-jest @babel/core @babel/preset-env @babel/preset-react @babel/preset-typescript ts-jest @types/jest
```

---

## Step 10: Remove CRA artifacts

Delete files that are CRA-specific and no longer needed:

- `src/react-app-env.d.ts` — replaced by `src/vite-env.d.ts`
- `jest.config.js` / `jest.config.ts` — replaced by Vitest config in `vite.config`
- `babel.config.js` — no longer needed (Vite uses esbuild, Vitest uses Vite)
- `public/index.html` — moved to project root in Step 4

```bash
rm -f src/react-app-env.d.ts jest.config.js jest.config.ts babel.config.js public/index.html
```

The `public/` folder remains — Vite serves it as static assets automatically.

---

## Step 11: Verify the build and tests

Run each command and fix any errors before proceeding to the next:

```bash
npm run start       # dev server should start on http://localhost:5173
npm run build       # production build should complete without errors
npm run preview     # preview the production build
npm run test:run    # all tests should pass
```

If build errors occur, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
If test errors occur, see [JEST-TO-VITEST.md](JEST-TO-VITEST.md).

---

## Step 12: Update .gitignore

Vite outputs production builds to `dist/` instead of CRA's `build/`. Make sure `dist` is ignored and `build` is removed if it was CRA-specific:

```bash
# Check current state:
grep -E "^/?(build|dist)" .gitignore
```

Update `.gitignore` — replace `/build` with `/dist` (or add `/dist` if not present):

```
# Vite build output
/dist
```

If the project had `/build` from CRA, remove that line. The `build/` directory name is now unused.

---

## Step 13: Check for license violations

Skip this step if the project has no `license` field in `package.json`.

Swapping CRA for Vite changes a large portion of the dependency tree. Run a license scan after all dependency changes are final to catch any newly introduced incompatible licenses.

### 1. Detect the project license

```bash
node -e "const p = require('./package.json'); console.log(p.license || 'NOT SET')"
```

If the output is `NOT SET` or `UNLICENSED`, skip this step.

### 2. Scan dependencies for violations

```bash
npx @your-org/license-checker scan --allowOnly <project-license> --ignoreRootPackageLicense
# Example for an MIT project:
npx @your-org/license-checker scan --allowOnly MIT --ignoreRootPackageLicense
```

The process exits with code 1 if any dependency uses a non-allowed license and generates a `license-error-<timestamp>.md` report listing all offending packages.

### 3. Handle violations

For each violating package:

```
Is a license-compliant version available?
├── YES → Bump to that version and re-run the scan.
└── NO  → Find a replacement package or request a legal exception.
          Flag as BLOCKED and raise with the team before proceeding.
```

Do not proceed past this step if any violation remains unresolved.

---

## Step 14: Update CI/CD pipelines, Dockerfiles, and Helm/Kubernetes templates

Three things changed that affect every deployment artifact: the Node version, the build output folder (`build/` → `dist/`), and the env variable prefix (`REACT_APP_*` → `VITE_*`). Each must be updated everywhere it appears outside `src/`.

### Dockerfiles

Every Dockerfile that builds the frontend needs three changes:

**1. Node base image — upgrade to Node 20:**

```dockerfile
# Before
FROM node:16-buster as builder

# After
FROM node:20-alpine as builder
```

> Use `-alpine` unless the project requires Debian-specific tooling. It is smaller and more secure.

**2. Build output path — `build/` → `dist/`:**

```dockerfile
# Before
COPY --from=builder /src/build /app

# After
COPY --from=builder /src/dist /app
```

**3. Env variable names — `REACT_APP_*` → `VITE_*`:**

```dockerfile
# Before
ENV REACT_APP_API_URL="https://api.example.com"

# After
ENV VITE_API_URL="https://api.example.com"
```

> **Important:** Vite bakes `VITE_*` env variables into the bundle at build time (they become `import.meta.env.VITE_*` literals). This means the value set in `ENV` during `docker build` is the value that ends up in the JS bundle. If different environments need different values, use build args (`ARG`) and pass them at build time, or use runtime injection via nginx.

Full example after migration:

```dockerfile
# Builder
FROM node:20-alpine as builder
ARG VITE_API_URL
ENV VITE_API_URL=$VITE_API_URL
WORKDIR /src
COPY . /src
RUN npm ci
RUN npm run build

# App
FROM nginxinc/nginx-unprivileged
COPY --from=builder /src/dist /app
COPY default.conf /etc/nginx/conf.d/default.conf
```

---

### GitHub Actions workflows

Check `.github/workflows/*.yml` for:

**1. Node version in `actions/setup-node`:**

```yaml
# Before
- uses: actions/setup-node@v3
  with:
    node-version: '16'

# After
- uses: actions/setup-node@v3
  with:
    node-version: '20'
```

**2. Build output folder in upload/deploy steps:**

```yaml
# Before
- uses: actions/upload-artifact@v3
  with:
    path: build/

# After
- uses: actions/upload-artifact@v3
  with:
    path: dist/
```

**3. Env variable names in `env:` blocks and step inputs:**

```yaml
# Before
env:
  REACT_APP_API_URL: ${{ secrets.API_URL }}

# After
env:
  VITE_API_URL: ${{ secrets.API_URL }}
```

**4. Cache paths — if the workflow caches `node_modules` or build output:**

```yaml
# Before
path: build

# After
path: dist
```

---

### Azure Pipelines (`azure-pipelines.yml`)

Check for the same three patterns:

**1. Node version in `NodeTool@0` task:**

```yaml
# Before
- task: NodeTool@0
  inputs:
    versionSpec: '16.x'

# After
- task: NodeTool@0
  inputs:
    versionSpec: '20.x'
```

**2. Build output folder in `CopyFiles`, `PublishBuildArtifacts`, or deploy tasks:**

```yaml
# Before
- task: CopyFiles@2
  inputs:
    SourceFolder: build

# After
- task: CopyFiles@2
  inputs:
    SourceFolder: dist
```

**3. Env variable names in pipeline variables or script steps:**

```yaml
# Before
variables:
  REACT_APP_API_URL: $(ApiUrl)

# After
variables:
  VITE_API_URL: $(ApiUrl)
```

---

### Helm / Kubernetes templates

**`templates/secrets.yaml` — rename keys:**

```yaml
# Before
data:
  REACT_APP_API_URL: <base64-encoded-value>

# After
data:
  VITE_API_URL: <base64-encoded-value>
```

**`values.yaml` and `values-*.yaml` — rename any env var keys:**

```yaml
# Before
env:
  - name: REACT_APP_API_URL
    valueFrom:
      secretKeyRef:
        name: my-secret
        key: REACT_APP_API_URL

# After
env:
  - name: VITE_API_URL
    valueFrom:
      secretKeyRef:
        name: my-secret
        key: VITE_API_URL
```

> After renaming Helm secret keys, also update any pipeline steps that create or patch the secret — the key names must match end-to-end.

**`templates/deployment.yaml`** — if env vars are referenced by name in the template itself, update those too.

---

### Quick audit command

Run this to find all remaining `REACT_APP_` and `build/` references outside `src/` and `node_modules/`:

```bash
grep -r "REACT_APP_" . --include="*.yml" --include="*.yaml" --include="Dockerfile*" --include="*.json" \
  --exclude-dir=node_modules --exclude-dir=.git

grep -r "\/build" . --include="*.yml" --include="*.yaml" --include="Dockerfile*" \
  --exclude-dir=node_modules --exclude-dir=.git \
  | grep -v "node_modules" | grep -v "#"
```

Any hits after completing this step should be reviewed and updated.

---

## Reference files

- **Testing migration**: [JEST-TO-VITEST.md](JEST-TO-VITEST.md)
- **Common errors and fixes**: [TROUBLESHOOTING.md](TROUBLESHOOTING.md)

---

## Handoff

After completing all 15 steps, emit:

```
***HANDOFF BLOCK***
Skill/Agent : cra-to-vite
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated build tool from CRA (react-scripts <vX>) to Vite 6
- Migrated tests from Jest to Vitest 3: <yes | no>
- Node.js upgraded to 20: <yes | no>
- REACT_APP_* → VITE_* env vars renamed: <yes | no | none to rename>
- CI/CD updated: <yes | no | none>
- Docker updated: <yes | no | none>
- Helm/K8s templates updated: <yes | no | none>

### Artifacts produced
| File | Change |
|------|--------|
| vite.config.(js|ts) | created |
| package.json        | modified — replaced react-scripts with vite, updated scripts |
| public/index.html   | deleted (moved to root index.html) |
| index.html          | created at project root |
| .nvmrc              | modified — set to 20 |
| <other files>       | <description> |

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
Project migrated from CRA to Vite 6. Node 20. React <version>. TypeScript: <yes|no>.
Test runner: Vitest 3. Entry point: index.html at project root.
Env vars prefix changed from REACT_APP_ to VITE_.
Vite config at: vite.config.(js|ts).
***END HANDOFF BLOCK***
```
