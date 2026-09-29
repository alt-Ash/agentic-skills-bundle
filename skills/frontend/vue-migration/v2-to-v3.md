# Vue 2 → Vue 3 Breaking Changes Reference

Full official guide: https://v3-migration.vuejs.org/breaking-changes/

---

## Global API

### `new Vue()` → `createApp()`

```js
// Vue 2
import Vue from 'vue'
import App from './App.vue'
new Vue({ render: h => h(App) }).$mount('#app')

// Vue 3
import { createApp } from 'vue'
import App from './App.vue'
createApp(App).mount('#app')
```

| Vue 2 | Vue 3 |
|---|---|
| `Vue.config.*` | `app.config.*` |
| `Vue.component()` | `app.component()` |
| `Vue.directive()` | `app.directive()` |
| `Vue.use()` | `app.use()` |
| `Vue.mixin()` | `app.mixin()` |
| `Vue.prototype.foo` | `app.config.globalProperties.foo` |
| `Vue.extend()` | `defineComponent()` or `extends` option |
| `Vue.observable()` | `reactive()` |
| `Vue.set()` / `Vue.delete()` | No longer needed (Proxy-based reactivity) |
| `vm.$set()` / `vm.$delete()` | No longer needed |

Docs: https://v3-migration.vuejs.org/breaking-changes/global-api

### Global API tree-shaking

APIs are now named exports — import only what you use:

```js
// Vue 2 — attached to Vue constructor
Vue.nextTick(() => {})

// Vue 3 — named import (tree-shakable)
import { nextTick } from 'vue'
nextTick(() => {})
```

Affects: `nextTick`, `observable` → `reactive`, `version`, `compile`, `set`, `delete`.

Docs: https://v3-migration.vuejs.org/breaking-changes/global-api-treeshaking

---

## Template Directives

### `v-model` on components

`v-bind.sync` is removed. `v-model` now uses `modelValue` prop and `update:modelValue` event.

```html
<!-- Vue 2 -->
<MyInput v-model="title" />
<!-- equivalent to: :value="title" @input="title = $event" -->

<MyInput :title.sync="title" />
<!-- equivalent to: :title="title" @update:title="title = $event" -->

<!-- Vue 3 -->
<MyInput v-model="title" />
<!-- equivalent to: :modelValue="title" @update:modelValue="title = $event" -->

<MyInput v-model:title="title" />
<!-- equivalent to: :title="title" @update:title="title = $event" -->
```

Multiple `v-model` bindings are now possible:

```html
<MyForm v-model:name="name" v-model:email="email" />
```

Docs: https://v3-migration.vuejs.org/breaking-changes/v-model

### `v-if` / `v-for` precedence

**Vue 2**: `v-for` had higher priority than `v-if` on the same element.
**Vue 3**: `v-if` now has higher priority — `v-if` cannot access `v-for` variables.

```html
<!-- Broken in Vue 3 — item is not in scope for v-if -->
<li v-for="item in list" v-if="item.active">{{ item.name }}</li>

<!-- Fix: move v-if to a wrapper or use computed -->
<template v-for="item in list">
  <li v-if="item.active">{{ item.name }}</li>
</template>
```

Docs: https://v3-migration.vuejs.org/breaking-changes/v-if-v-for

### `key` attribute changes

- `key` on `<template v-for>` must now be on `<template>`, not on children
- `v-if`/`v-else`/`v-else-if` branches no longer need `key` (auto-generated)

```html
<!-- Vue 2 -->
<template v-for="item in list">
  <div :key="item.id">...</div>
</template>

<!-- Vue 3 -->
<template v-for="item in list" :key="item.id">
  <div>...</div>
</template>
```

Docs: https://v3-migration.vuejs.org/breaking-changes/key-attribute

### `v-bind` merge order

`v-bind="object"` is now order-sensitive — later bindings win:

```html
<!-- Vue 3: id will be "blue" (explicit :id wins, comes last) -->
<div v-bind="{ id: 'red' }" :id="'blue'"></div>

<!-- Vue 3: id will be "red" (object wins, comes last) -->
<div :id="'blue'" v-bind="{ id: 'red' }"></div>
```

Docs: https://v3-migration.vuejs.org/breaking-changes/v-bind

### `v-on.native` removed

Native event listeners are no longer needed — non-declared emits fall through to the root element:

```html
<!-- Vue 2 -->
<MyButton @click.native="handler" />

<!-- Vue 3 — .native removed; declare emits to prevent fallthrough -->
<MyButton @click="handler" />
```

