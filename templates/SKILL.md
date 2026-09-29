# Skill Template

> Copy this file to `skills/<category>/<skill-name>/SKILL.md` and fill in every section.
> Delete this instruction line and any comment blocks (lines starting with `>`) before publishing.

---

```yaml
---
name: <skill-name>                     # kebab-case, matches the directory name
description: >
  <One sentence that describes what this skill does AND when to activate it.>
  Use when <specific trigger conditions — commands, user intents, file patterns>.
version: "1.0.0"
category: frontend | backend           # pick one
---
```

---

# \<Skill Title\>

## What this skill does

> 2–3 sentences. What problem it solves, what it produces, and what it does NOT handle.
> Be specific enough that an AI agent can decide whether to load this skill without reading further.

---

## When to use it

> List the specific conditions that should trigger this skill. Be precise.

- Use when asked to <action>
- Use when the project has <dependency/config/pattern>

## Do not use when

> List exclusions. Be explicit — prevents the agent from applying this skill in the wrong context.

- Do not use when <exclusion condition>
- Do not use when the project has <conflicting setup>

---

## Inputs required

> List everything this skill needs before it can start. Distinguish between what is read from
> files vs what must come from the user.

| Input | Source | Required |
|-------|--------|----------|
| <input name> | `package.json` / user prompt / `.nvmrc` | Yes / No |

---

## Phase 0 — Gather context

> This phase collects inputs and emits compact context. No work happens until this phase is complete.

1. Read `package.json` and note: <specific fields to check>
2. Check for <file or config>: <command or read instruction>
3. Ask the user if <gap condition>.

> At the end of this phase, emit compact context by default (see `templates/COMPACT-CONTEXT.md`). Use the full `templates/CONTEXT-BLOCK.md` only when a downstream parser, user request, or agent handoff requires it:

```
Context: <skill-name>; <project type>; pkg=<manager>; ts=<yes|no>; versions=<key versions>; files=<paths read>; gaps=<none|items>
```

---

## Phase 1 — \<First action phase title\>

> Describe what happens in this phase using numbered sub-steps.

### 1.1 — <Sub-step title>

<Instructions>

```bash
# Example command
<command>
```

### 1.2 — <Sub-step title>

<Instructions>

---

## Phase 2 — \<Second action phase title\>

> Repeat the pattern above for each phase.

---

## Phase N — Complete

> Final phase. Run checks and emit the HANDOFF BLOCK.

### Done when

> Define the observable conditions that mean this skill's work is finished.
> Be specific — these become the acceptance criteria for the implementing agent.

- <condition that must be true for the task to be considered complete>
- All available checks pass (tests, lint, typecheck, build)

### Checks

Run every available check in this order:

1. Tests — `<test command>`
2. Lint — `<lint command>`
3. Typecheck — `<typecheck command>`
4. Build — `<build command>`

For each failure, fix and re-run before continuing.

> Emit compact handoff by default (see `templates/COMPACT-HANDOFF.md`). Use the full `templates/HANDOFF-BLOCK.md` only when a downstream parser, user request, or agent handoff requires it:

```
Handoff: <skill-name>; status=<completed|partial|blocked>; changed=<files>; checks=<command: result>; blockers=<none|items>; next=<one sentence>
```

---

## Rules

> Non-negotiable constraints. Use imperative "Do not" / "Always" language.
> Keep this list short — if everything is a rule, nothing is.

- Do not <constraint>
- Always <constraint>
- Never <constraint>

---

## Templates and snippets

> All reusable code templates live here, not scattered through the phase steps.
> Steps reference them by name (e.g. "Use the vite.config template below").

### \<Template name\>

```<language>
<template code>
```

### \<Another template\>

```<language>
<template code>
```
