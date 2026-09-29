---
description: "Migrate Node.js from one major version to another. Usage: /migrate-node <from> <to> (e.g. /migrate-node 20 22)"
subtask: true
---

Use the `nodejs-version-migrator` skill to migrate this project from Node.js $1 to Node.js $2.

Follow the full skill workflow, including: reading the relevant migration guide(s), auditing breaking changes, running codemods, auditing npm package compatibility, updating `package.json` engines and tooling configs (`.nvmrc`, Dockerfiles, CI/CD pipelines), verifying tests and build, and checking for license violations.

If the version gap requires multiple hops (e.g. v14→v16→v20), chain the guides in order and complete all steps for each hop before starting the next.