Docs: https://v3-migration.vuejs.org/breaking-changes/v-on-native-modifier-removed

---

## Components

### Functional components

```js
// Vue 2 — class-based or options with functional: true
export default {
  functional: true,
  render(h, { props }) {
    return h('div', props.text)
  }
}

// Vue 3 — plain function (no functional option needed)
// h is imported, no context argument
import { h } from 'vue'
export default (props) => h('div', props.text)
```

`<template functional>` in SFCs is removed entirely.

Docs: https://v3-migration.vuejs.org/breaking-changes/functional-components

### Async components

```js
// Vue 2
const AsyncComp = () => import('./MyComp.vue')

// Vue 3 — must use defineAsyncComponent
import { defineAsyncComponent } from 'vue'
const AsyncComp = defineAsyncComponent(() => import('./MyComp.vue'))

// Vue 3 — with options
const AsyncComp = defineAsyncComponent({
  loader: () => import('./MyComp.vue'),
  loadingComponent: LoadingSpinner,
  errorComponent: ErrorComponent,
  delay: 200,
  timeout: 3000
})
```

Docs: https://v3-migration.vuejs.org/breaking-changes/async-components

### `emits` option

Components should declare emitted events explicitly:

```js
export default {
  emits: ['submit', 'update:modelValue'],
  // or with validation:
  emits: {
    submit: (payload) => payload !== null,
  }
}
```

Undeclared emits fall through as native DOM events on the root element — this can
cause double-firing if `v-on` is also used from the parent.

Docs: https://v3-migration.vuejs.org/breaking-changes/emits-option

---

## Render Function

### `h` is now imported globally

```js
// Vue 2 — h injected as argument
render(h) {
  return h('div', {})
}

// Vue 3 — import h
import { h } from 'vue'
export default {
  render() {
    return h('div', {})
  }
}
```

### VNode props structure flattened

```js
// Vue 2
h('div', {
  class: ['foo'],
  style: { color: 'red' },
  attrs: { id: 'my-div' },
  domProps: { innerHTML: 'hello' },
  on: { click: handler }
})

// Vue 3 — flat structure
h('div', {
  class: ['foo'],
  style: { color: 'red' },
  id: 'my-div',
  innerHTML: 'hello',
  onClick: handler
})
```

Docs: https://v3-migration.vuejs.org/breaking-changes/render-function-api

### `$slots` unification — `$scopedSlots` removed

```js
// Vue 2
this.$slots.default     // static slots
this.$scopedSlots.default() // scoped slots

// Vue 3 — all slots are functions on $slots
this.$slots.default?.()
```

Docs: https://v3-migration.vuejs.org/breaking-changes/slots-unification

### `$listeners` removed — merged into `$attrs`

```js
// Vue 2
this.$listeners // { click: fn, focus: fn }
this.$attrs     // { id: '...' } (no class/style)

// Vue 3
this.$attrs     // includes listeners (onClick), class, and style
```

If you used `v-bind="$listeners"` to forward events, replace with `v-bind="$attrs"`.

Docs: https://v3-migration.vuejs.org/breaking-changes/listeners-removed
Docs: https://v3-migration.vuejs.org/breaking-changes/attrs-includes-class-style

---

## Lifecycle Hooks Renamed

| Vue 2 | Vue 3 |
|---|---|
| `beforeCreate` | `beforeCreate` (or use `setup()`) |
| `created` | `created` (or use `setup()`) |
| `beforeDestroy` | `beforeUnmount` |
| `destroyed` | `unmounted` |

---

## Removed APIs

### Event Bus (`$on`, `$off`, `$once`)

```js
// Vue 2 EventBus pattern
const bus = new Vue()
bus.$on('event', handler)
bus.$emit('event', data)

// Vue 3 — use mitt or tiny-emitter
import mitt from 'mitt'
const emitter = mitt()
emitter.on('event', handler)
emitter.emit('event', data)

// Or use Pinia store actions for app-level events
```

Docs: https://v3-migration.vuejs.org/breaking-changes/events-api

### Filters

```html
<!-- Vue 2 -->
{{ price | currency }}

<!-- Vue 3 — use computed or a method -->
{{ formatCurrency(price) }}
```

For global filters, replace with `app.config.globalProperties`:

```js
// Vue 2
Vue.filter('currency', (val) => '$' + val)

// Vue 3 — use a utility function or globalProperties
app.config.globalProperties.$filters = {
  currency: (val) => '$' + val
}
```

