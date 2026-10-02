# CLAUDE.md

Guidance for Claude Code in this repo.

## What this project is

This repo **produces** AI agent skills, agents, commands, hooks, and MCP servers — it does not consume them. The CLI (`agentic-skills`, a self-contained jar built by `bin/agentic-skills-cli`) copies files from this repo into users' own AI tool configurations.

**Critical:** `~/.claude/`, `~/.config/opencode/`, `~/.cursor/` etc. are install **targets**, written only when a user runs the CLI in their own environment. Never read or validate against them during development.

## Commands

Everything is Maven via the root aggregator `pom.xml` and wrapper (no Node/npm anywhere):

```bash
./mvnw verify                                   # all modules: unit tests + ITs; billed evals excluded by default
./mvnw -pl bin/agentic-skills-cli -am package -DskipTests   # build the self-contained installer jar
java -jar bin/agentic-skills-cli/target/agentic-skills.jar  # run the interactive installer
java -jar bin/agentic-skills-cli/target/agentic-skills.jar --package-root .   # ...against this checkout's content
./mvnw -pl bin/agentic-skills-cli -am test -Dtest=AgentFileStructureTest,SkillFileStructureTest,CommandFileStructureTest,TokenBudgetTest -Dsurefire.failIfNoSpecifiedTests=false   # structural + token-budget checks
./mvnw -pl evals/agentic-skills-evals -Pbilled-evals test -Dtest=TddEngineerEvalTest   # behavioral eval — REAL BILLED model calls, run sparingly
# hooks: black-box IT against the packaged jar
./mvnw -pl hooks/agentic-skills-hooks verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=HookInvocationIT
```

