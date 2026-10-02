---
name: ticket-scope-extraction
description:
  Separates genuine scope from noise in a ticket, PBI, or User Story — in both
  directions. Use whenever ticket content is being READ (pulled via the issue-tickets
  MCP `pull_ticket`, in any agent or an ad-hoc session) or DRAFTED (a human's raw,
  rambling request for a new ticket) and it mixes the actual requirement with
  questions, off-topic asides, color-styled "thinking out loud" notes, meeting-note
  pastes, or unresolved "to be elaborated" markers. Classifies each unit of content
  as signal, flagged aside, open item, or noise, and never silently discards any of
  it.
---

# Ticket Scope Extraction

Real tickets mix the actual requirement with unanswered questions, color-styled caveats, meeting-notes pastes, and "to be elaborated" placeholders. Treated as one blob, an implementing agent either builds the noise into the acceptance criteria or silently drops something the author needed answered. This skill separates the two without discarding anything unrecoverably.

## When this applies

- **Reading**: after `pull_ticket` returns a ticket with non-empty `comments`, `flaggedAsides`, or `openItems`, or a `description` that reads as free-form prose with asides.
- **Drafting**: when a human's request for a new ticket mixes the real ask with a tangent, an aside, or an unresolved "we might also need X".
- **Skip** a ticket that already reads as clean, structured signal (see the negative-control few-shot example); do not over-trigger.

## Method

**Step 1 — render the input as tagged blocks.** Wrap the description and each
comment so every unit of content is individually addressable:

```xml
<ticket_discussion>
  <description>{{description text, with any mechanically-extracted flaggedAsides/openItems left in place as inline markers}}</description>
  <comment index="1" author="Jane" date="2026-08-03">Can we also support SSO while we're in here?</comment>
  <comment index="2" author="Bob" date="2026-08-04">Not for this ticket — keep the AC as written.</comment>
  <comment index="3" author="Jane" date="2026-08-05">lol fair, catching up on Monday's notes now</comment>
</ticket_discussion>
```

**Step 2 — classify every unit** into exactly one category. Read
[references/few-shot-examples.md](references/few-shot-examples.md) first — it
calibrates the boundary cases (a "question" that's actually scope-narrowing signal;
an author-flagged aside that must be surfaced, not discarded) far better than the
category names alone:

```xml
<scope_analysis>
  <signal source="comment:2">Explicitly narrows scope — SSO stays out.</signal>
  <noise source="comment:1" type="question">Unresolved off-topic ask, never turned into a requirement.</noise>
  <noise source="comment:3" type="color-text">Personal aside, no bearing on requirements.</noise>
  <flagged source="description:aside-1">{{an author-flagged aside — a caveat the author explicitly marked, e.g. via color-styled text}}</flagged>
  <open source="description:open-1">{{an explicit "to be elaborated" / TBD marker}}</open>
</scope_analysis>
```

Four categories, never more, never fewer:

| Category | Meaning | What happens to it |
|---|---|---|
| `signal` | A testable requirement or an explicit scope decision | Feeds Scope / Acceptance Criteria / triage |
| `flagged` | The author explicitly marked it as a caveat/aside (e.g. color-styled text) | Surfaced verbatim, never treated as firm scope, never discarded |
| `open` | An explicit unresolved marker ("to be elaborated", "TBD", "TODO") | Becomes a clarifying question or a stated assumption — never silently resolved either way |
| `noise` | Off-topic, resolved-elsewhere, or purely social content | Preserved in an appendix, never used to shape scope, never silently deleted |

**Step 3 — hand back the output contract** (below) to the calling agent or session.

## Output contract

Return, for the calling agent to consume:
- `signal_summary` — the `<signal>` and `<flagged>` content, ready to drop into a
  Scope/Context/Acceptance Criteria section.
- `open_questions` — the `<open>` content, one per unresolved marker.
- `filtered_noise` — the full `<noise>` list, each with its `type` and a one-line
  reason, for an audit appendix.

## Hard rule — tags are internal reasoning scaffolding, never persisted

`<ticket_discussion>`, `<scope_analysis>`, `<signal>`, `<noise>`, `<flagged>`,
`<open>` — and literal words like "signal"/"noise" used as labels — exist only to
help the model reason during classification. They must **never** appear in text
that gets posted anywhere: a `create_issue` description, a ticket comment, a PR
body, a commit message. Before `signal_summary`, `open_questions`, or
`filtered_noise` land in anything that leaves the calling agent, rewrite them as
plain natural-language markdown (prose or bullets) — indistinguishable from a
normally human-authored ticket. If what you are about to write contains an angle
bracket or the word "noise"/"signal" as a label, stop and rewrite it first.

## Suggested CLAUDE.md addition (optional, for a consuming project)

This repo doesn't own any consuming project's `CLAUDE.md`, so this isn't applied
automatically — copy it in if a project's developers pull tickets this way during
a dev cycle and want the distilled result to persist across sessions:

```markdown
## Ticket memory
When a ticket/PBI is pulled via `issue-tickets` and run through `ticket-scope-extraction`,
persist the distilled `signal_summary` (+ any `open_questions`) to memory as a
`project`-type memory keyed by ticket ID. Summarize by default; keep full context only
when the ticket's scope was genuinely contested or ambiguous (i.e. `filtered_noise`
was non-trivial). Never persist `filtered_noise` itself.
```

***CONTEXT BLOCK***
Skill      : ticket-scope-extraction
Ticket     : <source>#<id> (<title>)
Comments   : <n fetched>
Flagged    : <n author-flagged asides>
Open items : <n "to be elaborated" markers>
***END CONTEXT BLOCK***

***HANDOFF BLOCK***
Skill/Agent : ticket-scope-extraction
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Classified <n> units of ticket content into signal / flagged / open / noise.
- Source: <issue-tickets pull | human draft request>

### Artifacts produced
| Output | Content |
|---|---|
| signal_summary | <n> items — ready for Scope/Acceptance Criteria |
| open_questions | <n> items — unresolved, must be asked or flagged as an assumption |
| filtered_noise | <n> items — preserved in an audit appendix, never used for scope |

### Checks
| Check | Result |
|---|---|
| No XML tags/labels in any output destined for a posted ticket/PR | ✅ passed / ❌ failed |
| Every `open` item surfaced as a question or explicit assumption | ✅ passed / ❌ failed |

### Blocked items
- <anything ambiguous enough to need a human call, with reason | "—">

### For the next agent or step
<Which of signal_summary/open_questions/filtered_noise still need action, and where they should land>
***END HANDOFF BLOCK***
