---
name: figma-code-connect
description: >
  Creates Figma Code Connect mappings that link Figma design components to their React code
  counterparts. Generates `.figma.ts` template files so designers see real component usage
  examples inside Figma's Dev Mode. Use when asked to set up Code Connect, link Figma components
  to code, or improve the design-to-code handoff experience in Figma Dev Mode.
---

# Figma Code Connect

## What this skill does

Creates `.figma.ts` parserless template files that map Figma component variants and properties to the corresponding React component props. After this skill runs, developers inspecting a Figma component in Dev Mode will see a live, correctly-configured usage example from the actual codebase.

This skill does NOT implement Figma designs as code (use `figma-design-to-code`) or push components into Figma (use `figma-generate-library`).

---

## When to use it

- Use when asked to set up Code Connect for Figma Dev Mode
- Use when the team wants designers and developers to see real component examples inside Figma
- Use when linking an existing component library to a Figma design system
- Use after running `figma-generate-library` to complete the code↔design bridge

## Do not use when

- Do not use when no Figma file is available or the project has no component library
- Do not use when the task is to implement a Figma design as code — use `figma-design-to-code`

---

## Inputs required

| Input | Source | Required |
|-------|--------|----------|
| Figma file URL or file key | User prompt | Yes |
| Component scope (all / specific list) | User prompt | No — defaults to all exported components |

---

## Phase 0 — Gather context

1. Load the full Figma MCP skill before starting:

   ```
   get_figma_skill({ uri: "skill://figma/figma-code-connect/SKILL.md" })
   ```

   Follow the loaded skill as the primary workflow, with the house additions below.

2. Read `package.json` to detect: UI library, TypeScript (required — Code Connect files are `.figma.ts`, not `.figma.tsx`), package manager.
3. List all exported components: read the barrel export file or the component directory.
4. Check if any `.figma.ts` files already exist — note their structure to stay consistent.
5. Emit context at end of this phase (see template below).

### House additions to Phase 0

- Code Connect files MUST be `.figma.ts` (not `.figma.tsx`) — the Figma parser requires this extension.
- Use `figma.code` tagged templates as shown in the loaded skill, not JSX syntax.
- Place each `.figma.ts` file alongside its component file, mirroring the project's co-location pattern.

> Emit compact context by default. Use the full `***CONTEXT BLOCK***` template below when required:

```
Context: figma-code-connect; <project type>; pkg=<manager>; ts=<yes|no>; ui=<library + version>; figma=<file key>; components=<count>; existing-connects=<count>; files=<read>; gaps=<none|items>
```

---

## Phase 1 — Map components

Follow the loaded Figma MCP skill's mapping steps. House additions:

- For MUI components: map Figma variant property values to MUI prop values (e.g. Figma `variant=Outlined` → MUI `variant="outlined"`).
- For boolean Figma properties: map to boolean React props (e.g. Figma `disabled=true` → `disabled={figma.boolean('disabled')}`).
- For instance swap properties (nested components): use `figma.instance('propertyName')`.
- For text content properties: use `figma.string('Label')`.

---

## Phase 2 — Generate `.figma.ts` files

Follow the loaded Figma MCP skill's file generation steps. Key rules:

- One `.figma.ts` file per Figma component (not per variant).
- File must export a default call to `figma.connect(Component, figmaUrl, { ... })`.
- Import the component using the project's existing import path — do not invent new paths.
- Do not include JSX in the template — use `figma.code` tagged templates only.

---

## Phase 3 — Publish and verify

Follow the loaded Figma MCP skill's publish step:

1. Run `npx figma connect publish` to push the mappings to Figma.
2. Open Figma Dev Mode and verify that a selected component shows the correct code snippet.

Run project checks:

1. Typecheck — `tsc --noEmit` (verifies `.figma.ts` files compile)
2. Lint — `eslint` / `biome` (if applicable)

### Done when

- A `.figma.ts` file exists for every target component
- `figma connect publish` ran without errors
- TypeScript compilation passes
- At least one component was verified in Figma Dev Mode

> Emit compact handoff by default. Use the full `***HANDOFF BLOCK***` template below when required:

```
Handoff: figma-code-connect; status=<completed|partial|blocked>; figma=<file key>; components=<count>; checks=<tsc/lint/publish>; blockers=<none|items>; next=<summary>
```

---

## Rules

- Do not call `use_figma` or the Figma MCP without first loading the skill via `get_figma_skill`.
- Always use `.figma.ts` extension — never `.figma.tsx`.
- Do not use JSX syntax in Code Connect templates — use `figma.code` tagged templates only.
- Do not invent Figma node IDs — read them from the file via the Figma MCP.
- Do not declare done without running `figma connect publish` and verifying the output.

---

## Full block templates

Use these only when required by a downstream agent, an orchestrator, or explicit user request.

```
***CONTEXT BLOCK***
Skill/Agent : figma-code-connect
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
- File key / URL     : <key or URL>
- Figma skill URI    : skill://figma/figma-code-connect/SKILL.md
- Existing connects  : <count or "none">

### Component inventory
- Components targeted : <count>
- Barrel export       : <path or "none">

### Files read
- package.json
- <component directory>
- <any existing .figma.ts files>

### Gaps / unknowns
- <description of gap or "none">
***END CONTEXT BLOCK***
```

```
***HANDOFF BLOCK***
Skill/Agent : figma-code-connect
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Loaded Figma MCP code-connect skill
- Generated `.figma.ts` files for <N> components
- Ran `figma connect publish` to push mappings

### Artifacts produced
| File | Change |
|------|--------|
| <relative/path>.figma.ts | created / modified — Code Connect for <ComponentName> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ⚪ n/a |
| browser   | ✅ verified in Figma Dev Mode / ❌ not verified / ⚪ skipped — <reason> |

### Blocked items
- <item — reason — next: suggested action | "—">

### For the next agent or step
Code Connect mappings published for <N> components in Figma file <key>. UI library: <name>. TypeScript: yes. All `.figma.ts` files co-located alongside components at <directory>. Designers can now see live code examples in Figma Dev Mode.
***END HANDOFF BLOCK***
```
