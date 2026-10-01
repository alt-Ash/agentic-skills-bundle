---
description: "Draft and optionally post a structured GitHub or Azure DevOps issue. Reads the project, captures intent, and produces a deterministic issue body designed to be consumed as a prompt by another LLM agent. Usage: /new-issue <short description of the issue>"
subtask: true
---

Use the `@issue-architect` agent to handle this request.

User intent: $ARGUMENTS

Follow the full Issue Architect workflow **exactly as defined in the agent**, starting with Phase 0. Do not skip or reorder any phase.

**Phase 0 (mandatory first step — do not skip):** Before scanning or reading anything, sync with the default branch:
1. Run `git rev-parse --abbrev-ref origin/HEAD`. If the command fails or returns empty, use `main` as the default branch.
2. Run `git fetch origin`, then `git rev-list HEAD..origin/<default-branch> --count`.
   - Count is `0` → nothing to pull, continue.
   - Count is `> 0` → run `git pull origin <default-branch>`. On failure, warn the user and ask how to proceed (options: continue without changes / resolve manually / custom).

Then continue with the remaining phases as defined in the agent:
1. Capture intent (goal, type, constraints, unknowns) from the user prompt above.
2. Scan the project (README, AGENTS.md, package manifests, configs, `.github/ISSUE_TEMPLATE/`, and any code paths relevant to the intent).
3. Detect the target platform from `git remote -v` (GitHub vs Azure DevOps). Ask the user only if it cannot be determined.
4. Resolve the target location:
   - **GitHub**: prefer the GitHub MCP server if present, otherwise `gh`. Prompt the user to pick organization (or personal) and repo. Optionally collect labels and milestone.
   - **Azure DevOps**: use `az` with the `azure-devops` extension. Prompt for org URL (if no default), project, work item type, and optionally area/iteration.
5. Draft the issue body using the exact template in the agent prompt — including the `agent_contract` YAML block.
6. Show the full draft and ask the user: "Post this issue to **<platform>** (`<destination>`) now, or just return the draft?"
7. If posting was confirmed, create the issue via the appropriate MCP/CLI and return the URL.
8. Always emit the final `issue_result` YAML block as the very last output so it is machine-parseable.

Do not write project source files. Do not run builds or tests. The only side effect allowed is creating the issue/work item, and only after explicit user confirmation.
