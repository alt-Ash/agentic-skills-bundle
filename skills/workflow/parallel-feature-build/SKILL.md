---
name: parallel-feature-build
description:
  Splits one feature into independent, file-scoped slices and builds them with
  parallel workers that have no fixed specialist role — each worker gets a
  self-contained brief and produces its slice on its own, isolated working
  tree where the host tool supports it. Use when a feature or ticket clearly
  decomposes into two or more slices that do not touch the same files, no
  shared migration/schema ordering, and no shared config edits — as an
  addition to (not a replacement for) routing role-specific work (a security
  fix, a dependency migration, a runtime bug) to a named specialist agent. Rejects the
  decomposition and falls back to sequential work when slices are not
  provably independent.
---

# Parallel Feature Build

Named specialists (`@spring-boot-backend-engineer`, `@security-auditor`, …) are the
right call when a task genuinely needs a fixed expertise. But a single feature
that splits cleanly into independent pieces — a new REST endpoint plus its
corresponding service/repository layer, three unrelated components in the same PR, a config
migration touching disjoint files — doesn't need N different personas. It
needs N *interchangeable* workers, each briefed on its own slice, running at
the same time instead of one after another. This skill defines that mechanic:
how to decide a decomposition is actually safe to parallelize, how to brief a
generic worker so it needs nothing else, and how to merge and validate the
result exactly once.

## When this applies

- The user's feature/ticket (or its OpenSpec task list, if one was produced)
  already reads as ≥2 distinct chunks of work.
- Load this skill **before** delegating implementation — it gates whether
  parallel mode is safe; if it isn't, implementation proceeds sequentially as
  normal (e.g. via `@issue-implementer`, one slice at a time).
- Do not use this skill for a task that needs a named specialist's judgment
  throughout (a security review, a UX audit, a browser-observable bug) — those
  stay routed to the specialist, whether or not the surrounding feature is
  otherwise being parallelized.

## Phase 0 — Decomposition safety gate

A decomposition is safe to parallelize only if **all** of the following hold:

1. **Disjoint files.** Every slice's file list (from the OpenSpec task list, or
   asked for explicitly) is pairwise non-overlapping with every other slice's.
2. **No ordering dependency.** No slice's database migration, schema change, or
   generated artifact must land before another slice's work can start.
3. **No shared-config collision.** No two slices edit the same shared file —
   `package.json`, a shared types file, a shared config — even if the rest of
   their file lists are disjoint.

Compute the file list per slice up front and diff every pair. **Any overlap on
any of the three checks → reject parallel mode entirely** and fall back to
implementing the slices sequentially, one at a time, still without a fixed
persona per slice (a generic worker, not a named specialist, unless a slice
itself needs one).

## Worker briefing

Each worker's prompt is self-contained and carries no persona:

- Its own slice of the spec — only the part relevant to its files.
- The CONTEXT BLOCK already produced upstream (reuse
  [`templates/CONTEXT-BLOCK.md`](../../../templates/CONTEXT-BLOCK.md)
  verbatim — do not invent a new context format).
- An explicit instruction to follow the project's existing conventions rather
  than a specialist's house style, since no specialist is assigned.

Each worker returns a HANDOFF BLOCK (reuse
[`templates/HANDOFF-BLOCK.md`](../../../templates/HANDOFF-BLOCK.md) verbatim).
A worker returning `Status: blocked` only holds up its own slice — the other
workers' results still land, and the blocked slice alone gets retried or
escalated.

## Worktree mechanics

**Naming.** For a feature slug `<feature-slug>` (kebab-case; take it from
upstream context — e.g. `ticket-scope-extraction` output or the ticket title
— or derive it by kebab-casing the first few words of the feature
description if nothing upstream supplies one) and slices numbered `<n>` in
the order they were declared during Phase 0:
- Worktree path: `.worktrees/<feature-slug>/slice-<n>`
- Branch: `feature/<feature-slug>/slice-<n>`

**Setup (before spawning any worker).** Create all N worktrees up front:

```bash
git worktree add .worktrees/<feature-slug>/slice-<n> -b feature/<feature-slug>/slice-<n>
```