Docs: https://v3-migration.vuejs.org/breaking-changes/filters

### `$children`

`vm.$children` is removed. Use template refs instead:

```html
<template>
  <MyChild ref="child" />
</template>
<script>
// access via this.$refs.child
</script>
```

Docs: https://v3-migration.vuejs.org/breaking-changes/children

### `inline-template`

Removed. Use default slots or SFCs instead.

Docs: https://v3-migration.vuejs.org/breaking-changes/inline-template-attribute

### `propsData`

Removed from `new Vue()`. Pass initial props via `createApp(App, props)`:

```js
// Vue 2
new Vue({ propsData: { name: 'Alice' } })

// Vue 3
createApp(App, { name: 'Alice' }).mount('#app')
```

Docs: https://v3-migration.vuejs.org/breaking-changes/props-data

### `keyCode` modifiers

```html
<!-- Vue 2 -->
<input @keyup.13="submit" />

<!-- Vue 3 — use key names only -->
<input @keyup.enter="submit" />
```

`config.keyCodes` is also removed.

Docs: https://v3-migration.vuejs.org/breaking-changes/keycode-modifiers

---

## Other Minor Changes

### `data` must always be a function

```js
// Vue 2 — allowed object on root
new Vue({ data: { count: 0 } })

// Vue 3 — always a function
createApp({ data() { return { count: 0 } } })
```

Mixin `data` is now shallow-merged (not deep-merged) with component `data`.

Docs: https://v3-migration.vuejs.org/breaking-changes/data-option

### Custom directives — lifecycle hooks renamed

| Vue 2 | Vue 3 |
|---|---|
| `bind` | `beforeMount` |
| `inserted` | `mounted` |
| `update` | `beforeUpdate` |
| `componentUpdated` | `updated` |
| `unbind` | `unmounted` |

Docs: https://v3-migration.vuejs.org/breaking-changes/custom-directives

### Transition class names

```
.v-enter  → .v-enter-from
.v-leave  → .v-leave-from
```

Do a project-wide search for `.*-enter` and `.*-leave` CSS class names and add `-from`.

Docs: https://v3-migration.vuejs.org/breaking-changes/transition

### `<TransitionGroup>` no longer renders a root element

In Vue 2 it rendered a `<span>` by default. In Vue 3 it renders no wrapper.
Use the `tag` prop to restore the old behavior if needed: `<TransitionGroup tag="span">`.

Docs: https://v3-migration.vuejs.org/breaking-changes/transition-group

### Props default function — no `this` access

```js
// Vue 2
props: {
  theme: {
    default() { return this.globalTheme } // ❌ Vue 3: this is undefined
  }
}

// Vue 3 — inject dependencies explicitly
props: {
  theme: { default: 'light' }
}
```

Docs: https://v3-migration.vuejs.org/breaking-changes/props-default-this

### `watch` on arrays — requires `deep: true` for mutations

```js
// Vue 2 — triggered on push/splice
watch: { list(val) { ... } }

// Vue 3 — only triggered on array replacement unless deep: true
watch: {
  list: {
    handler(val) { ... },
    deep: true
  }
}
```

Docs: https://v3-migration.vuejs.org/breaking-changes/watch

### Mount behavior change

`app.mount('#app')` no longer replaces `#app` — it renders inside it.

```html
<!-- Vue 2: replaces #app -->
<!-- Vue 3: renders inside #app -->
<div id="app"><!-- App content rendered here --></div>
```

Docs: https://v3-migration.vuejs.org/breaking-changes/mount-changes

---

## `@vue/compat` Migration Build

Use this for incremental migration of large apps. Runs in Vue 2 compatibility mode
with runtime warnings for every breaking change.

Full workflow: https://v3-migration.vuejs.org/migration-build

### Quick setup (Vite)

```js
// vite.config.js
export default {
  resolve: {
    alias: { vue: '@vue/compat' }
  },
  plugins: [
    vue({
      template: {
        compilerOptions: {
          compatConfig: { MODE: 2 }
        }
      }
    })
  ]
}
```

### Disable specific compat warnings

```js
import { configureCompat } from 'vue'
configureCompat({
  MODE: 3,              // default to Vue 3
  FILTERS: true,        // keep Vue 2 filter support temporarily
  INSTANCE_CHILDREN: true
})
```

### Per-component opt-in to Vue 3 mode

```js
export default {
  compatConfig: { MODE: 3 },
  // this component now uses Vue 3 APIs fully
}
```
