---
description: "Implement an existing GitHub or Azure DevOps issue end-to-end. Pulls the issue body (expects the issue-architect template with agent_contract), triages, delegates to sub-agents/skills, iterates until validation passes, and optionally opens a PR. Usage: /implement-issue <issue-number-or-url>"
subtask: true
---

Use the `@issue-implementer` agent to implement this issue.

Issue: $ARGUMENTS

Follow the full Issue Implementer workflow:

1. Resolve the issue. Detect the platform from `git remote -v`. Pull the issue body via the GitHub MCP / `gh` CLI, or via `az boards work-item show` for Azure DevOps. Parse the `agent_contract` block.
2. Branch decision. Show the issue title and type, then ask the user one question: create a new `<type>/<issue-number>-<slug>` branch, or work on the current branch. Do not proceed until answered.
3. Triage. Read acceptance criteria, success signals, in/out of scope, affected files. Build a `todowrite` plan with one item per acceptance criterion plus validation gates.
4. Detect the project's build/test/lint commands from `package.json`, `Makefile`, `pyproject.toml`, `go.mod`, `Cargo.toml`, `AGENTS.md`, `CLAUDE.md`, `README.md`. Do not invent commands.
5. Implement. Delegate to specialist sub-agents and skills whenever they fit (`@spring-boot-backend-engineer`, `@security-auditor`, `@tdd-engineer`, plus skills like `java-version-migrator`). Pass each delegate only the relevant slice, not the full issue.
6. Validate. Run lint → build → tests. Iterate Phase 5 ↔ Phase 6 until every acceptance criterion is satisfied, every `success_signal` is observable, and gates pass (or only pre-existing failures remain). Honor the contract's `forbidden` list. Hard-stop after ~10 iterations or on a recurring failure and ask the user.
7. Produce the concise summary in the exact format defined in the agent prompt (Summary, Changes, Validation, Closes).
8. Ask exactly one question: open a PR now? If yes, commit with a conventional message (no AI attribution, no Co-Authored-By), push the branch, and create the PR via `gh pr create` or `az repos pr create` using the summary as the body. Return the PR URL.
9. Emit the final `implement_result` YAML block as the very last output.

Do not push to default branches. Do not force-push. Do not amend pushed commits. Do not expand scope beyond the issue.
