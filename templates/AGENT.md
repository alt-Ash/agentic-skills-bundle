# Agent Template

> Copy this file to `agents/<agent-name>.md` and fill in every section.
> Delete this instruction line and any comment blocks (lines starting with `>`) before publishing.

---

```yaml
---
description: >
  <One sentence: what this agent does and when to invoke it.>
  Invoke this agent when <specific trigger — task type, symptom, user intent>.
mode: primary
temperature: 0.1   # 0.1 for deterministic/debugging agents; 0.2 for creative/UI agents
color: "#XXXXXX"   # hex color for visual identification in agent lists
permission:
  edit: allow
  write: allow
  bash: allow
---
```

---

## Identity

> One paragraph. Who is this agent? What is its primary expertise? What source of truth does it
> use? What does it NEVER do?

---

## Core principles

> 4–6 bullet points. The non-negotiable operating rules that shape every decision this agent makes.
> These are different from the "Rules you must never break" section — these are the values/mindset.

- **<Principle name>.** <One sentence explanation.>

---

## Consumes

> Which skill or agent output this agent reads as input. Use "—" if none.

| Source | What it reads |
|--------|---------------|
| `<skill-name>` skill | CONTEXT BLOCK + <specific artifacts> |
| `<agent-name>` agent | HANDOFF BLOCK + `<file path>` |

---

## Produces

> What this agent emits. List all artifacts.

| Artifact | Description |
|----------|-------------|
| HANDOFF BLOCK | Emitted in conversation at end of execution |
| `<file path>` | Written to disk — <description> |

---

## Tools required

> List every MCP, CLI tool, or capability this agent depends on. Flag any that are optional.

| Tool | Required | Purpose |
|------|----------|---------|
| `chrome-devtools` MCP | Yes | Browser inspection |
| `playwright` MCP | Yes | Navigation and screenshots |
| `gh` CLI | Optional | PR creation |

---

## Workflow

Follow these phases in order.

---

### Phase 0 — Gather context

> Ask the user for required inputs. Read project files. Emit the CONTEXT BLOCK before Phase 1.

#### 0.1 — \<Sub-step\>

<Instructions>

#### 0.2 — Emit CONTEXT BLOCK

After collecting all context, emit:

```
***CONTEXT BLOCK***
Skill/Agent : <agent-name>
Timestamp   : <ISO-8601 date>
...fill all fields...
***END CONTEXT BLOCK***
```

---

### Phase 1 — \<First action phase\>

> Describe what happens with numbered sub-steps.

#### 1.1 — \<Sub-step\>

<Instructions>

---

### Phase 2 — \<Second action phase\>

> Repeat the pattern above.

---

### Phase N — Output report and handoff

After all work is complete:

1. Verify all "Done when" conditions are met.
2. Run all available checks (tests, lint, typecheck, build).
3. Fix any failures before continuing.
4. Emit the HANDOFF BLOCK:

```
***HANDOFF BLOCK***
Skill/Agent : <agent-name>
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- <action>

### Artifacts produced
| File | Change |
|------|--------|
| <path> | created / modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |
| browser   | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <item or "—">

### For the next agent or step
<summary paragraph>
***END HANDOFF BLOCK***
```

---

## Rules you must never break

> Hard stops. Use "Do not", "Never", "Always" language. Keep the list to the most critical items.
> Anything in this list should cause the agent to stop work if violated.

- Do not write production code before Phase 0 is complete.
- Do not declare done while any check is failing.
- Do not declare done until all "Done when" conditions in the active skill or agent are met.
- Do not invent file paths, component names, or API shapes — read them from source or ask.
- Do not ask multiple questions at once — one question, then wait.
- Never commit or push while on `main` or `master` (if this agent does git work).
- <add agent-specific rules>
