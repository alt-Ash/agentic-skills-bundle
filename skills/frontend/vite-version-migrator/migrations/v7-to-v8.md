# Vite v7 → v8 Migration Guide

> Reference: https://v8.vite.dev/guide/migration.html

## Contents

- Default browser target change
- Rolldown replaces Rollup and esbuild
- Oxc replaces esbuild for JS transforms and minification
- Lightning CSS for CSS minification
- CJS interop changes
- Other breaking changes
- Removed deprecated features

---

## Default browser target change

`'baseline-widely-available'` now targets:

| Browser | v7 | v8 |
|---|---|---|
| Chrome | 107 | 111 |
| Edge | 107 | 111 |
| Firefox | 104 | 114 |
| Safari | 16.0 | 16.4 |

Check your app's browser support policy and set an explicit target if the new
defaults are too aggressive:

```js
build: {
  target: ['chrome107', 'edge107', 'firefox104', 'safari16'],
}
```

---

## Rolldown replaces Rollup and esbuild

Vite 8 uses [Rolldown](https://rolldown.rs/) for bundling and
[Oxc](https://oxc.rs/) for JS transforms/minification.

### `build.rollupOptions` → `build.rolldownOptions`

```js
// Before
build: { rollupOptions: { ... } }

// After
build: { rolldownOptions: { ... } }
```

`build.rollupOptions` still works but is deprecated and will be removed in a
future version.

### `worker.rollupOptions` → `worker.rolldownOptions`

Same rename applies for worker builds.

### `build.commonjsOptions` removed (no-op)

Remove from config if present. Rolldown handles CJS natively.

### `output.manualChunks` object form removed; function form deprecated

```js
// Object form — no longer supported, remove or convert
build: {
  rolldownOptions: {
    output: {
      // Use codeSplitting instead:
      codeSplitting: { /* see Rolldown docs */ },
    },
  },
},
```

### `build.rollupOptions.watch.chokidar` removed

```js
// Before
build: { rollupOptions: { watch: { chokidar: { ... } } } }

// After
build: { rolldownOptions: { watch: { watcher: { ... } } } }
```

### `parseAst` / `parseAstAsync` deprecated

Use `parseSync` / `parse` instead (re-exported from `vite`).

---

## Oxc for JS transforms — `esbuild` option deprecated

The `esbuild` config option is deprecated. Vite 8 auto-converts it to `oxc`, but
migrate explicitly for correctness:

```js
// Before
esbuild: {
  jsxInject: `import React from 'react'`,
  target: 'esnext',
}

// After
oxc: {
  jsxInject: `import React from 'react'`,
  // 'target' is not needed; Vite sets it from build.target
}
```

Conversion table for common options:

| `esbuild` option | `oxc` equivalent |
|---|---|
| `jsxInject` | `oxc.jsxInject` |
| `include` / `exclude` | `oxc.include` / `oxc.exclude` |
| `jsx: 'automatic'` | `oxc.jsx: { runtime: 'automatic' }` |
| `jsx: 'transform'` | `oxc.jsx: { runtime: 'classic' }` |
| `jsxImportSource` | `oxc.jsx.importSource` |
| `jsxFactory` | `oxc.jsx.pragma` |
| `jsxFragment` | `oxc.jsx.pragmaFrag` |
| `define` | `oxc.define` |

`esbuild.banner` / `esbuild.footer` have no direct `oxc` equivalent — migrate to
a custom plugin using the `transform` hook.

### Native decorator lowering

Oxc does not yet lower native decorators. Use Babel or SWC as a workaround:

```bash
npm install -D @rolldown/plugin-babel @babel/plugin-proposal-decorators
```

```js
import babel from '@rolldown/plugin-babel'

export default defineConfig({
  plugins: [
    babel({
      presets: [() => ({
        plugins: [['@babel/plugin-proposal-decorators', { version: '2023-11' }]],
      })],
    }),
  ],
})
```

---

## Oxc minifier replaces esbuild

JS minification now uses Oxc by default.

```js
// To temporarily revert to esbuild minifier (deprecated, needs esbuild installed):
build: { minify: 'esbuild' }
npm install -D esbuild
```

For minification options previously on `esbuild.*`:

```js
// Before
esbuild: { drop: ['console', 'debugger'] }

// After
build: {
  rolldownOptions: {
    output: {
      minify: { compress: { drop_console: true, drop_debugger: true } },
    },
  },
},
```

Note: property mangling (`mangleProps` etc.) is not supported by Oxc.

---

## Lightning CSS for CSS minification

CSS is now minified by [Lightning CSS](https://lightningcss.dev/) instead of
esbuild. Bundle size may increase slightly due to improved syntax lowering.

```js
// To revert to esbuild CSS minification (needs esbuild installed):
build: { cssMinify: 'esbuild' }
npm install -D esbuild
```

---

## CJS interop changes

The `default` import from CJS modules is now handled consistently via Rolldown.
Previously, dev and build had different rules.

New rule: `default` is `module.exports` when:
- Importer is `.mjs`/`.mts`, or `package.json` has `"type": "module"`, **or**
- `module.exports.__esModule` is not `true`

Otherwise, `default` is `module.exports.default`.

If the change breaks existing code importing CJS packages:

```js
// Temporary opt-out (deprecated):
legacy: { inconsistentCjsInterop: true }
```

Report affected packages upstream and link to the
[Rolldown CJS docs](https://rolldown.rs/in-depth/bundling-cjs).

---

## `optimizeDeps.esbuildOptions` deprecated → `optimizeDeps.rolldownOptions`

```js
// Before
optimizeDeps: { esbuildOptions: { ... } }

// After
optimizeDeps: { rolldownOptions: { ... } }
```

Common auto-converted options:

| `esbuildOptions` | `rolldownOptions` |
|---|---|
| `minify` | `output.minify` |
| `define` | `transform.define` |
| `loader` | `moduleTypes` |
| `conditions` | `resolve.conditionNames` |
| `platform` | `platform` |

---

## `import.meta.url` in UMD / IIFE

`import.meta.url` is no longer polyfilled in UMD/IIFE output. It becomes
`undefined`. If you need the previous behaviour, add it manually via
`build.rolldownOptions.output.intro`.

---

## `transformWithEsbuild` deprecated

Migrate to `transformWithOxc` (re-exported from `vite`). If a plugin you depend
on uses `transformWithEsbuild`, install `esbuild` as a `devDependency` until the
plugin is updated.

---

## `require` for externalized modules preserved

`require()` calls for externalized modules are no longer converted to `import`.
If you need ESM-style behaviour:

```bash
npm install vite   # already there
```

```js
import { defineConfig, esmExternalRequirePlugin } from 'vite'

export default defineConfig({
  plugins: [
    esmExternalRequirePlugin({ external: ['react', 'vue'] }),
  ],
})
```

---

## Removed deprecated features (from prior versions)

| Removed | Notes |
|---|---|
| `import.meta.hot.accept(url)` with URL argument | Pass an id string instead |
| `build.rollupOptions.output.format: 'system'` / `'amd'` | Not supported by Rolldown |
| `shouldTransformCachedModule` hook | Not supported by Rolldown |
| `resolveImportMeta` hook | Not supported by Rolldown |
| `renderDynamicImport` hook | Not supported by Rolldown |
| `resolveFileUrl` hook | Not supported by Rolldown |

---

## Plugin authors

- All parallel Rollup hooks now execute sequentially in Rolldown. Review timing-sensitive plugins.
- If a `load` / `transform` hook converts non-JS content to JS, add `moduleType: 'js'` to the return value.
- `bundle[foo] = ...` assignment in `generateBundle` / `writeBundle` not supported — use `this.emitFile()`.
- `structuredClone(bundle)` errors — use `structuredClone({ ...bundle })`.

---

## Gradual migration path

If you want to migrate incrementally, first switch to `rolldown-vite` (Vite 7
with Rolldown) before upgrading to Vite 8:

```json
{ "devDependencies": { "vite": "npm:rolldown-vite@7.2.2" } }
```

Then upgrade to Vite 8 when ready:

```json
{ "devDependencies": { "vite": "^8.0.0" } }
```

---

## Verification

```bash
npm install
npm run dev
npm run build
```

---

## References

- [Vite 8 migration guide](https://v8.vite.dev/guide/migration.html)
- [Rolldown docs](https://rolldown.rs/)
- [Oxc docs](https://oxc.rs/)
- [Lightning CSS](https://lightningcss.dev/)
