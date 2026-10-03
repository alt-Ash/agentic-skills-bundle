---
name: parallel-feature-build
description:
  Splits one feature into independent, file-scoped slices and builds them with
  parallel workers, each in its own isolated working tree. Each slice goes to
  the matching named specialist agent (e.g. spring-boot-backend-engineer) or to
  a general-purpose worker when none fits or is installed. Use when a feature or
  ticket clearly decomposes into two or more slices that do not touch the same
  files, no shared migration/schema ordering, and no shared config edits.
  Rejects the decomposition and falls back to sequential work when slices are
  not provably independent.
---

# Parallel Feature Build

Each independent slice goes to its **best-fit worker** (a named specialist, else `general-purpose`), briefed on its own slice only, all run at once.

## When this applies

- The feature/ticket (or OpenSpec task list) reads as ≥2 distinct chunks of work.
- Load this skill **before** delegating; if parallel mode is unsafe, go sequential (e.g. `@issue-implementer`, one slice at a time).
- A task that is one specialist's whole job (security audit, UX audit, browser bug) goes straight to that specialist.

## Phase 0 — Decomposition safety gate

Safe only if **all** hold:

1. **Disjoint files** across every pair of slices.
2. **No ordering dependency**: no migration, schema change or generated artifact must land before another slice starts.
3. **No shared-config collision**: no two slices edit the same shared file (`pom.xml`, `build.gradle(.kts)`, shared types or config).

Diff every pair of file lists up front. **Any overlap → reject parallel mode**; go sequential, still routed per "Worker selection".

## Worker selection

Pick a worker **per slice** from agents on the host's agent list (never name an absent one):

| Slice is mostly… | Worker |
|---|---|
| Spring Boot controllers, services, repositories, entities, config, plus JUnit tests | `spring-boot-backend-engineer` |
| React / Next.js components, hooks, stories | `react-frontend-engineer` (`mui-frontend-engineer` if MUI) |
| Applying a security audit's findings | `security-implementor` |
| Mechanical, new-file-only slice with a precise spec (DTOs, entities, mappers, boilerplate, tests from a clear spec) | `local-slice-worker` (local model via the `local-codegen` MCP server) |
| Anything else, **or** the specialist is unavailable | `general-purpose` |

- **Specialist when one fits, generic when not**; a missing specialist means `general-purpose`, never a refusal. Note fallbacks in the HANDOFF.
- **Local slices are opt-in and narrow.** Use `local-slice-worker` only when every file is new and the spec leaves no design decision open. Edits to existing files, shared config or judgement go to the specialist. If it is absent or returns `Status: blocked`, reassign the slice to the specialist that would otherwise have taken it, with the same gate brief, and note why. Local slices share one GPU: at most 2 at once.

## Worker briefing

Each worker's prompt is self-contained:

- Its slice of the spec, with the complete list of files it may create or edit.
- The upstream CONTEXT BLOCK (format below) and an instruction to follow the project's conventions.
- The project's gate list (from its `CLAUDE.md` "Validation gates") and an instruction to load `validation-loop` and run them. Its HANDOFF lists which gates ran, with results.
- Workers do **NOT** commit unless the user's request or the brief says so; invoking this skill is not commit permission. Workers return a bundle: worktree path, files, diff, gates.

The orchestrator reviews each bundle, fixes small things itself if they fit its context, or hands further work to new parallel workers. A blocked worker holds up only its own slice.

## Gate enforcement

- Before accepting a slice, the orchestrator re-runs the cheapest gate (compile) itself in its worktree and checks `git status` shows only allowed files.
- A slice with no gate result, or one the orchestrator cannot reproduce, fails.
- The same rule applies when Claude builds a slice inline.
- The `local-codegen` MCP runs no gates by design.
- The merged-tree `validation-loop` still runs once.

## Plan-vs-diff review (local slices only)

Compiling does not show the local model built what was asked. Review each completed `local-slice-worker` slice against its spec **before** merging.

