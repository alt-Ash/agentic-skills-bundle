# Vue 3 Ecosystem Migration Reference

Official recommendations: https://v3-migration.vuejs.org/recommendations

---

## Build Toolchain: Vue CLI → Vite

Vue CLI is in maintenance mode. New projects and migrations should use Vite.

```bash
# Scaffold a new Vue 3 + Vite project
npm init vue@3
```

### Manual migration (Vue CLI → Vite)

1. Remove `@vue/cli-service`, `babel.config.js`, `vue.config.js`
2. Install Vite and the Vue plugin:
   ```bash
   npm install -D vite @vitejs/plugin-vue
   ```
3. Create `vite.config.js`:
   ```js
   import { defineConfig } from 'vite'
   import vue from '@vitejs/plugin-vue'

   export default defineConfig({
     plugins: [vue()],
     resolve: {
       alias: { '@': '/src' }
     }
   })
   ```
4. Move `public/index.html` to root and update script tag:
   ```html
   <script type="module" src="/src/main.js"></script>
   ```
5. Replace `process.env.VUE_APP_*` env vars with `import.meta.env.VITE_*`
6. Update `package.json` scripts:
   ```json
   {
     "scripts": {
       "dev": "vite",
       "build": "vite build",
       "preview": "vite preview"
     }
   }
   ```

Migration guides:
- https://vueschool.io/articles/vuejs-tutorials/how-to-migrate-from-vue-cli-to-vite/
- https://github.com/vitejs/awesome-vite#vue-cli

---

## Vue Router: v3 → v4

Vue Router v4 is required for Vue 3. It has breaking changes of its own.

Full migration guide: https://router.vuejs.org/guide/migration/

```bash
npm install vue-router@^4
```

### Key changes

| Vue Router 3 | Vue Router 4 |
|---|---|
| `new VueRouter({ routes })` | `createRouter({ history, routes })` |
| `mode: 'history'` | `history: createWebHistory()` |
| `mode: 'hash'` | `history: createWebHashHistory()` |
| `mode: 'abstract'` | `history: createMemoryHistory()` |
| `router.app` | removed |
| `router.match()` | `router.resolve()` |
| Catch-all `*` route | `{ path: '/:pathMatch(.*)*' }` |

```js
// Vue Router 3
import VueRouter from 'vue-router'
const router = new VueRouter({
  mode: 'history',
  routes: [...]
})

// Vue Router 4
import { createRouter, createWebHistory } from 'vue-router'
const router = createRouter({
  history: createWebHistory(),
  routes: [...]
})
```

### `<router-view>` with `<transition>` and `<keep-alive>`

Must use the scoped slot syntax in v4:

```html
<!-- Vue Router 3 -->
<transition><router-view /></transition>

<!-- Vue Router 4 -->
<router-view v-slot="{ Component }">
  <transition>
    <component :is="Component" />
  </transition>
</router-view>
```

### Navigation guards — `next` is optional

```js
// Vue Router 3
router.beforeEach((to, from, next) => {
  if (!isAuthenticated) next('/login')
  else next()
})

// Vue Router 4 — return value replaces next()
router.beforeEach((to, from) => {
  if (!isAuthenticated) return '/login'
  // return true or undefined to allow
})
```

### `vuex-router-sync` removal

Replace with a store getter:

```js
// In your Pinia/Vuex store
import { useRoute } from 'vue-router'
const route = useRoute()
```

---

## State Management: Vuex 3 → Vuex 4 or Pinia

### Option A: Vuex 4 (minimal change)

Vuex 4 supports Vue 3 with the same API as Vuex 3.
Only breaking change is installation:

```bash
npm install vuex@^4
```

```js
// Vuex 3
import Vue from 'vue'
import Vuex from 'vuex'
Vue.use(Vuex)
const store = new Vuex.Store({ ... })

// Vuex 4
import { createStore } from 'vuex'
const store = createStore({ ... })
app.use(store)
```

Migration guide: https://vuex.vuejs.org/guide/migrating-to-4-0-from-3-x.html

### Option B: Pinia (recommended for new code / full rewrites)

Pinia is the official state management successor. No mutations, better TypeScript,
modular by default.

```bash
npm install pinia
```

```js
// main.js
import { createPinia } from 'pinia'
app.use(createPinia())
```

```js
// stores/counter.js
import { defineStore } from 'pinia'

export const useCounterStore = defineStore('counter', {
  state: () => ({ count: 0 }),
  getters: {
    double: (state) => state.count * 2
  },
  actions: {
    increment() { this.count++ }
  }
})

// In component:
import { useCounterStore } from '@/stores/counter'
const counter = useCounterStore()
counter.increment()
console.log(counter.double)
```

Vuex → Pinia migration guide: https://pinia.vuejs.org/cookbook/migration-vuex.html

---

## IDE Support: Vetur → Volar

Vetur does not support Vue 3 properly. Replace with Volar (Vue - Official).

1. **Disable Vetur** in VS Code extensions
2. **Install Volar**: search "Vue - Official" (id: `Vue.volar`)
3. Enable **Takeover Mode** if using TypeScript (disables VS Code's built-in TS server
   for `.vue` files — Volar handles both):
   - Run command: "Extensions: Show Built-in Extensions"
   - Find "TypeScript and JavaScript Language Features"
   - Right-click → Disable (workspace only)

Extension: https://marketplace.visualstudio.com/items?itemName=Vue.volar

---

## TypeScript: `vue-tsc`

`vue-tsc` provides CLI type checking and declaration generation for Vue SFCs.

```bash
npm install -D vue-tsc
```

```json
// package.json
{
  "scripts": {
    "type-check": "vue-tsc --noEmit"
  }
}
```

Replaces `tsc` for projects with `.vue` files.
Guide: https://vuejs.org/guide/typescript/overview.html

---

## DevTools Extension

Update to DevTools v6+ (supports both Vue 2 and Vue 3).

- If you installed v6 via the beta channel previously, remove it and install from stable.
- Guide: https://devtools.vuejs.org/guide/installation.html

---

## SSR: `vue-server-renderer` → `@vue/server-renderer`

```bash
npm uninstall vue-server-renderer
npm install @vue/server-renderer
```

Vue 3 no longer provides a bundle renderer. Recommended SSR setup:
- **Nuxt 3** for full-stack apps
- **Vite SSR** for custom setups: https://vitejs.dev/guide/ssr.html

---

## JSX Support

```bash
# Vue 2
npm install @vue/babel-preset-jsx

# Vue 3
npm install @vue/babel-plugin-jsx
```

```js
// babel.config.js (Vue 3)
module.exports = {
  plugins: ['@vue/babel-plugin-jsx']
}
```

---

## Static Site Generation: VuePress → VitePress

VitePress is the Vue 3 + Vite successor to VuePress.
Docs: https://vitepress.vuejs.org/

---

## Common Pitfalls

| Issue | Fix |
|---|---|
| `vue-template-compiler` conflict | Remove it; install `@vue/compiler-sfc` instead |
| `$on`/`$off` calls crashing | Replace EventBus with `mitt` or Pinia actions |
| Filters not working | Replace with computed properties or `$filters` on `globalProperties` |
| `<transition>` not working with `<router-view>` | Use scoped slot syntax (Vue Router v4 requirement) |
| TypeScript errors in `.vue` files | Ensure `vue-tsc` is used; disable Vetur; enable Volar takeover mode |
| Vite can't resolve `@` alias | Add `resolve.alias: { '@': '/src' }` to `vite.config.js` |
| `process.env` undefined in Vite | Replace with `import.meta.env`; prefix vars with `VITE_` |
