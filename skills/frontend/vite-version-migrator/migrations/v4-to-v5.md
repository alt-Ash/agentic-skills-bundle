# Vite v4 → v5 Migration Guide

> Reference: https://v5.vite.dev/guide/migration.html

## Contents

- Node.js requirement
- Breaking changes
- Removed deprecated APIs
- Advanced / plugin-author changes

---

## Node.js requirement

Vite 5 requires **Node.js 18 or 20+**. Node.js 14, 16, 17, and 19 are no longer supported.

Update `.nvmrc`, `package.json` engines, and CI configs accordingly.

---

## Breaking changes

| Area | Change | Action |
|---|---|---|
| Rollup 4 | `assertions` prop renamed to `attributes` on import objects | Update any plugin/config using `assertions` |
| Rollup 4 | Acorn plugins no longer supported | Remove acorn plugin usage |
| Rollup 4 | `this.resolve` `skipSelf` defaults to `true` | Review plugins using `this.resolve` |
| CJS Node API | `require('vite')` is deprecated | Switch to ESM `import` (see below) |
| `define` / `import.meta.env.*` | Replacement now uses esbuild in builds (was regex) | Expressions must be JSON-compatible or single identifiers |
| SSR externalized modules | `.default` / `.__esModule` wrapping removed | Use `import * as _foo from 'bar'` pattern if needed |
| `worker.plugins` | Must now be a function `() => Plugin[]` | Wrap existing array in a function |
| `appType: 'spa'` | Paths containing `.` now fall back to `index.html` | Check if any static file routes relied on old 404 behaviour |
| Manifest files | Generated in `.vite/` subdirectory inside `build.outDir` | Update backend manifest path references |
| CSS in manifest | CSS entries no longer listed as top-level manifest entries | Inject CSS via the JS entry's `css` array |
| CLI shortcuts | Require extra `Enter` press (e.g. `r + Enter`) | Update any automation that relied on single-key shortcuts |
| TypeScript decorators | `experimentalDecorators` no longer enabled by default | Add `"experimentalDecorators": true` to `tsconfig.json` if needed |
| `useDefineForClassFields` | Defaults based on TS `target`; may behave differently | Set `target: "ESNext"` or explicitly set `useDefineForClassFields` |
| `--https` / `server.https: true` | Removed (no bundled cert generation) | Use `@vitejs/plugin-basic-ssl` or `vite-plugin-mkcert` |
| `resolvePackageEntry` / `resolvePackageData` APIs | Removed | Replace with `import.meta.resolve` / `vitefu` |
| TypeScript `moduleResolution` | Rollup 4 requires `bundler`, `node16`, or `nodenext` | Update `tsconfig.json` |

---

## CJS Node API → ESM

### vite.config.js

Ensure your config uses ESM syntax:

```js
// Before (CJS)
const { defineConfig } = require('vite')
module.exports = defineConfig({ ... })

// After (ESM)
import { defineConfig } from 'vite'
export default defineConfig({ ... })
```

Also ensure the closest `package.json` has `"type": "module"`, or rename the
config to `vite.config.mjs` / `vite.config.mts`.

---

## `worker.plugins` — wrap in a function

```js
// Before (Vite 4)
export default defineConfig({
  worker: {
    plugins: [myPlugin()],
  },
})

// After (Vite 5)
export default defineConfig({
  worker: {
    plugins: () => [myPlugin()],
  },
})
```

---

## Manifest path update

If your backend reads the Vite manifest, update the path:

```
# Before
dist/manifest.json

# After
dist/.vite/manifest.json
```

---

## TypeScript — `tsconfig.json`

```jsonc
{
  "compilerOptions": {
    "moduleResolution": "bundler",   // or "node16" / "nodenext"
    // If you use decorators:
    "experimentalDecorators": true,
    // Recommended to avoid class-field surprises:
    "useDefineForClassFields": true,
    "target": "ESNext"
  }
}
```

---

## Removed deprecated APIs

| Deprecated (v4) | Replacement |
|---|---|
| Default CSS import `import style from './foo.css'` | Use `?inline` query: `import style from './foo.css?inline'` |
| `import.meta.globEager(...)` | `import.meta.glob('*', { eager: true })` |
| `ssr.format: 'cjs'` | — (removed, no direct replacement) |
| `legacy.buildSsrCjsExternalHeuristics` | — (removed) |
| `server.middlewareMode: 'ssr'` / `'html'` | `appType` + `server.middlewareMode: true` |

Grep for usages:

```bash
grep -rn "globEager\|\.css'\|\.css\"\|middlewareMode.*ssr\|middlewareMode.*html" . \
  --include="*.js" --include="*.ts" --include="*.mjs"
```

---

## Rollup 4 — `build.rollupOptions`

If you pass Rollup options directly, review the [Rollup v4 changelog](https://github.com/rollup/rollup/releases/tag/v4.0.0) for additional breaking changes. Key item:

```js
// Before: import assertion attribute key
{ assertions: { type: 'json' } }

// After: import attribute key
{ attributes: { type: 'json' } }
```

---

## Verification

```bash
npm install
npm run dev     # check browser console
npm run build
npm run preview # if available
```

---

## References

- [Vite 5 migration guide](https://v5.vite.dev/guide/migration.html)
- [Rollup v4 release notes](https://github.com/rollup/rollup/releases/tag/v4.0.0)
