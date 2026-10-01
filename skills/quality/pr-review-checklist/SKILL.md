---
name: pr-review-checklist
description:
  Reviews a pending code change (uncommitted diff, or a diff against a base branch)
  for Java/Spring Boot best practices and alignment with the originating
  ticket's scope, before a PR is opened. Use whenever a diff needs an advisory
  best-practice pass — never blocks, never edits, never runs a full security audit.
  Diff-first and token-efficient — reads the change, not the whole repo.
---

# PR Review Checklist

A PR review that reads every touched file in full is expensive and mostly reviews
code that didn't change. This skill reviews the diff itself, pulling in extra
context only where a hunk genuinely can't be judged without it, and checks the
change against two things a generic linter can't: the originating ticket's actual
scope, and Java/Spring Boot-specific pitfalls that compile and pass Checkstyle
cleanly but are still wrong.

## When this applies

- A calling agent or the user wants an advisory review of pending changes before a
  commit or PR — never as a blocking gate.
- Skip it for changes with no ticket and no meaningful diff (e.g. a single typo fix)
  — don't over-trigger on trivial changes.
- This is not a security audit. If a change touches auth, secrets handling, input
  validation from an untrusted boundary, or anything else security-load-bearing,
  flag it and recommend `@security-auditor` — do not attempt a deep security review
  here.

## Method — build context, then review

**Step 1 — diff first, not files first.** Run `git status` then `git diff` (and
`git diff --staged` if anything is staged). This is the primary source of truth for
what changed. Do not `Read` a file in full just because it appears in the diff.

**Step 2 — read further only when a hunk demands it.** Pull additional context with
targeted `Read` (using `offset`/`limit` for the surrounding lines) only when:
- the hunk changes a function signature and you need to see all call sites (use
  `grep` for the symbol name, not a full-file read of every caller)
- the diff is a few lines inside a much larger function and you need the rest of
  that function's body to judge correctness

**Step 3 — skip generated and low-signal content.** Never review, and never spend a
`Read` call on: generated build output (`target/`, `build/`), generated sources
(`target/generated-sources/`), IDE metadata, or vendored/generated directories.

**Step 4 — use `grep` for known-risky patterns** on touched files instead of
re-reading them line by line: raw `Map`/`List` without generics, `catch (Exception e) {}`
with an empty body, `e.printStackTrace()` left in non-debug code, `@SuppressWarnings`,
`TODO`/`FIXME` introduced by this diff.

**Step 5 — classify every finding** into exactly one of four categories:

| Category | Meaning |
|---|---|
| `scope` | The diff does or doesn't satisfy the ticket's acceptance criteria, or goes beyond ticket scope |
| `best-practice` | A Java/Spring Boot idiom or safety issue (see below) |
| `maintainability` | Naming, duplication, dead code, missing tests — not a bug, just costlier to live with |
| `flag-for-security` | Touches something security-load-bearing — do not deep-review it here, name it and recommend `@security-auditor` |

## Java/Spring Boot best-practice checks

- **Type safety**: raw (non-generic) `Map`/`List`/`Collection` usage, unchecked casts,
  overuse of `@SuppressWarnings("unchecked")` to silence a real type-safety gap,
  `instanceof` narrowing without a corresponding `else`/default case.
- **Async correctness**: a `CompletableFuture` created but never joined/composed (fire-and-forget
  where the result or exception matters), blocking calls (`Thread.sleep`, JDBC calls, blocking I/O)
  made on a reactive/WebFlux event-loop thread, `@Async` methods whose exceptions aren't handled via
  an `AsyncUncaughtExceptionHandler`, independent calls made sequentially that could run via
  `CompletableFuture.allOf(...)` instead.
- **Error handling**: empty `catch` blocks, `catch` blocks that swallow the original exception
  instead of rethrowing/wrapping it (losing the stack trace), catching `Exception`/`Throwable`
  broadly where a specific exception type is available, throwing a raw `RuntimeException` instead
  of a domain-specific exception type.
- **Module/bean hygiene**: a new `@Autowired` field/constructor that introduces a circular bean
  dependency, field injection (`@Autowired` on a field) introduced where the file's existing
  convention is constructor injection, inconsistent package structure relative to the project's
  layering convention.
- **Modern idioms**: `var` overuse where an explicit type materially aids readability, missing
  `final` on fields/parameters that are never reassigned, side-effecting `forEach` where a
  functional stream pipeline (`map`/`filter`/`collect`) would be clearer, string concatenation
  in a loop instead of `StringBuilder`/`String.join`, or `String.format` instead of a text block
  for multi-line messages.

## Severity taxonomy for the report

- **Blocking** — a `scope` finding that means acceptance criteria aren't met, or a
  `best-practice`/correctness finding that's an outright bug (not a style
  preference). "Blocking" here is a severity label for the human, not an actual gate
  — this skill and its calling agent are always advisory.
- **Should fix** — a genuine best-practice violation that isn't a live bug (e.g. an
  `any` leak, a swallowed error).
- **Nice to have** — maintainability/style findings.

## Output contract

Return, for the calling agent to present:
- `scope_findings` — ticket-alignment results (met / not met / out-of-scope additions).
- `blocking` / `should_fix` / `nice_to_have` — findings by severity, each with file,
  line range, and a one-line explanation of the concrete failure scenario (not just
  "this is bad practice").
- `security_flags` — anything recommended for `@security-auditor`, never reviewed
  in depth here.
- `looks_good` — a short list of things the diff does well, so the report isn't
  purely negative.

***CONTEXT BLOCK***
Skill        : pr-review-checklist
Diff scope   : <n files changed, n insertions/deletions>
Ticket       : <source>#<id> (<title>) | "none identified"
Base branch  : <branch diffed against, or "working tree only">
***END CONTEXT BLOCK***

***HANDOFF BLOCK***
Skill/Agent : pr-review-checklist
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Reviewed <n> changed files against ticket scope and Java/Spring Boot best practices.
- Ticket context: <pulled via issue-tickets | none available>

### Artifacts produced
| Output | Content |
|---|---|
| scope_findings | <n> items |
| blocking | <n> items |
| should_fix | <n> items |
| nice_to_have | <n> items |
| security_flags | <n> items — deferred to @security-auditor |

### Checks
| Check | Result |
|---|---|
| Diff read before any full-file reads | ✅ passed / ❌ failed |
| No file edited or git state mutated by this review | ✅ passed / ❌ failed |
| Security-load-bearing findings deferred, not deep-reviewed | ✅ passed / ❌ failed |

### Blocked items
- <anything that couldn't be assessed, with reason | "—">

### For the next agent or step
<Which findings need action before the PR, and whether @security-auditor should run>
***END HANDOFF BLOCK***
