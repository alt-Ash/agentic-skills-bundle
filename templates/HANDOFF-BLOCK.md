# HANDOFF BLOCK — Reference Template

The **HANDOFF BLOCK** is the normalized output emitted at the end of every skill or agent
execution. It replaces ad-hoc conversational summaries and inconsistent report formats with a
single typed structure that:

- Any downstream agent can parse without re-reading the full conversation.
- A human can scan in under 30 seconds to understand what happened.
- A CI/orchestration layer can consume as a machine-readable status.

---

## How to emit it

Emit the block **in the conversation** at the very end of execution, after all work is done and
all checks have passed (or are explicitly noted as failing). Use the exact fences shown below.

For skills/agents that also write a file artifact (e.g. `ux-auditor` writes `UX-AUDIT.md`),
embed the HANDOFF BLOCK at the **top of that file** AND emit it in the conversation.

---

## Template

```
***HANDOFF BLOCK***
Skill/Agent : <skill-name or agent-name>
Timestamp   : <ISO-8601 date>
Status      : [completed | partial | blocked]

### What was done
<!-- Bullet list. One line per meaningful action. Use past tense. -->
- <action taken>

### Artifacts produced
<!-- Every file created or modified. Use "—" if none. -->
| File | Change |
|------|--------|
| <relative/path> | created / modified / deleted — <one-line description> |

### Checks
<!-- Every check that was run. -->
| Check      | Result                        |
|------------|-------------------------------|
| tests      | ✅ passed / ❌ failed / ⚪ n/a |
| lint       | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck  | ✅ passed / ❌ failed / ⚪ n/a |
| build      | ✅ passed / ❌ failed / ⚪ n/a |
| browser    | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
<!-- Anything unresolved. Include the reason and the suggested next action. Use "—" if none. -->
- <item> — <reason> — next: <suggested action>

### For the next agent or step
<!-- One short paragraph. Everything a downstream agent or human needs to continue without
     re-reading this session. Include relevant file paths, versions found, and any decisions made. -->
<summary paragraph>
***END HANDOFF BLOCK***
```

---

## Status definitions

| Status | Meaning |
|--------|---------|
| `completed` | All planned work is done, all checks pass, no blockers. |
| `partial` | Core work is done but some checks or steps were skipped or non-critical items are open. |
| `blocked` | Work stopped because of a blocker that requires user input or external action. |

---

## Rules

- **Always emit before saying "done".** No session ends without a HANDOFF BLOCK.
- **Status `blocked` requires an explanation.** Do not mark blocked without listing what is
  blocking and what the next step is.
- **Checks section is mandatory.** If a check was not run, mark it `⚪ n/a` with a note.
- **"For the next agent" paragraph must be self-contained.** A downstream agent reading only
  that paragraph must have enough context to pick up where you left off.
- **Do not abbreviate.** If ten files were modified, list all ten.
- **Inherit the CONTEXT BLOCK.** The "For the next agent" paragraph should re-state the key
  facts from the CONTEXT BLOCK that are still relevant downstream.
