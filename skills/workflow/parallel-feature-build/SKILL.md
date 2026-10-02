---
name: parallel-feature-build
description:
  Splits one feature into independent, file-scoped slices and builds them with
  parallel workers, each in its own isolated working tree. Every slice is routed
  to the matching named specialist agent when one is available (e.g. a Spring
  Boot slice to the spring-boot-backend-engineer) and to a general-purpose
  worker when no specialist fits or none is installed. Use when a feature or
  ticket clearly decomposes into two or more slices that do not touch the same
  files, no shared migration/schema ordering, and no shared config edits.
  Rejects the decomposition and falls back to sequential work when slices are
  not provably independent.
---

# Parallel Feature Build

A feature that splits cleanly into independent pieces (an endpoint plus its service/repository layer, three unrelated components, a config migration touching disjoint files) need not be built one slice after another. Each slice goes to its **best-fit worker** (a named specialist where one exists, `general-purpose` otherwise), briefed on its own slice only, and all run at once. This skill covers deciding a decomposition is safe, picking and briefing each worker, and merging and validating once.

## When this applies

- The feature/ticket (or its OpenSpec task list) already reads as ≥2 distinct chunks of work.
- Load this skill **before** delegating implementation: it gates whether parallel mode is safe; if not, implement sequentially as normal (e.g. via `@issue-implementer`, one slice at a time).
- A task that is one specialist's whole job (security audit, UX audit, browser-observable bug) goes straight to that specialist, though it may still be *one slice* of a larger parallel feature.

## Phase 0 — Decomposition safety gate

A decomposition is safe to parallelize only if **all** of the following hold:

1. **Disjoint files.** Every slice's file list (from the OpenSpec task list, or
   asked for explicitly) is pairwise non-overlapping with every other slice's.
2. **No ordering dependency.** No slice's database migration, schema change, or
   generated artifact must land before another slice's work can start.
3. **No shared-config collision.** No two slices edit the same shared file —
   `pom.xml` / `build.gradle(.kts)`, a shared types file, a shared config — even if the rest of
   their file lists are disjoint.

Compute each slice's file list up front and diff every pair. **Any overlap on any check → reject parallel mode entirely** and implement the slices sequentially, each still routed per "Worker selection".

## Worker selection

Pick a worker **per slice** from the agents actually available in this environment (the host's agent list; never name an agent that is not on it):

| Slice is mostly… | Worker |
|---|---|
| Spring Boot controllers, services, repositories, entities, config, plus their JUnit tests | `spring-boot-backend-engineer` |
| React / Next.js components, hooks, stories | `react-frontend-engineer` (`mui-frontend-engineer` if the UI library is MUI) |
| Applying a security audit's findings | `security-implementor` |
| Anything else, **or** the matching specialist is not available | `general-purpose` |

Rules:
- **Specialist when one fits, generic when not.** A slice no specialist covers (documentation, a CI workflow, a data file) goes to `general-purpose`; that is a normal outcome.
- **Missing specialist → `general-purpose`, never a refusal.** Use it, still in parallel, and note the fallback in the HANDOFF BLOCK. Do not drop the slice or go sequential just because a specialist is absent.
- **One worker per slice**, chosen independently; one feature often mixes specialists and generic workers.

## Worker briefing

Each worker's prompt is self-contained:

- Its own slice of the spec, with the complete list of files it may create or edit.
- The upstream CONTEXT BLOCK in the standard format (see the block at the end of this file); do not invent a new one.
- An instruction to follow the project's existing conventions (a specialist also applies its own expertise).
- An instruction to **commit its finished work** on its worktree branch, so the merge has something to merge.

Each worker returns a HANDOFF BLOCK in the standard format (end of this file). A `Status: blocked` worker holds up only its own slice; the others' results still land and the blocked slice alone is retried or escalated.

## Worktree mechanics

Each worker edits in its own git worktree so parallel edits cannot collide.
**Who creates the worktree depends on the host — never do both.**

**Claude Code (has a worktree-isolation primitive).** Spawn each worker with the `Agent` tool, `isolation: "worktree"`, and the `subagent_type` from "Worker selection": one call per slice, **all in the same assistant turn** so they run concurrently. Claude Code creates, names and cleans up the worktree itself:
- Do **not** run `git worktree add` yourself (it makes a second, unused worktree and an empty branch to merge), and do not tell a worker a path or branch name.
- A worker that made changes returns `worktreePath` and `worktreeBranch`; record these per slice, since they are what you merge.
- Worktrees branch from the default branch unless the `worktree.baseRef` setting is `"head"`; if the feature builds on unpushed or non-default-branch work, confirm `baseRef` is `"head"` first.

**Hosts without a worktree-isolation primitive.** Create one worktree per slice before spawning, numbered in Phase 0 order (`<feature-slug>` is kebab-case from upstream context or the first words of the feature description):

```bash
git worktree add .worktrees/<feature-slug>/slice-<n> -b feature/<feature-slug>/slice-<n>
```

Brief each worker with its absolute worktree path as its required working directory. Otherwise the contract is identical, minus true concurrency.

**Merge.** Once every worker returns `Status: completed`, merge in
slice-declared order into one integration branch, using the branch each worker
reported (Claude Code: `worktreeBranch`; other hosts: `feature/<feature-slug>/slice-<n>`):

```bash
git checkout -b <feature-slug>-integration <base>
git merge --no-ff <slice-1-branch>
git merge --no-ff <slice-2-branch>
# ...one merge per slice, in order
```

Phase 0 guarantees disjoint files, so each merge should be conflict-free. **A conflict is a Phase 0 false negative, not a normal merge conflict**: stop, do not auto-resolve, and surface the conflicting files to the user as a decomposition-safety failure needing re-diagnosis.

**Cleanup.** Only after the merged-tree validation below reports `completed`, remove each slice's worktree and branch (`git worktree remove <path>`, `git branch -d <branch>`). If a fix is still needed, keep the relevant worktree/branch for the retry.

## Merge and validation

Never validate per-worker: a slice can pass its own build and still break the integration. After merging:

1. Diff each worker's HANDOFF BLOCK "Artifacts produced" list against the others. Two workers touching the same file despite a clean Phase 0 is a real conflict; surface it to the user, never auto-merge.
2. Load the `validation-loop` skill against the integration branch, exactly once, for the combined build/test/lint pass.
3. Any remaining validation/security/PR steps in the calling orchestration
   (e.g. `dev-orchestrator`'s Phase 5/5b/6) run once, against the merged result,
   exactly as they would for a single sequential implementation.

## Output contract

Return, for the calling orchestration to consume:
- `decomposition_verdict` — `parallel` or `sequential-fallback`, with the
  specific overlap that triggered a fallback, if any.
- `slice_results` — each worker's HANDOFF BLOCK, keyed by slice, with the worker
  type used (`spring-boot-backend-engineer`, `general-purpose`, …) and whether it
  was a fallback because the specialist was unavailable.
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
- Spawned <n> workers <in parallel via worktree isolation | sequentially>: <slice -> worker type; note any general-purpose fallback and why>.

### Artifacts produced
| Slice | Worker | Files | Result |
|---|---|---|---|
| <slice-name> | <agent type> | <file list> | <worker HANDOFF status> |

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
