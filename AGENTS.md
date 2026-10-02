# agentic-skills-bundle — AI Agent Context

This repo **produces** AI agent skills, agents, and slash commands. It does not use them. A plain-Java-21 installer (`bin/agentic-skills-cli`, built to one self-contained `agentic-skills.jar`) copies them into users' own tool configs. Build everything with `./mvnw verify` (Maven only; no Node/npm).

## Critical rule: never read global config

Do not read, inspect, modify, or validate against global AI tool config (`~/.config/opencode/`, `~/.claude/`, `~/.cursor/`, `~/.gemini/`, `~/.codex/`, `~/.vscode/`, `~/.codeium/`, `~/.config/zed/`). They are install **targets**, written only when a user runs the CLI in their own environment. Never diagnose a problem from the presence or absence of files there.

## Terms always mean the source files in this repo

| Term | Location | Notes |
|---|---|---|
| Skill | `skills/<category>/<name>/SKILL.md` (+ optional supporting files) | Auto-discovered; no installer change needed |
| Agent | `agents/<name>.md` | Auto-discovered; installed as `@name` |
| Command | `.opencode/commands/<name>.md` | Single source for every tool with `supportsCommands`; map it to its skill/agent in `CommandRegistry` (`SKILL_COMMANDS`/`AGENT_COMMANDS`) |

Write `SKILL.md` as instructions for an AI agent that will use the skill after installation.

## Where to look when something is wrong

- Skill content → `skills/<category>/<name>/SKILL.md`
- Installer behaviour → `bin/agentic-skills-cli` (registries in `.../registry/`)
- Missing command or agent → `.opencode/commands/`, `agents/`, and `CommandRegistry`
- Build/package → root `pom.xml` and the module `pom.xml`; `./mvnw verify` reproduces CI

Full architecture and test guidance: `CLAUDE.md`.
