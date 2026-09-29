---
name: figma-generate-library
description: >
  Builds or updates a Figma design system by pushing React components, design tokens, and
  typography from the codebase into a Figma file via the Figma MCP. Use when asked to sync the
  codebase into Figma, create a Figma component library from existing React components, or keep
  a Figma design system in sync with the live codebase.
---

# Figma Generate Library

## What this skill does

Reads the project's React components, theme tokens, and design system, then pushes them into a Figma file as a structured component library. It works in the opposite direction of `figma-design-to-code`: code is the source of truth and Figma is the target.

This skill does NOT handle implementing Figma designs as code (use `figma-design-to-code`) or linking existing Figma components to their code counterparts (use `figma-code-connect`).

---

## When to use it

- Use when asked to create or update a Figma component library from the codebase
- Use when a designer needs a Figma file that reflects the current React components
- Use when setting up a design system in Figma from scratch, based on existing code

## Do not use when

- Do not use when the task is to implement a Figma design as code — use `figma-design-to-code`
- Do not use when the task is only to link code to existing Figma components — use `figma-code-connect`
- Do not use when no Figma file reference or target is available

---

## Inputs required

| Input | Source | Required |
|-------|--------|----------|
| Figma file URL or file key | User prompt | Yes |
| Component scope (all / specific list) | User prompt | No — defaults to all exported components |
| Target Figma page name | User prompt | No — defaults to "Components" |

---

## Phase 0 — Gather context

1. Load the full Figma MCP skill before starting any design work:

   ```
   get_figma_skill({ uri: "skill://figma/figma-generate-library/SKILL.md" })
   ```

   Follow the loaded skill as the primary workflow, with the house additions below.

2. Read `package.json` to detect: UI library (MUI, shadcn, Radix, none), TypeScript, package manager.
3. Locate the component directory. List all exported components.
4. Find the theme or design token file (search for `createTheme`, `tokens.ts`, CSS variables).
5. Emit context at end of this phase (see template below).

### House additions to Phase 0

- If the project uses MUI: extract palette keys, typography scale, spacing base, border radius, and shadows from `createTheme`.
- Prioritize components that are already documented in Storybook, if Storybook is present.
- Do not run parallel `use_figma` calls — the loaded Figma MCP skill enforces this rule.

> Emit compact context by default. Use the full `***CONTEXT BLOCK***` template below when required:

```
Context: figma-generate-library; <project type>; pkg=<manager>; ts=<yes|no>; ui=<library + version>; figma=<file key>; components=<count or list>; files=<read>; gaps=<none|items>
```

---

## Phase 1 — Discovery

Follow the loaded Figma MCP skill's Discovery phase. House additions:

- Read `src/index.ts` or the main export barrel to get the canonical component list.
- If Storybook exists, read the stories directory to discover documented props and variants.
- Build the component inventory before touching Figma: name, props, variants, status (stable/beta/deprecated).

---

## Phase 2 — Foundations

Follow the loaded Figma MCP skill's Foundations phase. House additions:

- Map MUI theme tokens to Figma styles:
  - `palette.primary.main` → Figma "Primary/Main" color style
  - `typography.h1`…`h6`, `body1`, `body2` → Figma text styles
  - `spacing(n)` base unit → Figma grid / spacing tokens
  - `shape.borderRadius` → Figma corner radius tokens
  - `shadows` array → Figma effect styles

---

## Phase 3 — Components

Follow the loaded Figma MCP skill's Components phase. House additions:

- Follow the project's component naming exactly — do not rename in Figma.
- Include all prop-driven variants as Figma variants in a component set.
- Mark deprecated components with a "Deprecated" prefix in Figma.

---

## Phase 4 — Complete

Run project checks (these verify code, not Figma output):

1. Typecheck — `tsc --noEmit` or equivalent
2. Lint — `eslint` / `biome` / equivalent (if any files were touched)

### Done when

- All target components exist in the Figma file as properly structured component sets
- Design tokens (colors, typography, spacing) are set up as Figma styles or variables
- No existing Figma components were deleted without user confirmation

> Emit compact handoff by default. Use the full `***HANDOFF BLOCK***` template below when required:

```
Handoff: figma-generate-library; status=<completed|partial|blocked>; figma=<file key>; components=<count>; checks=<tsc/lint>; blockers=<none|items>; next=<summary>
```

---

## Rules

- Do not call `use_figma` without first loading the Figma MCP skill via `get_figma_skill`.
- Do not run parallel `use_figma` calls — always sequential.
- Do not delete existing Figma components without user confirmation.
- Do not introduce new names that diverge from the codebase — Figma should mirror code names.
- Do not declare done without confirming the Figma file was updated.

---

## Full block templates

Use these only when required by a downstream agent, an orchestrator, or explicit user request.

```
***CONTEXT BLOCK***
Skill/Agent : figma-generate-library
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
- Target page     : <page name>
- Figma skill URI : skill://figma/figma-generate-library/SKILL.md

### Component inventory
- Components found : <count>
- Token file       : <path or "none">
- Storybook        : <yes | no>

### Files read
- package.json
- <component directory>
- <theme or token file>

### Gaps / unknowns
- <description of gap or "none">
***END CONTEXT BLOCK***
```

```
***HANDOFF BLOCK***
Skill/Agent : figma-generate-library
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Loaded Figma MCP generate-library skill
- Discovered <N> components and design tokens in the codebase
- Pushed components and foundations to Figma file: <file key>

### Artifacts produced
| File | Change |
|------|--------|
| Figma file <key> | created / updated — <N> components, <N> token styles |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |
| browser   | ⚪ n/a — Figma output verified via MCP |

### Blocked items
- <item — reason — next: suggested action | "—">

### For the next agent or step
Figma library in file <key> updated from codebase. UI library: <name>. Components pushed: <N>. Token styles: colors=<N>, typography=<N>, spacing=<N>. To link code to Figma components, run `figma-code-connect` next.
***END HANDOFF BLOCK***
```
