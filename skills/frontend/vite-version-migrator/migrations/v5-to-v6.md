# Vite v5 → v6 Migration Guide

> Reference: https://v6.vite.dev/guide/migration.html

## Contents

- Node.js requirement
- Environment API (experimental)
- Breaking config changes
- Sass modern API
- Advanced / plugin-author changes

---

## Node.js requirement

No change — Vite 6 still requires **Node.js 18 or 20+**.

---

## Environment API (experimental)

Vite 6 introduces a new experimental Environment API that required a significant
internal refactoring. Most projects are unaffected. Edge cases exist for
low-level framework and tool authors.

The experimental Vite Runtime API from v5.1 is superseded by the **Module Runner
API**. Update any usage accordingly.

---

## Breaking config changes

### `resolve.conditions` — must include defaults explicitly

If you set a custom `resolve.conditions`, `ssr.resolve.conditions`, or
`ssr.resolve.externalConditions`, you must now include the defaults manually.

```js
import { defineConfig, defaultClientConditions, defaultServerConditions } from 'vite'

export default defineConfig({
  resolve: {
    // Before: ['custom']
    conditions: ['custom', ...defaultClientConditions],
  },
  ssr: {
    resolve: {
      // Before: ['custom'] (also inherited resolve.conditions)
      conditions: ['custom', ...defaultServerConditions],
    },
  },
})
```

Grep for existing custom conditions:

```bash
grep -rn "resolve\.conditions\|ssr\.resolve\.conditions" vite.config.*
```

### `json.stringify` new default `'auto'`

`json.stringify` now defaults to `'auto'` (stringifies large JSON only).

```js
// To restore v5 behaviour (never stringify):
json: { stringify: false }
```

### `json.namedExports` no longer disabled by `json.stringify: true`

Previously, `json.stringify: true` silently disabled `json.namedExports`.
Now both are respected independently.

```js
// To restore v5 behaviour:
json: { stringify: true, namedExports: false }
```

### Sass — modern API is now the default

```js
// To keep using the legacy API temporarily:
css: {
  preprocessorOptions: {
    sass: { api: 'legacy' },
    scss: { api: 'legacy' },
  },
},
```

Note: legacy Sass API support is removed in Vite 7. Migrate to the modern API.
See the [Sass modern API migration guide](https://sass-lang.com/documentation/breaking-changes/legacy-js-api/).

### CSS output filename in library mode

The CSS output file is now named after `package.json` `"name"` (e.g.
`my-lib.css`) instead of always `style.css`.

```json
// package.json — update exports if you published style.css
{
  "exports": {
    "./style.css": "./dist/my-lib.css"
  }
}
```

To keep `style.css`:

```js
build: {
  lib: { cssFileName: 'style' },
},
```

---

## PostCSS config — TypeScript support requires `tsx` or `jiti`

`postcss-load-config` was upgraded from v4 → v6. If you use a TypeScript
PostCSS config (`postcss.config.ts`), install:

```bash
npm install -D tsx   # or jiti
```

---

## Advanced / plugin-author changes

| Change | Action |
|---|---|
| `build.cssMinify` now defaults to `'esbuild'` for SSR | No action needed unless you were disabling CSS minification for SSR |
| `server.proxy[path].bypass` called for WebSocket upgrades (`res` is `undefined`) | Guard bypass handlers: `if (!res) return` |
| Minimum terser version bumped to 5.16.0 | `npm install terser@latest` if using `build.minify: 'terser'` |
| `@rollup/plugin-commonjs` v28: `strictRequires` defaults to `true` | Review CJS entry points; may increase bundle size |
| `fast-glob` replaced by `tinyglobby` | Range braces `{01..03}` and incremental braces `{2..8..2}` no longer work in globs |
| `fs.cachedChecks` option removed | Remove from config if present |
| SSR-only module updates no longer trigger full page reload | Add `hmrReload` plugin if previous behaviour needed (see official guide) |

---

## Verification

```bash
npm install
npm run dev
npm run build
```

---

## References

- [Vite 6 migration guide](https://v6.vite.dev/guide/migration.html)
- [Sass modern API migration](https://sass-lang.com/documentation/breaking-changes/legacy-js-api/)
