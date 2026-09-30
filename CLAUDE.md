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
pnpm run test:cli       # JUnit suite for bin/agentic-skills-cli (the Java installer)
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

`bin/install.js`/`bin/uninstall.js` are thin Node shims (~20 lines each) —
they resolve the installed npm package's root directory and `execFileSync`
into a bundled Java fat jar (`bin/agentic-skills-cli/target/agentic-skills-cli.jar`,
passed `--package-root <path>` and, for the uninstall shim, `--uninstall`),
with `stdio: 'inherit'` so the JVM's interactive prompts work normally. All
actual install/uninstall logic now lives in `bin/agentic-skills-cli/`, a
plain-Java-21 Maven project (no Spring Boot — see that module's `pom.xml`
for the rationale: it's a one-shot-per-invocation wizard, not a long-lived
process, so a DI-container bootstrap cost buys nothing here). Build it with
`mvn -f bin/agentic-skills-cli/pom.xml package` (or `pnpm test:cli` to run
its JUnit suite); `npm pack`/`npm publish` build it automatically via the
root `package.json`'s `prepack` script.

Install behaviour is driven by registries under
`bin/agentic-skills-cli/src/main/java/dev/dorrian/agenticskillscli/registry/`:

| Registry | Purpose |
|---|---|
| `AgentToolRegistry` | Per-tool capability flags (`supportsCommands`, `supportsAgents`, `agentsGlobalPath`, `agentsProjectFolder`, …) |
| `McpConfigRegistry` | Per-tool MCP config file paths and JSON key names |
| `CommandRegistry` | Maps skill/agent names to companion slash command files |
| `HooksRegistry` | Maps each hook type to the Claude Code hook-event name(s) it registers under (see Hooks section below) |

Key classes: `discovery/AgentDiscovery`/`discovery/SkillDiscovery`
(filesystem scan of `agents/`/`skills/<category>/`),
`frontmatter/AgentContentTransformer` (rewrites an agent's OpenCode-format
frontmatter into the target tool's format), `install/AgentInstaller`/
`install/SkillInstaller`/`install/CommandInstaller` (copy to the target
tool's config directory), and the four top-level wizards under `flow/`
(`QuickInstallFlow`, `FullInstallFlow`, `TokenUpdateFlow`, `UninstallWizard`),
dispatched from `App.main`.

**Known gap, now closed**: `bin/agentic-skills-cli`'s `config/HookRegistrar`
+ `registry/HooksRegistry` register the 5 hooks into a target project's
Claude Code `settings.json` (merging into any existing `hooks` block rather
than clobbering it). This is scoped to **Claude Code only** for now — the
other 6 tools in `AgentToolRegistry` don't have a documented hook-config
schema to build against; extending hook registration to them is unstarted,
not just unimplemented.

### Hooks (`hooks/`)

`hooks/agentic-skills-hooks/` is a plain-Java-21 Maven project (no Spring
Boot — each hook type is a brand-new JVM process spawned per firing,
potentially many times per session, so a framework bootstrap cost is paid on
every single invocation, not amortized like the long-lived MCP servers
below). It builds to one fat jar, argv-dispatched:
`java -jar agentic-skills-hooks.jar <hookType>` where `hookType` is one of
`post-tool-use` / `post-tool-use-failure` / `session` / `user-prompt-submit` /
`stop`. Each reads all of stdin as JSON, writes its side effects, and always
exits 0 (an internal failure must never block the host CLI). Build/test via
`pnpm test:hooks` (builds the jar, then runs the black-box Vitest spawn
suite in `hooks/tests/hook-invocation.test.ts`) or
`mvn -f hooks/agentic-skills-hooks/pom.xml test` for the JUnit suite alone.

Shared infrastructure lives in `EventLog.java`: the `UsageEvent` type,
`recordEvent` (read-modify-write to `ai-usage-events.json` in the JVM's
working directory), and optional fire-and-forget POST to
`ANALYTICS_SERVICE_URL` via `AnalyticsServiceClient` (`java.net.http.HttpClient`).

| Hook type | Event kind | When it fires |
|---|---|---|
| `post-tool-use` | `tool_use` | After each successful tool call; reads model + token data from the transcript |
| `post-tool-use-failure` | `tool_failure` | After a tool call fails; captures tool name and error message |
| `session` | `session_start` / `session_end` | Session lifecycle; captures `source` (start) and `reason` (end) |
| `user-prompt-submit` | `user_prompt` | Before the model runs, every time a user submits a prompt |
| `stop` | `turn_stop` | End of each assistant turn; records character length as an output-size proxy — never stores message content |

Provider detection (`ProviderDetector`) is best-effort from hook payload shape: Gemini is flagged by `hook_event_name == "AfterTool"`; Cursor by having both `model` and `user_email`/`conversation_id`; Codex by `model` alone; everything else is Claude.

### Extension patterns

- **New skill:** add a directory to `skills/<category>/` — auto-discovered, no installer changes needed.
- **New agent:** add `agents/<name>.md` — auto-discovered.
- **New command:** add `.opencode/commands/<name>.md` (the single source of truth, installed to every tool with `supportsCommands: true` in `AgentToolRegistry` — currently OpenCode and Claude Code) and map it to its backing skill/agent via `CommandRegistry` in `bin/agentic-skills-cli`. Keep the frontmatter to `description` (+ optional `subtask`) — quote the `description` value if it contains a colon, since unquoted colons break strict YAML frontmatter parsers.
- **New local MCP:** there is currently no descriptor/registry pattern for this. Write bespoke build/install/config/uninstall logic directly in `bin/agentic-skills-cli`'s `mcp/local/` package, following `SecurityScannerMcpInstaller` as a reference implementation. Both `issue-tickets` and `security-scanner` are Java/Spring Boot Maven projects (not Node/TS) — the installer shells out to `mvn -q -DskipTests package` and copies the resulting self-contained fat jar (`target/<name>.jar`); the config-builder method launches it via `java -jar <path>` instead of `node <entryPoint>`. A brand-new local MCP could still be Node/TS if that's a better fit for it — this repo now has precedent for both toolchains side by side.
- **New hook provider / new hook type:** add the Java class under `hooks/agentic-skills-hooks/src/main/java/.../hooks/`, register the new `hookType` in `HookDispatcher`, and add a matching `HookDescriptor` entry to `bin/agentic-skills-cli`'s `HooksRegistry`. Registration is currently Claude-Code-only — extending it to another tool means adding that tool's hook-config schema to `HookRegistrar` first (none of the other 6 tools have one documented yet).

### Test suites

| Suite | Config | What it covers |
|---|---|---|
| `evals/structural/` | `evals/vitest.config.ts` | Validates agent frontmatter fields and skill file structure |
| `evals/behavioral/` | `evals/vitest.config.ts` | LLM-invoked scenario evals (expensive) |
| `hooks/tests/` | `hooks/vitest.config.ts` | Spawns the built hooks jar (`java -jar ... <hookType>`) as a child process, asserts event output — cross-language black-box test, deliberately kept in a different toolchain than the JUnit suite it sits alongside |
| `e2e/` | `e2e/vitest.config.ts` | Analytics pipeline integration |

Agent files must pass `evals/structural/agents.test.ts`: required frontmatter includes `description` (≥ 20 chars), `mode` (`subagent` or `primary`), `temperature` (0–1), `color` (hex), and a `permission` object. Body must have ≥ 2 `##` sections and document its output format.
