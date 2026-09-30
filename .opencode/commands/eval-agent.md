---
description: "Runs the Java behavioral-eval harness against one agent's existing scenarios and reports golden-check + judge results. Usage: /eval-agent <agent-name>"
subtask: true
---

Repo-maintainer tooling only: requires a local `claude login` session and a JDK/Maven toolchain to build `evals/agentic-skills-evals`. Not part of the installed skill/command set — never wired into `bin/agentic-skills-cli`'s registries, so it does not get copied into an end user's tool config.

Context: $ARGUMENTS

1. Determine the target agent name: an explicit name in `$ARGUMENTS`, or — if omitted — infer it from an `agents/<name>.md` file recently discussed or edited in this conversation. If neither is available, ask which agent to check rather than guessing.
2. Confirm the agent name is one of the currently supported ones (`issue-architect`, `security-auditor`, `security-implementor`, `tdd-engineer` — check `evals/agentic-skills-evals/src/main/java/dev/dorrian/agenticskillsevals/cli/EvalCli.java`'s `AGENT_TEST_CLASSES` map if unsure, since this list can grow). If the agent isn't in that list, say so plainly — there's no eval scenario for it yet, this isn't a "no issues found" result.
3. Build the jar if it doesn't exist yet or looks stale: `mvn -q -DskipTests package -f evals/agentic-skills-evals/pom.xml`.
4. Run `java -jar evals/agentic-skills-evals/target/agentic-skills-evals.jar check <agent-name>` from the repo root. This makes real, billed Anthropic API calls (one per scenario, plus one judge call per scenario) — tell the user this before running if it isn't already obvious from context, per this repo's "run sparingly" convention for behavioral evals.
5. Summarize the output in plain language: which scenarios passed/failed, the golden-check pass count, the judge score and rationale per scenario, and any tool-call anomalies. Flag failures clearly and don't soften a failing judge rationale — quote it.
6. If everything passed, say so concisely — don't pad a clean result with caveats.
