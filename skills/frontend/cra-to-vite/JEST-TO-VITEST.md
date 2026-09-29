# Jest-to-Vitest Migration

CRA bundles Jest. Vite does not. This file covers both options.

## Contents

- Option A: Migrate to Vitest (recommended)
- Option B: Keep Jest (minimal change)
- Jest-to-Vitest differences reference

---

## Option A: Migrate to Vitest (recommended)

Vitest integrates natively with Vite, supports `import.meta.env` out of the box, and is API-compatible with Jest. Most tests require no changes.

### 1. Install Vitest

```bash
npm install -D vitest @testing-library/jest-dom jsdom
```

### 2. Configure Vitest inside vite.config

Add a `test` block to your existing `vite.config.ts` (or `.js`):

```ts
/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/setupTests.ts',
  },
});
```

> `globals: true` makes `describe`, `it`, `expect`, `vi`, etc. available globally — matching Jest's default behavior. Without it you must import them explicitly from `vitest`.

### 3. Update setupTests file

CRA projects generate `src/setupTests.ts` (or `.js`) with this import:

```ts
// CRA default — the /extend-expect path does not exist in modern versions
import '@testing-library/jest-dom/extend-expect';
```

Replace it with the current import:

```ts
import '@testing-library/jest-dom';
```

`@testing-library/jest-dom` works with Vitest as-is — no other changes needed.

### 4. Update package.json scripts

```json
"scripts": {
  "test": "vitest",
  "test:run": "vitest run",
  "test:ui": "vitest --ui"
}
```

> `vitest` runs in watch mode. `vitest run` runs once (equivalent to `jest --watchAll=false`).

### 5. Remove Jest dependencies

```bash
npm uninstall jest jest-environment-jsdom babel-jest @babel/core @babel/preset-env @babel/preset-react @babel/preset-typescript ts-jest @types/jest
```

Also delete Jest config files if present: `jest.config.js`, `jest.config.ts`, `babel.config.js`.

---

## Option B: Keep Jest (minimal change)

Use this if you want to defer the testing migration. Note that `import.meta.env` is not natively available in Jest — you must mock it manually.

Install the packages needed to run Jest standalone (without react-scripts):

```bash
npm install -D jest jest-environment-jsdom @testing-library/jest-dom
npm install -D babel-jest @babel/core @babel/preset-env @babel/preset-react @babel/preset-typescript
```

Create `babel.config.js` at project root:

```js
module.exports = {
  presets: [
    ['@babel/preset-env', { targets: { node: 'current' } }],
    ['@babel/preset-react', { runtime: 'automatic' }],
    '@babel/preset-typescript',
  ],
};
```

Create `jest.config.js` at project root:

```js
module.exports = {
  testEnvironment: 'jsdom',
  setupFilesAfterFramework: ['@testing-library/jest-dom'],
  moduleNameMapper: {
    '\\.(css|less|scss|sass)$': '<rootDir>/__mocks__/fileMock.js',
    '\\.(jpg|jpeg|png|gif|svg)$': '<rootDir>/__mocks__/fileMock.js',
  },
  globals: {
    'import.meta': { env: { MODE: 'test', DEV: true, PROD: false } },
  },
};
```

Create `__mocks__/fileMock.js`:

```js
module.exports = 'test-file-stub';
```

Update `package.json`:

```json
"test": "jest"
```

---

## Jest-to-Vitest differences reference

Most Jest tests work unchanged in Vitest. The items below are the known differences.

### Globals must be enabled or imported

Jest enables globals by default. Vitest does not unless `globals: true` is set in config.

With `globals: true` (recommended for CRA migrations): no test file changes needed.