Run one test class: `-Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.

## Architecture

### Installer (`bin/agentic-skills-cli`)

Plain Java 21 (no Spring Boot, no DI). One jar, `target/agentic-skills.jar`, carries `skills/`, `agents/`, `.opencode/commands/`, `templates/`, the hooks jar and both MCP jars under `bundle/`. Without `--package-root`, `BundleExtractor` unpacks `bundle/` once per version to `~/.agentic-skills/dist/<version>/`, which becomes `PackageRoot`. `--uninstall` jumps to the uninstall wizard; `upgrade` refreshes installs; `data <path|import|prune>` and `dashboard` (read-only, localhost) skip the bundle. Released via JReleaser on `v*` tags.

Install behaviour is driven by registries in `.../agenticskillscli/registry/`:

| Registry | Purpose |
|---|---|
| `AgentToolRegistry` | Per-tool capability flags (`supportsCommands`, `supportsAgents`, `agentsGlobalPath`, `agentsProjectFolder`, …) |
| `McpConfigRegistry` | Per-tool MCP config paths and JSON key names |
| `CommandRegistry` | Maps skill/agent names to companion slash command files |
| `HooksRegistry` | Maps each hook type to its Claude Code hook-event name(s) |

Key classes: `discovery/{Agent,Skill}Discovery` (scan `agents/`, `skills/<category>/`), `frontmatter/AgentContentTransformer` (OpenCode-format agent → target tool's format), `install/{Agent,Skill,Command}Installer`, and the wizards in `flow/` (`QuickInstallFlow`, `FullInstallFlow`, `TokenUpdateFlow`, `UninstallWizard`) from `App.main`. `config/HookRegistrar` merges the hooks (5 analytics + opt-in `guard`) into Claude Code's `settings.json`, preserving existing `hooks`; Claude via `HookRegistrar`, others via `ToolHooksInstallers`.

### Hooks (`hooks/agentic-skills-hooks`)

Plain Java 21 fat jar: `java -jar agentic-skills-hooks.jar <hookType>`. Fresh JVM per firing. Reads stdin JSON; **always exits 0** — except opt-in `guard`/`verify` (exit 2 blocks; fail open). Detail: `docs/hooks.md`. `EventLog.recordEvent` → `UsageDb` (`data/agentic-skills-usage-store`: SQLite `~/.agentic-skills/data/usage.db`, override `AGENTIC_SKILLS_DB`), then optional POST to `ANALYTICS_SERVICE_URL`.

| Hook type | Event kind | Fires |
|---|---|---|
| `post-tool-use` | `tool_use` | after a successful tool call; reads model + tokens from the transcript |
| `post-tool-use-failure` | `tool_failure` | after a failed tool call |
| `session` | `session_start` / `session_end` | session lifecycle (`source` / `reason`) |
| `user-prompt-submit` | `user_prompt` | each prompt submit |
| `stop` | `turn_stop` | end of each turn; lengths + token usage, never content |
| `subagent` | `subagent_start`/`_stop` | sub-agent start/stop |
| `guard`, `verify`, `context` (opt-in) | `guard_block`, `verify_*` | `PreToolUse` / `Stop` / `SessionStart` |

`ProviderDetector` guesses the provider from payload shape; shims set it; `agy` = Antigravity adapter.

### Evals (`evals/agentic-skills-evals`)

Behavioral evals: **real, billed calls; run sparingly.** Plain Java 21. They drive the `claude` CLI over its bidirectional control protocol (`ProcessBuilder`, stream-json, `hook_callback`/`mcp_message` round-trips), giving real tool execution and live `PreToolUse`/`PostToolUse` interception. That protocol is **undocumented and reverse-engineered**; it can drift across `claude` releases. `GoldenChecker` (deterministic checks) and `Judge` (1–5 rubric) gate each scenario; `AbstractEvalTest` generates one `DynamicTest` per fixture under `src/test/resources/fixtures/`. `EvalCli` has `check`, `select`, `report [--save-baseline]`. Full detail: `evals/README.md`. `/eval-agent` (`.opencode/commands/eval-agent.md`) runs `check` after editing an agent; needs a local jar and `claude login`, so it's **not** in `CommandRegistry`. The module is never bundled into `agentic-skills.jar`.

### Extension patterns

- **New skill:** add `skills/<category>/<name>/` — auto-discovered.
- **New agent:** add `agents/<name>.md` — auto-discovered.
- **New command:** add `.opencode/commands/<name>.md` (installed to every tool with `supportsCommands`: OpenCode, Claude Code) and map it in `CommandRegistry`. Frontmatter is `description` (+ optional `subtask`) only; quote a `description` containing a colon.
- **New local MCP:** no registry pattern exists. Write bespoke build/install/config/uninstall code in `mcp/local/`, modelled on `SecurityScannerMcpInstaller`. MCPs are Java/Spring Boot modules in the root reactor; add the new fat jar to the cli module's `provided` deps and the `maven-dependency-plugin` copy list. Keep MCPs on the JVM.
- **New hook type:** add the class under `hooks/.../hooks/`, register it in `HookDispatcher`, add a `HookDescriptor` to `HooksRegistry` (`ALL`, or an opt-in constant like `GUARD`). A new tool: implement `ToolHooksInstaller`, add it to `ToolHooksInstallers` and `HookToolSupport`.

### Test suites

| Suite | Covers |
|---|---|
| cli `content/` (`AgentFileStructureTest`, `SkillFileStructureTest`, `CommandFileStructureTest`, `TokenBudgetTest`) | Frontmatter/structure of agents, skills, commands, plus per-file token ceilings in `token-budgets.properties`. No model calls. Repo root is resolved independently of the shared `PackageRoot` singleton. |
| `evals/agentic-skills-evals/` | `./mvnw -pl evals/agentic-skills-evals test` runs only zero-cost classes; `-Pbilled-evals` (or an explicit `-Dtest=…EvalTest`) opts into billed ones |
| `hooks/agentic-skills-hooks/` | Unit tests, then `HookInvocationIT` (failsafe) spawns the shaded jar per hook type and asserts DB rows and POSTed events |

Agent files need `description` (≥ 20 chars), `mode` (`subagent`/`primary`), `temperature` (0–1), `color` (hex), a `permission` object, ≥ 2 `##` sections, and a documented output format. Skills need `name`/`description` and `***CONTEXT BLOCK***`/`***HANDOFF BLOCK***` templates. Commands: frontmatter `description`/`subtask` only.

**Token budgets:** every agent, `SKILL.md`, command, and root doc has a ceiling in `bin/agentic-skills-cli/src/test/resources/token-budgets.properties` (chars/4). Ceilings only go down; a new file must declare one.