1. **Spawn `pr-reviewer`** (read-only; reviews may run concurrently). Brief it with:
   - the worktree path and base branch;
   - the spec and allowed-files list as the **scope baseline**: skip the ticket lookup, report alignment item by item;
   - the files created. They are untracked, so `git diff` shows nothing: have it read them directly and `git status` to confirm nothing else changed. Any other file is outside the allowed list and must be reported;
   - the "Scope alignment" format: a numbered list with one line per spec requirement (split compound items), each `MET`, `MISSING` or `CONTRADICTED` with file and line evidence, then an explicit statement of any file or behaviour added beyond the spec. For each requirement, also have it state the behaviour on null, empty and boundary inputs, and mark `CONTRADICTED` any requirement that fails for an input the spec allows (e.g. a nullable field). This wording caught every seeded deviation in testing (a dropped id copy, a missing not-null, an extra file) without false positives.
2. **Verdict.** The reviewer is advisory, so you decide. Fail on any Blocking finding, missing or contradicted spec item, file outside the allowed list, or unrequested behaviour. Should-fix findings are only recorded. Surface security flags.
3. **On failure, one retry** with the findings appended to the spec (clean slice state), then review once more. A second failure reassigns the slice per the fallback rule.
4. **Never hand-fix local output** to get a pass.

## Worktree mechanics

Each worker edits in its own git worktree. **The host or you create it, never both.**

**Claude Code.** Spawn each worker with `Agent`, `isolation: "worktree"` and the chosen `subagent_type`: one call per slice, **all in the same turn**. Claude Code creates and cleans up the worktree:
- Do **not** run `git worktree add` or name a path or branch.
- A worker with changes returns `worktreePath` and `worktreeBranch`; record both.
- Worktrees branch from the default branch unless `worktree.baseRef` is `"head"`; confirm it for unpushed or non-default work.

**Other hosts.** Create one worktree per slice before spawning:

```bash
git worktree add .worktrees/<feature-slug>/slice-<n> -b feature/<feature-slug>/slice-<n>
```

Give each worker its absolute worktree path as working directory.

**Merge.** Once every slice is accepted, integrate in slice-declared order on one branch:

```bash
git checkout -b <feature-slug>-integration <base>
```

- Slice committed: `git merge --no-ff <slice-branch>` (`worktreeBranch`, or `feature/<feature-slug>/slice-<n>`).
- Slice uncommitted (default): copy its new files and `git -C <worktree> diff | git apply` its edits, then check `git status` shows only allowed files. Commit only if the user asked.

A conflict is a Phase 0 false negative: stop, never auto-resolve, tell the user.

**Cleanup.** After merged-tree validation is `completed` and each slice is applied, remove its worktree and branch: `git worktree remove --force <path>` and `git branch -D <branch>` for uncommitted slices (never before applying), plain `remove` and `-d` for committed ones.

## Merge and validation

After merging (a slice can pass alone and still break the integration):

1. Diff each HANDOFF's "Artifacts produced" lists; a shared file is a real conflict, surface it.
2. Load `validation-loop` against the integration branch, exactly once.
3. The caller's remaining steps run once, on the merged result.

## Output contract

- `decomposition_verdict`: `parallel` or `sequential-fallback` (with the overlap).
- `slice_results`: each HANDOFF BLOCK by slice, worker type, fallback or not.
- `local_slice_reviews`: per local slice, `pass`, `pass-after-retry` or `reassigned`, plus findings.
- `merge_conflicts`: files touched by more than one slice (empty if none).

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
- Phase 0 gate: <parallel | sequential-fallback>.
- Workers: <slice -> type; fallbacks and why>.

### Artifacts produced
| Slice | Worker | Files | Result |
|---|---|---|---|
| <slice-name> | <agent type> | <file list> | <worker HANDOFF status> |

### Checks
| Check | Result |
|---|---|
| Phase 0 gate | ✅ passed / ❌ overlap found |
| Slice gates reproduced | ✅ passed / ❌ failed |
| Local slice plan-vs-diff review | ✅ passed / ❌ failed / ⚪ no local slices |
| Merged-tree build | ✅ passed / ❌ failed / ⚪ n/a |
| Merged-tree tests | ✅ passed / ❌ failed / ⚪ n/a |
| Cross-slice artifact overlap check | ✅ none found / ❌ conflicts found |

### Blocked items
- <blocked slice, reason, next action | "—">

### For the next agent or step
<Whether the merged result is ready for the caller's next phases; which slice needs a retry>
***END HANDOFF BLOCK***
