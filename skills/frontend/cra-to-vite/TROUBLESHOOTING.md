# Troubleshooting

## Contents

- JSX in .js files (JavaScript-only projects)
- CommonJS module.exports in source files
- Module not found errors (absolute imports)
- process is not defined
- SVG import errors
- CSS/asset import errors
- TypeScript moduleResolution errors
- Port differences
- Build output path differences
- Dev server starts but app is blank

---

## JSX in .js files (JavaScript-only projects)

**Symptom:** `Failed to parse source for import analysis because the content contains invalid JS syntax. If you are using JSX, make sure to name the file with the .jsx or .tsx extension.`

Or on **Vite 8** (Rolldown/OXC bundler):

```
[builtin:vite-transform] Error: Unexpected JSX expression
Help: JSX syntax is disabled and should be enabled via the parser options
```

**Cause:** Vite only processes JSX in `.jsx`/`.tsx` files by default. CRA processed JSX in `.js` files automatically.

### Vite 5/6 fix (esbuild)

Add esbuild options to `vite.config.js` to treat all `.js` files in `src/` as JSX:

```js
export default defineConfig({
  plugins: [react({ include: /\.(jsx|js|tsx|ts)$/ })],
  esbuild: {
    loader: 'jsx',
    include: /src\/.*\.jsx?$/,
    exclude: [],
  },
  optimizeDeps: {
    esbuildOptions: {
      loader: {
        '.js': 'jsx',
      },
    },
  },
});
```

### Vite 8 fix (Rolldown/OXC)

**None of the config options work reliably in Vite 8.** The following were tried and all fail:

- `react({ include: /\.(jsx|js|tsx|ts)$/ })` — plugin `include` alone is insufficient
- `oxc: { include: /src\/.*\.jsx?$/ }` — does not affect Rolldown's parser
- `build.rolldownOptions.moduleTypes: { '.js': 'jsx' }` — not applied to source files
- `build.rolldownOptions.transform.jsx` — no effect on parser

**The correct fix for Vite 8: rename all `.js` files that contain JSX to `.jsx`.**

```bash
# Find all .js files with JSX
grep -rl '<[A-Za-z/]' src --include="*.js"

# Rename each one (example)
mv src/App.js src/App.jsx
```

Then update `index.html` if `src/index.js` was renamed to `src/index.jsx`:

```html
<script type="module" src="/src/index.jsx"></script>
```

Vite's resolver handles bare extension-less imports (`import App from './App'`) automatically, so no import fixes are needed for relative imports. Only explicit `.js` extension imports need updating.

> **Note for Vite 5/6 projects:** The `esbuild` config approach avoids renaming files. Prefer it if staying on Vite 5/6. For Vite 8+, renaming to `.jsx` is the only reliable approach.

---

## CommonJS module.exports in source files

**Symptom:** `"default" is not exported by "src/utils/something.js", imported by "src/routes/SomeRoute.js"`

**Cause:** Vite uses native ES modules. Any source file that uses `module.exports` or `require()` is a CommonJS module and will fail Rollup's import analysis at build time.

**Fix:** Convert all `module.exports` in `src/` to ES module `export default`:

```js
// Before (CJS)
module.exports = (arg) => {
  return doSomething(arg);
};

// After (ESM)
const myFunction = (arg) => {
  return doSomething(arg);
};

export default myFunction;
```

And named exports:

```js
// Before (CJS)
module.exports = { foo, bar };

// After (ESM)
export { foo, bar };
```

**Find all CJS modules in src:**

```bash
grep -r "module\.exports" src/
grep -r "require(" src/
```

> Note: `require()` in `vite.config.js` is fine — that file runs in Node.js, not the browser bundle.

---

## Module not found errors (absolute imports)

**Symptom:** `Rollup failed to resolve import "components/Layout" from "src/screens/SignIn.js"`

**Cause:** CRA configured webpack to resolve bare directory names relative to `src/`. Vite does not do this by default.

**Fix — Option A (per-directory aliases):** Add an alias for each top-level directory used as a bare import:

```js
import path from 'path';

resolve: {
  alias: {
    components: path.resolve(__dirname, './src/components'),
    contexts:   path.resolve(__dirname, './src/contexts'),
    constants:  path.resolve(__dirname, './src/constants'),
    images:     path.resolve(__dirname, './src/images'),
    mocks:      path.resolve(__dirname, './src/mocks'),
    repository: path.resolve(__dirname, './src/repository'),
    routes:     path.resolve(__dirname, './src/routes'),
    screens:    path.resolve(__dirname, './src/screens'),
    utils:      path.resolve(__dirname, './src/utils'),
  },
},
```

**Fix — Option B (single `src` alias):** Add one alias that covers all imports prefixed with `src/`:

```js
resolve: {
  alias: {
    src: path.resolve(__dirname, './src'),
  },
},
```

Then update all bare imports to use the `src/` prefix:

```js
// Before
import Layout from 'components/Layout';

// After
import Layout from 'src/components/Layout';
```

> Option A avoids touching any import statements. Option B is cleaner long-term.

---

## process is not defined

**Symptom:** Runtime error `process is not defined`

**Cause:** Some libraries or code use `process.env.*` which CRA polyfilled; Vite does not.

**Fix option 1:** Replace all `process.env.*` usages with `import.meta.env.*` (preferred).

**Fix option 2:** Add a define shim in `vite.config.js` for third-party libraries:

```js
export default defineConfig({
  define: {
    'process.env': {},
  },
});
```

---

## SVG import errors

**Symptom:** `Failed to parse source for import analysis` when importing SVG as React component

**Cause:** CRA used `@svgr/webpack`. Vite does not support this by default.

**Fix:** Install the Vite SVGR plugin:

```bash
npm install -D vite-plugin-svgr
```

Update `vite.config.js`:

```js
import svgr from 'vite-plugin-svgr';

export default defineConfig({
  plugins: [react(), svgr()],
});
```

SVG imports as React components then work as before:

```jsx
import { ReactComponent as Logo } from './logo.svg';
```

---

## CSS import errors with ~ prefix

**Symptom:** CSS fails to compile with `@import '~some-package/...'`

**Fix:** Remove the `~` prefix — Vite resolves node_modules without it:

```css
/* Old CRA */
@import '~some-package/styles.css';

/* Vite */
@import 'some-package/styles.css';
```

---

## TypeScript moduleResolution errors

**Symptom:** TypeScript errors after updating `tsconfig.json`

**Fix:** `"moduleResolution": "Bundler"` requires TypeScript 5+. If on an older TS version, use:

```json
"moduleResolution": "node16",
"allowSyntheticDefaultImports": true,
"esModuleInterop": true
```

---

## Port differences

CRA defaults to port `3000`. Vite defaults to port `5173`.

To keep port `3000`, add to `vite.config.js`:

```js
server: {
  port: 3000,
},
```

---

## Build output path differences

CRA outputs to `build/`. Vite outputs to `dist/`.

To keep the `build/` output directory, add to `vite.config.js`:

```js
build: {
  outDir: 'build',
},
```

Alternatively, update CI/CD pipelines and deployment scripts to reference `dist/` instead.

---

## Dev server starts but app is blank

**Cause:** Missing or incorrect entry point script tag in `index.html`.

**Fix:** Ensure `index.html` at the project root contains:

```html
<script type="module" src="/src/index.jsx"></script>
```

(Use `/src/index.tsx` for TypeScript projects.)
