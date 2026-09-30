# Compact Context Template

Use this by default in skills. Emit the full `templates/CONTEXT-BLOCK.md` only when a downstream parser, user request, or agent handoff explicitly needs the full structure.

```text
Context: <skill>; <project type>; build=<maven|gradle>; java=<version>; versions=<key versions>; files=<paths read>; gaps=<none|items>
```

Rules:

- Include only facts actually read or inferred from files/user prompt.
- Use `unknown` for non-blocking unknowns; ask before proceeding if the unknown blocks safe work.
- Do not repeat static skill instructions in context output.
