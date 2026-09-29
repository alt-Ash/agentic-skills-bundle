---
description: "Diff-scoped OWASP Top 10:2025 gate on pending changes. Blocks on Critical/High findings unless explicitly overridden. Usage: /security-gate [base-branch]"
subtask: true
---

Load the `secure-feature-gate` skill and run it against the current pending changes.

Context: $ARGUMENTS

1. Gather the diff (`git status`, `git diff`, `git diff --staged`). If there's nothing to check, say so and stop.
2. Run `bash scripts/diff-security-scan.sh $ARGUMENTS` from the skill directory.
3. Manually confirm every raw match before treating it as a finding — never report an unconfirmed match.
4. Classify the result (clean / blocking — fix inline / blocking — escalate / non-blocking) and produce the report in the skill's defined output format.
5. If there is a Critical/High finding, this is blocking: either fix it, recommend `@security-auditor` → `@security-implementor`, or record an explicit human override — never proceed silently.
