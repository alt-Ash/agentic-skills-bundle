---
name: validation-loop
description:
  Bounded iterate-fix-reverify protocol for any domain-specific gate set —
  build/test/lint, `npm audit`, a browser console/network/snapshot recheck,
  or any other pass/fail check a caller supplies. Use whenever an agent needs
  to iterate until a set of checks pass rather than declaring success after
  the first pass. Defines gate-discovery convention, cheapest-first
  ordering, introduced-vs-pre-existing failure classification, a
  never-declare-done rule, and a uniform hard-stop template with a
  caller-supplied iteration cap — the caller always supplies its own
  concrete gates and how to fix a failure; this skill never invents either.
---

# Validation Loop

Every agent in this repo that fixes something eventually has to answer the
same question: how do I know I'm actually done? Four of them — implementing a
ticket, orchestrating a feature, patching a security finding, debugging a
browser bug — each answer it with their own slightly different prose, and two
of them never state a stopping condition at all, which means a stubborn
failure can loop forever instead of surfacing to a human. This skill is the
one shared protocol: bounded retries, before/after measurement, a strict rule
for what counts as "pre-existing" versus something the change just broke, and
a uniform hard-stop template. It supplies no gates and no fixes — the calling
agent already knows those; this skill only owns the loop shape around them.

## How to declare your gates

Before entering the loop, the caller states its own ordered gate list. If the
caller doesn't already know its gates (most agents implementing project code
changes do not), discover them:

- Read `package.json`'s `scripts` field, or `pyproject.toml` / `Makefile` /
  `go.mod` / `Cargo.toml` for other ecosystems.
- Read `AGENTS.md` / `CLAUDE.md` / `README.md` for documented commands.
- Typical gates, cheapest first: **lint/typecheck**, **build**, **tests**
  (full suite, then targeted tests for the changed area).
- **If a gate's command doesn't exist in the project, skip that gate. Never
  invent one.**

A caller with a fixed, non-discoverable gate (e.g. "re-run `npm audit --json`"
or "reload the page and recheck console/network/snapshot") skips discovery
and states its gate directly.

## The loop

1. **Run every gate in order, cheapest first.** Record the result of each.
2. **Classify every failure** as exactly one of:
   - **Introduced** — caused by the change being validated. Fix it, then
     re-run every gate from the top (a fix can regress an earlier gate).
   - **Pre-existing** — confirm this by re-running the *same* gate against
     the base branch's HEAD before accepting the label. Never accept
     "pre-existing" on the strength of a guess. Note it, do not fix it, do
     not let it block completion.
3. **Never declare `completed` until every gate has been re-run since the
   last fix**, with zero introduced failures remaining. A gate that passed
   before a later fix must be re-confirmed, not assumed still-passing.

## Hard-stop template

Stop iterating and surface to the user — do not keep retrying — the moment
**any** of these is true:

1. The same failure signature recurs after **2** identical fix attempts.
2. A required tool or dependency is missing from the environment.
3. The requirements/contract self-conflict (fixing one stated requirement
   breaks another).
4. More than **N** iterations have passed without convergence, where the
   caller supplies N:
   - **N = 10** for build/test/lint-shaped loops (implementing project code
     changes).
   - **N = 5** for higher-signal-per-cycle loops, where each iteration is an
     expensive, high-information re-check (a full `npm audit` re-run, a full
     browser reload-and-recheck) — non-convergence is diagnosable sooner in
     these loops than in a build/test/lint loop.

When stopping, report exactly which condition fired, the last failure output
verbatim, and what's been tried already — never a vague "still failing."

## Reporting

Emit results as the `### Checks` table already defined in
[`templates/HANDOFF-BLOCK.md`](../../../templates/HANDOFF-BLOCK.md) — one row
per gate, `✅ passed` / `❌ failed` / `⚪ n/a` — rather than inventing a new
report shape. A caller embedding this skill's result inside its own HANDOFF
BLOCK reuses that same table; it does not produce a second, differently
shaped one.

***CONTEXT BLOCK***
Skill      : validation-loop
Timestamp  : <ISO-8601 date>
Caller     : <agent/skill invoking this loop>
Gates      : <n> — <gate 1, gate 2, … in run order>
Iteration N: <caller-supplied cap>
***END CONTEXT BLOCK***

***HANDOFF BLOCK***
Skill/Agent : validation-loop
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Ran <n> gates over <m> iterations for <caller>.
- <k> introduced failures found and fixed; <j> pre-existing failures confirmed against base HEAD and left untouched.

### Artifacts produced
| Gate | Command | Final result |
|---|---|---|
| <gate-name> | <command, or "skipped — not present in project"> | ✅ / ❌ / ⚪ |

### Checks
| Check | Result |
|---|---|
| <gate 1> | ✅ passed / ❌ failed / ⚪ n/a |
| <gate 2> | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <hard-stop condition that fired, the exact failure output, and what's already been tried | "—">

### For the next agent or step
<Whether the caller can proceed, or what a human needs to resolve before it can>
***END HANDOFF BLOCK***
