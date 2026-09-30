# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

This repo **produces** AI agent skills, agents, commands, hooks, and MCP servers — it does not consume them. The CLI (`agentic-skills` / `bin/install.js`) copies files from this repo into users' own AI tool configurations.

**Critical:** Global paths like `~/.claude/`, `~/.config/opencode/`, `~/.cursor/` are install **targets** written to only when a user runs the CLI in their own environment. Never read, inspect, or validate against these paths during development.

## Commands

```bash
pnpm start              # run the interactive CLI installer
pnpm test               # structural tests (agent/skill/command file validation, Java)
pnpm run eval           # behavioral LLM evaluations (Java) — invoke actual models, run sparingly
pnpm run test:hooks     # hook unit tests (spawns compiled hooks as child processes)
pnpm run test:cli       # JUnit suite for bin/agentic-skills-cli (the Java installer)
```

Run a single test file:

```bash
# structural (one of the three content-validation classes)
mvn test -f bin/agentic-skills-cli/pom.xml -Dtest=AgentFileStructureTest

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
other 7 tools in `AgentToolRegistry` don't have a documented hook-config
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

### Evals (`evals/`)

`evals/agentic-skills-evals/` (behavioral evals — real, billed model calls, run sparingly)
is a plain-Java-21 Maven project, same no-Spring-Boot reasoning as the installer/hooks
(short-lived CLI invocations). Unlike those two, it doesn't call a completions API at
all — it speaks the `claude` CLI's bidirectional control protocol directly via
`ProcessBuilder` (`claude -p --input-format stream-json --output-format stream-json
--verbose`, an `initialize` control_request declaring hook interest, then
`hook_callback`/`mcp_message` control_request/control_response round-trips). This gets
real tool execution, real MCP wiring, and — the part a plain completions API can't
give you — live `PreToolUse`/`PostToolUse` hook interception of a session in progress,
not just post-hoc transcript parsing. **This protocol is not publicly documented by
Anthropic**; it was reverse-engineered via live experimentation (verified against a
real `claude` subprocess, cross-checked against the community `.NET`/Rust Agent SDK
wrappers' own reverse-engineering) and can drift across `claude` CLI releases with no
changelog to consult — see the `claude-cli-control-protocol` memory entry for the
exact verified wire frames if this needs re-validating after a CLI upgrade.

`protocol/ControlProtocolTransport` (the low-level wire client) and
`protocol/ClaudeSession` (the reusable, ergonomic wrapper — `query()`-only use with no
hooks registered is just the degenerate case of the same class) are deliberately
generic, not eval-specific: `HooksRegistry`/`HookRegistrar` (see above) mediate
Claude Code hook registration for *live* sessions today, so `ClaudeSession` is a
candidate to eventually back a real-time guard component, but nothing currently wires
it into a live session — it's used here only to replay canned scenarios offline.
`mcp/InProcessMcpBridge` replaces the old TS harness's separate-subprocess mock MCP
server with an in-process JSON-RPC 2.0 dispatcher (one less moving part, verified
against a real `mcp_message` round-trip).

`golden/GoldenChecker` (deterministic required/forbidden/tool-call/YAML-block checks)
and `judge/Judge` (a second `ClaudeSession` call scoring the response 1–5 per rubric
dimension) gate each scenario; `eval/AbstractEvalTest` is a JUnit 5 `@TestFactory` base
class that four concrete classes (`IssueArchitectEvalTest`,
`SecurityAuditorEvalTest`, `SecurityImplementorEvalTest`, `TddEngineerEvalTest`)
extend, one `DynamicTest` per fixture scenario under `src/test/resources/fixtures/`.
`cli/EvalCli` is the Main-Class for the shaded jar: `check <agent> [scenario]` (drives
the JUnit Platform Launcher against the same test classes — one source of truth, no
duplicated scenario-running logic; loads them from `target/test-classes` via a child
classloader since they're compiled to test scope, not the main jar), `select`
(interactive picker, JLine3), `report [--save-baseline]` (windowed pass-rate/judge/cost
trend + regression alerts, replacing the old `report.ts`).

A `/eval-agent` slash command (`.opencode/commands/eval-agent.md`) runs `EvalCli check`
for on-demand regression checks after editing an agent — deliberately **not** wired
into `CommandRegistry`/any installer registry, since it needs a locally-built jar and a
local `claude login` session; it's a repo-contributor tool, never installed for end
users of this npm package. This module itself is never bundled in the npm package
either (`package.json`'s `files`/`prepack` don't reference it) — dev-only tooling.

### Extension patterns

- **New skill:** add a directory to `skills/<category>/` — auto-discovered, no installer changes needed.
- **New agent:** add `agents/<name>.md` — auto-discovered.
- **New command:** add `.opencode/commands/<name>.md` (the single source of truth, installed to every tool with `supportsCommands: true` in `AgentToolRegistry` — currently OpenCode and Claude Code) and map it to its backing skill/agent via `CommandRegistry` in `bin/agentic-skills-cli`. Keep the frontmatter to `description` (+ optional `subtask`) — quote the `description` value if it contains a colon, since unquoted colons break strict YAML frontmatter parsers.
- **New local MCP:** there is currently no descriptor/registry pattern for this. Write bespoke build/install/config/uninstall logic directly in `bin/agentic-skills-cli`'s `mcp/local/` package, following `SecurityScannerMcpInstaller` as a reference implementation. Both `issue-tickets` and `security-scanner` are Java/Spring Boot Maven projects (not Node/TS) — the installer shells out to `mvn -q -DskipTests package` and copies the resulting self-contained fat jar (`target/<name>.jar`); the config-builder method launches it via `java -jar <path>` instead of `node <entryPoint>`. A brand-new local MCP could still be Node/TS if that's a better fit for it — this repo now has precedent for both toolchains side by side.
- **New hook provider / new hook type:** add the Java class under `hooks/agentic-skills-hooks/src/main/java/.../hooks/`, register the new `hookType` in `HookDispatcher`, and add a matching `HookDescriptor` entry to `bin/agentic-skills-cli`'s `HooksRegistry`. Registration is currently Claude-Code-only — extending it to another tool means adding that tool's hook-config schema to `HookRegistrar` first (none of the other 7 tools have one documented yet).

### Test suites

| Suite | Config | What it covers |
|---|---|---|
| `bin/agentic-skills-cli`'s `content/` package | `mvn test -f bin/agentic-skills-cli/pom.xml -Dtest=AgentFileStructureTest,SkillFileStructureTest,CommandFileStructureTest` (aliased as `pnpm test`) | Validates agent/skill/command frontmatter fields and file structure — no model calls, no real API cost. Resolves the repo root independently of the shared `PackageRoot` singleton (which `PackageRootTest` repeatedly re-points at fake dirs in the same Surefire fork) so results don't depend on cross-class execution order. |
| `evals/agentic-skills-evals/` | `mvn -f evals/agentic-skills-evals/pom.xml test` | LLM-invoked scenario evals (Java, expensive — real billed model calls) |
| `hooks/tests/` | `hooks/vitest.config.ts` | Spawns the built hooks jar (`java -jar ... <hookType>`) as a child process, asserts event output — cross-language black-box test, deliberately kept in a different toolchain than the JUnit suite it sits alongside |

Agent files must pass `AgentFileStructureTest`: required frontmatter includes `description` (≥ 20 chars), `mode` (`subagent` or `primary`), `temperature` (0–1), `color` (hex), and a `permission` object. Body must have ≥ 2 `##` sections and document its output format. Skill files must pass `SkillFileStructureTest` (required `name`/`description`, `***CONTEXT BLOCK***`/`***HANDOFF BLOCK***` templates). Command files must pass `CommandFileStructureTest` (frontmatter limited to `description`/`subtask`).

`e2e/` (the analytics pipeline integration test) was removed — it was already gated behind an external `ANALYTICS_SERVICE_ROOT` and skipped by default in normal runs.
