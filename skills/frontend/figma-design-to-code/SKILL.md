---
name: figma-design-to-code
description: >
  Implements a Figma design as production React code. Reads design context from a Figma file via
  the Figma MCP, maps frames and components to the project's existing component library (MUI,
  shadcn/ui, Radix, etc.), and writes pixel-faithful, accessible code. Use when given a Figma
  URL or file key and asked to implement a screen, page, component, or UI feature.
---

# Figma Design to Code

## What this skill does

Reads a Figma frame or component via the Figma MCP and translates it into production React code that matches the project's existing patterns and component library. It reuses existing project components rather than creating duplicates, honors any code hints embedded in the Figma design, and verifies the rendered output in a real browser.

This skill does NOT handle design token migration (use `figma-style-migrator`) or building/updating a Figma design system from code (use `figma-generate-library`).

---

## When to use it

- Use when given a Figma URL and asked to implement a screen, page, or component
- Use when a designer shares a Figma frame and asks for the React equivalent
- Use when asked to make the UI match a Figma design

## Do not use when

- Do not use when the task is to sync design tokens into the theme — use `figma-style-migrator`
- Do not use when the task is to push code components into Figma — use `figma-generate-library`
- Do not use when no Figma file reference is available

---

## Inputs required

| Input | Source | Required |
|-------|--------|----------|
| Figma file URL or file key | User prompt | Yes |
| Frame or component name / node ID | User prompt | Yes |
| Target component library | `package.json` | Yes (auto-detected) |

---

## Phase 0 — Gather context

1. Load the full Figma MCP skill before starting any design work:

   ```
   get_figma_skill({ uri: "skill://figma/figma-design-to-code/SKILL.md" })
   ```

   Follow the loaded skill as the primary workflow, with the house additions below.

2. Read `package.json` to detect: framework (React / Next.js), UI library (MUI, shadcn, Radix), TypeScript, package manager.
3. Locate the component directory and identify naming conventions by reading 2–3 existing components.
4. Emit context at end of this phase (see template below).

### House additions to Phase 0

- If the project uses MUI: note the `createTheme` file path and palette keys — map Figma color styles to theme tokens, not hardcoded hex values.
- If the project uses CSS modules or styled-components: follow the existing file naming pattern.
- Do not create a new component if an existing one covers the design — prefer extending existing components.

> Emit compact context by default. Use the full `***CONTEXT BLOCK***` template below when a downstream agent handoff or explicit request requires it:

```
Context: figma-design-to-code; <project type>; pkg=<manager>; ts=<yes|no>; ui=<library + version>; figma=<file key>; frame=<frame name>; files=<read>; gaps=<none|items>
```

---

## Phase 1 — Read Figma design

Follow the steps in the loaded Figma MCP skill. Key points for your projects:

- Call `get_design_context` with `depth: 3` for complex frames; `depth: 2` for simple ones.
- Treat the design context as a reference — do not copy Figma node IDs into production code.
- Map Figma auto-layout to CSS flex/grid; map Figma variants to component props.
- Match MUI component equivalents: Figma Card → MUI `Card`, Figma Button → MUI `Button`, etc.

---

## Phase 2 — Implement

Follow the loaded Figma MCP skill's implementation phase. Additional conventions:

- Place new components in the same directory as similar existing components.
- Use TypeScript interfaces for all props.
- Follow the existing component file structure (default export, named props type, co-located styles).
- Import from MUI or the project's UI library rather than adding new dependencies.
- Pass accessibility: add `aria-label` / `role` where the design uses icon-only buttons or decorative images.

---

## Phase 3 — Verify and complete

Run all available project checks:

1. Typecheck — `tsc --noEmit` or equivalent
2. Lint — `eslint` / `biome` / equivalent
3. Tests — if tests exist for nearby components, run them
4. Build — `npm run build` or equivalent (skip if slow and no structural changes were made)
5. Browser — verify the rendered output matches the Figma frame using Playwright or chrome-devtools

### Done when

- The implemented component or screen visually matches the Figma frame
- The code uses existing project components and theme tokens, not hardcoded values
- TypeScript, lint, and existing tests pass
- No new dependencies were added without user confirmation

> Emit compact handoff by default. Use the full `***HANDOFF BLOCK***` template below when required:

```
Handoff: figma-design-to-code; status=<completed|partial|blocked>; frame=<name>; changed=<files>; checks=<tsc/lint/tests/build/browser>; blockers=<none|items>; next=<summary>
```

---

## Rules

- Do not call `use_figma` without first loading the Figma MCP skill via `get_figma_skill`.
- Do not hardcode colors, spacing, or typography — map to theme tokens or CSS variables already in the project.
- Do not create a new component if an existing one covers the design.
- Do not add new npm dependencies without asking the user.
- Do not declare done without browser verification (or explicitly document why it was skipped).
- Always read the existing component directory before creating new files.

---

## Full block templates

Use these only when required by a downstream agent, an orchestrator, or explicit user request.

```
***CONTEXT BLOCK***
Skill/Agent : figma-design-to-code
Timestamp   : <ISO-8601 date>

### Project
- Type            : <React SPA | Next.js | unknown>
- Package manager : <npm | pnpm | yarn>
- TypeScript      : <yes | no>
- Monorepo        : <yes — tool: <name> | no>

### Runtime & tooling versions
- Node.js         : <version or "unknown">
- Framework       : <React + version>
- UI library      : <MUI vX | shadcn/ui | Radix | none | unknown>
- Build tool      : <Vite | CRA | Next.js | unknown>
- Test runner     : <vitest | jest | none | unknown>
- Linter          : <eslint | biome | none | unknown>

### Figma context
- File key / URL  : <key or URL>
- Frame / node    : <frame name or node ID>
- Figma skill URI : skill://figma/figma-design-to-code/SKILL.md

### Files read
- package.json
- <component directory path>
- <theme file path if found>

### Gaps / unknowns
- <description of gap or "none">
***END CONTEXT BLOCK***
```

```
***HANDOFF BLOCK***
Skill/Agent : figma-design-to-code
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Loaded Figma MCP design-to-code skill
- Read design context for frame: <frame name>
- Implemented component(s): <list>

### Artifacts produced
| File | Change |
|------|--------|
| <relative/path> | created / modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |
| browser   | ✅ passed / ❌ failed / ⚪ skipped — <reason> |

### Blocked items
- <item — reason — next: suggested action | "—">

### For the next agent or step
Figma frame <name> from file <key> implemented as React component(s) at <paths>. UI library: <name>. TypeScript: <yes|no>. All checks: <pass|partial>. Ready for review or further styling.
***END HANDOFF BLOCK***
```
