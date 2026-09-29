---
description: "Migrate Vite from one major version to another. Usage: /migrate-vite <from> <to> (e.g. /migrate-vite 5 6)"
subtask: true
---

Use the `vite-version-migrator` skill to migrate this project from Vite $1 to Vite $2.

Follow the full skill workflow: read the relevant migration guide(s), audit the codebase for affected patterns, update Vite and peer dependencies, apply breaking config changes to `vite.config.js/ts`, update the Node.js version requirement if needed, update CI/CD configs, and verify the dev server and production build.

If the version gap requires multiple hops (e.g. v4→v5→v6), chain the guides in order and complete all steps for each hop before starting the next.
