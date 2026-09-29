# Compact Handoff Template

Use this by default in skills. Emit the full `templates/HANDOFF-BLOCK.md` only when another agent/tool requires the full machine-readable block.

```text
Handoff: <skill>; status=<completed|partial|blocked>; changed=<files>; checks=<command: result>; blockers=<none|items>; next=<one sentence>
```

Rules:

- List every changed file, but keep each change to one phrase.
- Mark skipped checks as `not run: <reason>`.
- If blocked, include required next action.
