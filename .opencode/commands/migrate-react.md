---
description: "Migrate a React application to React 18 or 19. Usage: /migrate-react <target> (e.g. /migrate-react 18 or /migrate-react 19)"
subtask: true
---

Use the `react-migration` skill to migrate this project to React $1.

Follow the full skill workflow for the target version. This includes: auditing peer dependencies before upgrading (do not use `--legacy-peer-deps`), replacing deprecated APIs (`ReactDOM.render` → `createRoot`, legacy hydration and server APIs), updating TypeScript types, handling automatic batching changes, configuring the test environment, and verifying the build and tests.

If migrating to React 19 and the current version is below 18, migrate to React 18 first, then to 19.
