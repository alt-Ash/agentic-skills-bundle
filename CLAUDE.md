# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

This repo **produces** AI agent skills, agents, commands, hooks, and MCP servers — it does not consume them. The CLI (`agentic-skills` / `bin/install.js`) copies files from this repo into users' own AI tool configurations.

**Critical:** Global paths like `~/.claude/`, `~/.config/opencode/`, `~/.cursor/` are install **targets** written to only when a user runs the CLI in their own environment. Never read, inspect, or validate against these paths during development.

## Commands

```bash
pnpm start              # run the interactive CLI installer
pnpm test               # structural tests (agent/skill file validation)
pnpm run eval           # behavioral LLM evaluations — invoke actual models, run sparingly
pnpm run test:hooks     # hook unit tests (spawns compiled hooks as child processes)
pnpm run test:e2e       # analytics pipeline integration test
```

Run a single test file:

```bash
# structural
vitest run --config evals/vitest.config.ts evals/structural/agents.test.ts

# hooks
vitest run --config hooks/vitest.config.ts hooks/tests/hook-invocation.test.ts
```

Syntax-check the installer without running it:

```bash
node --check bin/install.js bin/uninstall.js
```

## Architecture

### Installer (`bin/`)

`bin/install.js` is the CLI entrypoint — a single ~3,700-line file (there is
no `bin/lib/` — earlier versions of this repo split it into separate
modules, but they were consolidated back into `bin/install.js`). All install
behaviour is driven by registries defined inline near the top of that file:

| Registry | Purpose |
|---|---|
| `AGENTS` | Per-tool capability flags (`supportsCommands`, `supportsAgents`, `agentsGlobalPath`, `agentsProjectFolder`, …) |
| `MCP_CONFIG` | Per-tool MCP config file paths and JSON key names |
| `SKILL_COMMANDS` / `AGENT_COMMANDS` | Maps skill/agent names to companion slash command files |

Key functions (also in `bin/install.js`): `discoverAgents`/`discoverSkills`
(filesystem scan of `agents/`/`skills/<category>/`), `transformAgentContent`
(rewrites an agent's OpenCode-format frontmatter into the target tool's
format — see the per-`toolKey` branches for exact field mappings),
`installAgentFiles`/`installSkills`/`installCommands` (copy to the target
tool's config directory). `bin/uninstall.js` is a 4-line shim that just
re-invokes `install.js` in `--uninstall` mode.

**Known gap**: the `hooks/` directory (below) contains real, unit-tested
analytics-hook sources, but `bin/install.js` currently has no code path to
compile or register them into a consuming project's config — there is no
`HOOKS` registry or install/registration function for them anymore. This is
an open gap, not an intentional design choice; `pnpm run test:hooks` still
exercises the hook sources directly and passes independently of the
installer.

### Hooks (`hooks/`)

TypeScript source files, run directly via `tsx` (see `hooks/tests/hook-invocation.test.ts`, which spawns each hook as `npx tsx <hookPath>`). There is currently no esbuild-bundling or install-time compilation step — see the "Known gap" note above.

Shared infrastructure lives in `hooks/lib/event-log.ts`: the `UsageEvent` type, `appendEvent` (read-modify-write to `ai-usage-events.json` in the project CWD), and optional fire-and-forget POST to `ANALYTICS_SERVICE_URL`.

| Hook file | Event kind | When it fires |
|---|---|---|
| `post-tool-use.ts` | `tool_use` | After each successful tool call; reads model + token data from the transcript |
| `post-tool-use-failure.ts` | `tool_failure` | After a tool call fails; captures tool name and error message |
| `session.ts` | `session_start` / `session_end` | Session lifecycle; captures `source` (start) and `reason` (end) |
| `user-prompt-submit.ts` | `user_prompt` | Before the model runs, every time a user submits a prompt |
| `stop.ts` | `turn_stop` | End of each assistant turn; records character length as an output-size proxy — never stores message content |

Provider detection (`detectProvider` in `event-log.ts`) is best-effort from hook payload shape: Gemini is flagged by `hook_event_name === 'AfterTool'`; Cursor by having both `model` and `user_email`/`conversation_id`; Codex by `model` alone; everything else is Claude.

### Extension patterns

- **New skill:** add a directory to `skills/<category>/` — auto-discovered, no installer changes needed.
- **New agent:** add `agents/<name>.md` — auto-discovered.
- **New command:** add `.opencode/commands/<name>.md` (the single source of truth, installed to every tool with `supportsCommands: true` in `bin/install.js`'s `AGENTS` registry — currently OpenCode and Claude Code) and map it to its backing skill/agent via `SKILL_COMMANDS`/`AGENT_COMMANDS` in `bin/install.js`. Keep the frontmatter to `description` (+ optional `subtask`) — quote the `description` value if it contains a colon, since unquoted colons break strict YAML frontmatter parsers.
- **New local MCP:** there is currently no descriptor/registry pattern for this (the earlier `bin/lib/mcp-registry.js` + `mcp-factory.js` descriptor pattern no longer exists). Write bespoke build/install/config/uninstall logic directly in `bin/install.js`, following the `security-scanner` MCP (~line 1094 onward) as a reference implementation.
- **New hook provider / new hook type:** not currently supported by the installer — see the "Known gap" note above. `hooks/<name>.ts` sources and their tests under `hooks/tests/` remain the right place to add new hook logic, but wiring it into any tool's config is unimplemented; treat this as a prerequisite piece of work, not a drop-in extension point.

### Test suites

| Suite | Config | What it covers |
|---|---|---|
| `evals/structural/` | `evals/vitest.config.ts` | Validates agent frontmatter fields and skill file structure |
| `evals/behavioral/` | `evals/vitest.config.ts` | LLM-invoked scenario evals (expensive) |
| `hooks/tests/` | `hooks/vitest.config.ts` | Spawns hook sources via `tsx` as child processes, asserts event output |
| `e2e/` | `e2e/vitest.config.ts` | Analytics pipeline integration |

Agent files must pass `evals/structural/agents.test.ts`: required frontmatter includes `description` (≥ 20 chars), `mode` (`subagent` or `primary`), `temperature` (0–1), `color` (hex), and a `permission` object. Body must have ≥ 2 `##` sections and document its output format.
