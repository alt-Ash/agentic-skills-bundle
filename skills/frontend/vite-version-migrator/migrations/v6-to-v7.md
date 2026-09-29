# Vite v6 → v7 Migration Guide

> Reference: https://v7.vite.dev/guide/migration.html

## Contents

- Node.js requirement
- Default browser target change
- Removed Sass legacy API
- Removed deprecated features
- Advanced / plugin-author changes

---

## Node.js requirement

Vite 7 requires **Node.js 20.19+ or 22.12+**. Node.js 18 is no longer supported.

Update `.nvmrc`, `package.json` engines, and CI configs:

```bash
# .nvmrc
20

# package.json
"engines": { "node": ">=20.19.0" }
```

---

## Default browser target change

`build.target` default (`'baseline-widely-available'`, introduced in v6) now
targets newer browsers:

| Browser | v6 | v7 |
|---|---|---|
| Chrome | 87 | 107 |
| Edge | 88 | 107 |
| Firefox | 78 | 104 |
| Safari | 14.0 | 16.0 |

Also: the old `'modules'` target string is removed. Use
`'baseline-widely-available'` or an explicit list instead.

```js
// If you relied on the old 'modules' default, set an explicit target:
build: {
  target: ['es2020', 'edge88', 'firefox78', 'chrome87', 'safari14'],
}
```

Check your app's browser support requirements and adjust if the new defaults are
too aggressive.

---

## Removed Sass legacy API

The Sass legacy API that was deprecated in Vite 5.4 and defaulted to modern in
Vite 6 is now fully removed.

Remove the `api` option if present:

```js
// Remove from vite.config:
css: {
  preprocessorOptions: {
    sass: { api: 'legacy' },   // remove this line
    scss: { api: 'legacy' },   // remove this line
  },
},
```

Migrate any Sass code that uses the legacy JS API. See the
[Sass documentation](https://sass-lang.com/documentation/breaking-changes/legacy-js-api/).

---

## Removed deprecated features

| Removed | Replacement |
|---|---|
| `splitVendorChunkPlugin` | Use `build.rollupOptions.output.manualChunks` |
| `transformIndexHtml` hook-level `enforce` / `transform` | Use `order` instead of `enforce`, `handler` instead of `transform` |

Grep for usages:

```bash
grep -rn "splitVendorChunkPlugin\|transformIndexHtml" . --include="*.js" --include="*.ts"
```

### `splitVendorChunkPlugin` replacement

```js
// Before
import { splitVendorChunkPlugin } from 'vite'
plugins: [splitVendorChunkPlugin()]

// After — manual chunks
build: {
  rollupOptions: {
    output: {
      manualChunks(id) {
        if (id.includes('node_modules')) {
          return 'vendor'
        }
      },
    },
  },
},
```

### `transformIndexHtml` hook object form

```js
// Before
{
  name: 'my-plugin',
  transformIndexHtml: {
    enforce: 'pre',
    transform(html) { return html },
  },
}

// After
{
  name: 'my-plugin',
  transformIndexHtml: {
    order: 'pre',
    handler(html) { return html },
  },
}
```

---

## Advanced / plugin-author changes

| Change | Action |
|---|---|
| `legacy.proxySsrExternalModules` removed (was no-op since v6) | Remove from config |
| Removed unused type-only properties (`ModuleRunnerOptions.root`, etc.) | Update TypeScript code referencing these |
| Deprecated Env API properties removed | Remove references to deprecated env properties |
| `HMRBroadcaster`, `HMRBroadcasterClient`, `ServerHMRChannel`, `HMRChannel` removed | Migrate to the new Environment API equivalents |
| `optimizeDeps.entries` always treated as globs | Literal paths no longer accepted; convert to glob patterns |
| Some middlewares now applied before `configureServer` / `configurePreviewServer` hooks | If a route must not have CORS headers, explicitly remove them |

---

## Verification

```bash
npm install
npm run dev
npm run build
```

---

## References

- [Vite 7 migration guide](https://v7.vite.dev/guide/migration.html)
- [Sass modern API migration](https://sass-lang.com/documentation/breaking-changes/legacy-js-api/)
