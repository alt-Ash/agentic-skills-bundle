---
description: "Advisory review of pending changes against Java/Spring Boot best practices and the originating ticket's scope, before opening a PR. Never edits code, never blocks. Usage: /pr-check [ticket-id-or-branch-context]"
subtask: true
---

Use the `@pr-reviewer` agent to review the current pending changes.

Context: $ARGUMENTS

Follow the PR Reviewer workflow:

1. Gather the diff (`git status`, `git diff`, `git diff --staged`). If there's nothing to review, say so and stop.
2. Identify a ticket reference if one exists (branch name, supplied ID, recent commits) and pull it via the `issue-tickets` MCP `pull_ticket` tool. Run `ticket-scope-extraction` first if the ticket has comments/asides/open items.
3. Load the `pr-review-checklist` skill and review the diff: scope alignment, Java/Spring Boot best practices, maintainability, and any security-load-bearing changes to flag for `@security-auditor`.
4. Produce the report in the agent's defined output format.

This is advisory only — it never edits code and never blocks the commit or PR.