Run this once per slice. Then brief each worker with its absolute worktree
path as its required working directory — add this as a field alongside the
CONTEXT BLOCK in "Worker briefing" above.

**Execution.** On Claude Code, spawn each worker via the `Agent` tool with a
generic `subagent_type` (not a named specialist) and `isolation: "worktree"`
— one call per slice, in parallel; the `Workflow` tool's
`parallel()`/`pipeline()` helpers are the right fit once there are more than
a couple of slices. On a host tool without a worktree-isolation primitive,
the orchestrator itself runs the `git worktree add` above before each
generic subagent call, and the subagent works inside that path — the same
"no fixed role, brief = slice + context + worktree path, single merge point"
contract either way, just without true concurrency.

**Merge.** Once every worker returns `Status: completed`, merge in
slice-declared order into one integration branch:

```bash
git checkout -b <feature-slug>-integration <base>
git merge --no-ff feature/<feature-slug>/slice-1
git merge --no-ff feature/<feature-slug>/slice-2
# ...one merge per slice, in order
```

Because Phase 0 already guarantees every slice's files are disjoint, each
merge should be conflict-free by construction. **If a merge still reports a
conflict, that is a Phase 0 false negative, not a normal merge conflict** —
stop, do not auto-resolve, surface the conflicting files to the user as a
decomposition-safety failure that needs re-diagnosis.

**Cleanup.** Only after the merged-tree validation below reports
`completed`:

```bash
git worktree remove .worktrees/<feature-slug>/slice-<n>
git branch -d feature/<feature-slug>/slice-<n>
```

per slice. If validation fails and a fix is still needed, leave the relevant
worktree/branch alive for the retry — do not clean up mid-loop.

## Merge and validation

Never validate per-worker — a single slice can pass its own build while
still breaking the integration. After merging:

1. Diff each worker's HANDOFF BLOCK "Artifacts produced" list against the
   others. Any two workers touching the same file despite a clean Phase 0
   check is a real conflict — surface it to the user to resolve manually,
   never auto-merge it.
2. Load the `validation-loop` skill against the integration branch, exactly
   once, for the combined build/test/lint pass — not described inline here a
   second time.
3. Any remaining validation/security/PR steps in the calling orchestration
   (e.g. `dev-orchestrator`'s Phase 5/5b/6) run once, against the merged result,
   exactly as they would for a single sequential implementation.

## Output contract

Return, for the calling orchestration to consume:
- `decomposition_verdict` — `parallel` or `sequential-fallback`, with the
  specific overlap that triggered a fallback, if any.
- `slice_results` — each worker's HANDOFF BLOCK, keyed by slice.
- `merge_conflicts` — any file touched by more than one slice's actual
  artifacts, even after a clean Phase 0 check (empty if none).

***CONTEXT BLOCK***
Skill        : parallel-feature-build
Timestamp    : <ISO-8601 date>
Feature      : <one-line description of the feature/ticket>
Slices       : <n> — <one-line description each>
File lists   : <slice -> file list, as gathered for the Phase 0 gate>
***END CONTEXT BLOCK***

***HANDOFF BLOCK***
Skill/Agent : parallel-feature-build
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Ran the Phase 0 decomposition gate on <n> proposed slices.
- Verdict: <parallel | sequential-fallback> — <reason if fallback>.
- Spawned <n> generic workers <in parallel via worktree isolation | sequentially>.

### Artifacts produced
| Slice | Files | Result |
|---|---|---|
| <slice-name> | <file list> | <worker HANDOFF status> |

### Checks
| Check | Result |
|---|---|
| Phase 0 decomposition gate | ✅ passed / ❌ overlap found |
| Merged-tree build | ✅ passed / ❌ failed / ⚪ n/a |
| Merged-tree tests | ✅ passed / ❌ failed / ⚪ n/a |
| Cross-slice artifact overlap check | ✅ none found / ❌ conflicts found |

### Blocked items
- <any slice that returned Status: blocked, with reason and suggested next action | "—">

### For the next agent or step
<Whether the merged result is ready for the calling orchestration's own validation/security-gate/PR phases, and which slice (if any) still needs a retry>
***END HANDOFF BLOCK***