Without `globals: true`, add imports to each test file:

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
```

### `jest.*` → `vi.*`

All `jest` namespace calls map directly to `vi`:

| Jest                          | Vitest                             |
| ----------------------------- | ---------------------------------- |
| `jest.fn()`                   | `vi.fn()`                          |
| `jest.spyOn()`                | `vi.spyOn()`                       |
| `jest.mock()`                 | `vi.mock()`                        |
| `jest.unmock()`               | `vi.unmock()`                      |
| `jest.resetAllMocks()`        | `vi.resetAllMocks()`               |
| `jest.clearAllMocks()`        | `vi.clearAllMocks()`               |
| `jest.restoreAllMocks()`      | `vi.restoreAllMocks()`             |
| `jest.useFakeTimers()`        | `vi.useFakeTimers()`               |
| `jest.useRealTimers()`        | `vi.useRealTimers()`               |
| `jest.advanceTimersByTime(n)` | `vi.advanceTimersByTime(n)`        |
| `jest.runAllTimers()`         | `vi.runAllTimers()`                |
| `jest.setTimeout(n)`          | `vi.setConfig({ testTimeout: n })` |
| `jest.requireActual(mod)`     | `await vi.importActual(mod)`       |

Search and replace across test files:

```bash
grep -r "jest\." src/ --include="*.test.*" --include="*.spec.*"
```

### Module mock factory must return an object

In Jest, the factory can return a primitive. In Vitest it must return an object with named exports:

```ts
// Jest
jest.mock('./path', () => 'hello');

// Vitest
vi.mock('./path', () => ({ default: 'hello' }));
```

### `__mocks__` directory is not auto-loaded

In Jest, manual mocks in `__mocks__/` are applied automatically for node modules. In Vitest they are only used when `vi.mock()` is explicitly called. If you relied on auto-mocking, add `vi.mock('module-name')` calls to your test setup file or individual tests.

### `mockReset` behavior differs

`jest.mockReset()` clears the implementation (returns `undefined`).
`vi.mockReset()` resets to the **original implementation** passed to `vi.fn(impl)`.

If you reset mocks globally in `afterEach`, verify this doesn't cause tests to call real implementations unexpectedly.

### TypeScript: import types from `vitest` not `jest`

```ts
// Jest
let fn: jest.Mock<(name: string) => number>;

// Vitest
import type { Mock } from 'vitest';
let fn: Mock<(name: string) => number>;
```

### `done` callback not supported

Vitest does not support the `done` callback style. Rewrite as async/await or return a Promise:

```ts
// Jest
it('works', (done) => {
  fetchData().then((data) => {
    expect(data).toBe('ok');
    done();
  });
});

// Vitest
it('works', async () => {
  const data = await fetchData();
  expect(data).toBe('ok');
});
```

### Hooks must not return non-teardown values

Vitest interprets a function returned from `beforeEach`/`beforeAll` as a teardown function. If your hook returns something unintentionally, wrap it:

```ts
// Risky — if setActivePinia returns something, Vitest treats it as teardown
beforeEach(() => setActivePinia(createTestingPinia()));

// Safe
beforeEach(() => {
  setActivePinia(createTestingPinia());
});
```

### `expect.getState().currentTestName` separator

Jest joins describe and test names with a space. Vitest uses `>`. Affects snapshot names and any assertions on `currentTestName`.

### `import.meta.env` is available natively

No mocking needed. Vitest reads from your Vite config and `.env.test` files automatically.

---

## Scope of test changes during migration

Only change tests for reasons directly caused by the Jest→Vitest switch. Use this checklist to decide whether a test failure is in scope:

| Failure reason                                           | In scope? | Action                                          |
| -------------------------------------------------------- | --------- | ----------------------------------------------- |
| `jest.*` API used (`jest.fn`, `jest.mock`, etc.)         | Yes       | Replace with `vi.*` equivalent                  |
| Globals not found (`describe`, `it`, `expect`)           | Yes       | Enable `globals: true` in config or add imports |
| `import '@testing-library/jest-dom/extend-expect'` fails | Yes       | Update to `import '@testing-library/jest-dom'`  |
| `done` callback not supported                            | Yes       | Rewrite as async/await                          |
| Test was already failing before migration                | No        | Fix separately — do not change test logic       |
| Test fails due to missing mock or context                | No        | Fix separately — do not change test logic       |

If a test fails for a reason not in the left column, treat it as a pre-existing issue and flag it for the team rather than fixing it as part of the migration.
