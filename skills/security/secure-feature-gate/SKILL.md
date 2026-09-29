---
name: secure-feature-gate
description:
  Runs a fast, diff-scoped OWASP Top 10:2025 pattern check on the current
  change set every time a feature is touched or created, and blocks on
  Critical/High findings unless explicitly overridden. Use both on demand
  (manually, via /security-gate) and as a mandatory step inside dev-workflow
  agents before a PR is opened. Not a substitute for @security-auditor's full
  audit — this is a cheap, always-on gate, not a deep review.
---

# Secure Feature Gate

`@security-auditor` does a thorough, full-repo OWASP Top 10:2025 audit — npm
audit triage, a full grep sweep, and optional live exploitation. That's too
slow and heavy to run on every change. This skill runs the same catalog of
patterns, but scoped to only the lines actually changed in the current diff,
so it's cheap enough to run as a mandatory gate on every feature, not just on
request.

## When this applies

- Any time a calling agent is about to finish implementing a feature or fix
  and is heading toward opening a PR — this is a mandatory, blocking step, not
  an optional advisory pass like `pr-review-checklist`.
- On demand, via the `/security-gate` command, for a developer who wants a
  quick check before committing.
- Skip only when there is no diff to check (nothing staged or changed).

## Method

**Step 1 — get the diff scope.** Run `git status` then `git diff` (and `git
diff --staged`). If a base branch is known (PR target), pass it through.
This mirrors `pr-review-checklist`'s diff-first approach — do not read
unrelated files.

**Step 2 — run the scanner.**

```bash
bash scripts/diff-security-scan.sh [base-branch]
```

This checks only added/changed lines in touched `.ts`/`.tsx`/`.js`/`.jsx`
files against the same severity/OWASP/CWE catalog `@security-auditor` uses
(CORS wildcards, hardcoded JWT secrets, `Math.random()` for crypto, injection
patterns, missing auth guards, path traversal, SSRF, stack-trace leaks,
sensitive logging, weak bcrypt rounds — see
`agents/security-auditor.md`'s Step 4 catalog table for the authoritative
severity/OWASP/CWE mapping). This catalog is intentionally duplicated in
`scripts/diff-security-scan.sh` rather than shared, since skills and agents
install independently — keep the two in sync by hand if either changes.

**Step 3 — confirm every match.** The script's output is a list of
*candidates*, not confirmed findings — some patterns (e.g. `jwt.sign(`)
legitimately appear in safe code. Read each matched line in context (and the
surrounding function if the line alone isn't enough to judge) before deciding
whether it's a real finding. Never report a match as a finding without having
read it.

**Step 4 — classify and decide.**

| Verdict | Meaning |
|---|---|
| Clean | No confirmed Critical/High findings — proceed. |
| Blocking — fix inline | Confirmed Critical/High finding, small enough to fix directly as part of this change. |
| Blocking — escalate | Confirmed Critical/High finding too deep for a quick fix. Recommend `@security-auditor` → `@security-implementor`, using the same `security-handoff/v1` schema so findings aren't re-litigated. |
| Non-blocking | Only Moderate/Low findings — report them but do not block. |

**Step 5 — override path (only if the calling agent/user insists on
proceeding with an open Blocking finding).** Never silently drop a blocking
finding. Require the human to type a short justification (e.g. "accepted
risk, ticket JIRA-123"), and record it verbatim in the HANDOFF BLOCK's
`### Blocked items` section — the gate never blocks with no escape, but it
never disappears a finding either.

## What this is not

- Not a replacement for `@security-auditor` — it has no npm audit / dependency
  coverage and no exploitation step. Escalate there for anything non-trivial.
- Not an advisory-only pass like `pr-review-checklist` — Critical/High
  findings are blocking by default here.

***CONTEXT BLOCK***
Skill        : secure-feature-gate
Diff scope   : <n files changed, n insertions/deletions>
Base branch  : <branch diffed against, or "working tree + staged only">
Triggered by : <manual /security-gate | auto-gate from @dev-orchestrator | auto-gate from @issue-implementer>
***END CONTEXT BLOCK***

***HANDOFF BLOCK***
Skill/Agent : secure-feature-gate
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Ran `scripts/diff-security-scan.sh` against <n> changed files.
- Manually confirmed <n> of <n> raw pattern matches as real findings.

### Artifacts produced
| Output | Content |
|---|---|
| verdict | clean \| blocking-fix-inline \| blocking-escalate \| non-blocking |
| blocking_findings | <n> items, each with file:line, catalog title, severity, OWASP |
| non_blocking_findings | <n> items |
| override_reason | <justification text> \| "—" |

### Checks
| Check | Result |
|---|---|
| Diff-scoped only (no unrelated files read) | ✅ passed / ❌ failed |
| Every raw match manually confirmed before being reported as a finding | ✅ passed / ❌ failed |
| No blocking finding silently dropped | ✅ passed / ❌ failed |

### Blocked items
- <finding + override justification, if the human chose to proceed anyway | "—">

### For the next agent or step
<Whether the PR phase may proceed, and whether @security-auditor/@security-implementor should run first>
***END HANDOFF BLOCK***
