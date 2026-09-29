---
description: "Migrate Material UI (MUI) from one major version to another. Usage: /migrate-mui <from> <to> (e.g. /migrate-mui 4 5)"
subtask: true
---

Use the `mui-migration` skill to migrate this project from MUI v$1 to MUI v$2.

Follow the full skill workflow: read the relevant reference file for the target version, update `package.json` dependencies, run official codemods before manual changes, verify the app runs after each step, apply manual breaking changes from the reference, and run TypeScript checks if the project uses TypeScript.

Migrate sequentially — do not skip versions. If the gap requires multiple hops (e.g. v4→v5→v6), complete each hop fully before starting the next.
