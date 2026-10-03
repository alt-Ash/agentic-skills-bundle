# Agentic Skills Bundle

A curated collection of reusable AI agent skills, sub-agents, and MCP servers for Java/Spring Boot projects — installed via a single guided CLI into any AI coding assistant's config.

This repo **produces** skills and agents; it does not consume them. `agentic-skills` copies files from here into your own AI tool configuration (globally or per-project).

## Quick start

```bash
# Homebrew (macOS / Linux) — pulls in OpenJDK 21 if needed
brew install alt-ash/tap/agentic-skills
agentic-skills

# JBang (any OS with JBang installed)
jbang agentic-skills@alt-Ash

# Or download agentic-skills.jar from GitHub Releases and run it directly
java -jar agentic-skills.jar
```

To upgrade (`brew update` first if it shows the old version):

```bash
brew upgrade agentic-skills && agentic-skills upgrade
```

To remove everything installed:

```bash
agentic-skills --uninstall
```

The jar is self-contained: on first run it unpacks its content to `~/.agentic-skills/dist/<version>/`.

## What it does

Running `agentic-skills` opens a menu with four modes:

| Mode | What it does |
|---|---|
| **Upgrade** | Refresh installed items that differ from this version. |
| **Install** | Pick skills/agents, global or project scope. Per-item status (up to date, update available, modified locally, new), only updates preselected, "Install everything" shortcut, no credential re-prompts for configured MCPs. |
| **Update token** | Rotate an expired Azure DevOps or GitHub PAT for the `issue-tickets` MCP. |
| **Uninstall** | Remove previously installed skills, agents, and MCP servers for one or more tools. |

## Supported AI tools

Each path is `global` / `project`. A dash means the tool doesn't support that item.

| Tool | Skills | Agents | Commands |
|---|---|---|---|
| OpenCode | `~/.config/opencode/skills` / `.opencode/skills` | `~/.config/opencode/agents` / `.opencode/agents` | `~/.config/opencode/commands` / `.opencode/commands` |
| Claude Code | `~/.claude/skills` / `.claude/skills` | `~/.claude/agents` / `.claude/agents` | `~/.claude/commands` / `.claude/commands` |
| Cursor | `~/.cursor/rules` / `.cursor/rules` | `~/.cursor/agents` / `.cursor/agents` | — |
| Antigravity (CLI + IDE) | `~/.gemini/config/skills` / `.agents/skills` | `~/.gemini/config/agents/<name>/agent.md` / `.agents/agents` | — |
| OpenAI Codex CLI | `~/.agents/skills` / `.agents/skills` | `~/.codex/agents` / `.codex/agents` (`.toml`) | — |
| VS Code (GitHub Copilot) | `~/.vscode/skills` / `.vscode/skills` | `~/.copilot/agents` / — (global only) | — |
| Devin Desktop (Windsurf) | `~/.codeium/windsurf/skills` / `.windsurf/skills` | — | — |
| Zed AI | `~/.config/zed/skills` / `.zed/skills` | — | — |

> [!NOTE]
> For Claude Code, a project target places skills and agents under the same scope (`.claude/skills`, `.claude/agents`). Codex agents are standalone custom-agent TOML (`name`, `description`, `developer_instructions`; read-only agents get `sandbox_mode = "read-only"`); keep repo-specific rules in `AGENTS.md`. Gemini agents get subagent frontmatter (`name`, `description`, `kind: local`, and a read-only `tools` allowlist for non-editing agents). Uninstall also removes older `~/.codex/skills` and `~/.codex/agents/*.md`.

Some skills and agents ship a companion slash command (see each entry's **Companion command** line); commands install only for tools that support them.

## Available skills

### Backend

#### `spring-boot-best-practices`

Spring Boot best practices: dependency injection, transaction boundaries, JPA/Hibernate access patterns, exception handling, validation, testing, and configuration.

**Use when:** writing, reviewing, or refactoring Spring Boot controllers, services, repositories, or entities.

#### `java-version-migrator`

Guides safe Java/JDK version upgrades — audits breaking changes, runs automated migration tooling (OpenRewrite recipes), and updates build configs. Includes a Spring Boot major-version migration guide (2.x→3.x Jakarta EE namespace migration); supports multi-hop JDK upgrades.

**Use when:** upgrading Java/JDK versions, migrating Spring Boot major versions, or investigating breaking changes between versions.
**Companion command:** `/migrate-java <from> <to>`

### DevOps

#### `cicd-pipelines`

Authoring, debugging, and best practices for GitHub Actions and Azure DevOps Pipelines.

**Use when:** creating, fixing, optimizing, or reviewing a CI/CD pipeline, workflow file, or build configuration.

### Quality

#### `pr-review-checklist`

Reviews a pending diff (uncommitted, staged, or against a base branch) for Java/Spring Boot best practices and alignment with the originating ticket's actual scope. Diff-first and token-efficient; never edits, blocks, or runs a full security audit.

**Use when:** a diff needs an advisory best-practice pass before a PR is opened.
**Companion command:** `/pr-check [ticket-id-or-branch-context]` (drives the `pr-reviewer` agent).

### Security

#### `secure-feature-gate`

Runs a fast, diff-scoped OWASP Top 10:2025 pattern check on the current change set and blocks on Critical/High findings unless explicitly overridden. Cheap enough to run on every feature — not a substitute for `security-auditor`'s full audit.

**Use when:** on demand via `/security-gate`, and as a mandatory blocking step inside `dev-orchestrator` and `issue-implementer` before a PR is opened.
**Companion command:** `/security-gate [base-branch]`

### Workflow

#### `ticket-scope-extraction`

Separates genuine scope from noise in a ticket, PBI, or User Story, in both directions — classifies content as signal, flagged aside, open item, or noise, and never silently discards any of it.

**Use when:** a ticket is being read (via `issue-tickets` `pull_ticket`) or drafted, and it mixes the real requirement with questions, asides, meeting-note pastes, or unresolved "to be elaborated" markers. Used internally by `pr-reviewer`, `issue-architect`, and `issue-implementer`.

#### `validation-loop`

Bounded iterate-fix-reverify protocol for any caller-supplied gate set — build/test/lint (`./mvnw verify`, `./gradlew check`), an OWASP Dependency-Check re-scan, a live endpoint re-check. Defines cheapest-first ordering, introduced-vs-pre-existing classification, and a hard-stop template; the caller supplies the gates and fixes.

**Use when:** an agent needs to iterate until a set of checks pass rather than declaring success after the first pass. Used internally by `dev-orchestrator`, `issue-implementer`, `security-implementor`, and `spring-boot-backend-engineer`.

#### `parallel-feature-build`

Splits one feature into independent, file-scoped slices and builds them with parallel generic workers, each on its own isolated working tree where the host tool supports it. Falls back to sequential work when slices are not provably independent.

**Use when:** a feature cleanly decomposes into two or more slices with no shared files, migration ordering, or config edits. Used internally by `dev-orchestrator`.

## Adding new skills

Skills are plain directories inside `skills/<category>/<skill-name>/`. Drop a new folder there and it appears in the installer automatically — no CLI changes required.

```
skills/
  <category>/
    <skill-name>/
      SKILL.md          # required — instructions for the AI agent
      references/       # optional — reference docs loaded on demand
      examples/         # optional — usage examples
      scripts/          # optional — deterministic helper scripts
```

See `templates/SKILL.md` for the full template with all required sections.

## Available agents

Agents run in `primary` or `subagent` mode and are invoked by name (`@agent-name`) inside a supported AI tool.

### Orchestration & workflow

#### `@dev-orchestrator`

Main orchestrator for development workflows. Pulls tickets, plans with OpenSpec, executes via specialist sub-agents, runs the mandatory security gate, iterates until all checks pass, and opens a pull request.

**Invoke when:** starting any feature, bug fix, issue, or audit — the default entry point for development work.
**Claude Code:** run it as the whole session with `claude --agent dev-orchestrator`, or call it as a sub-agent with `@dev-orchestrator`. Independent slices of a feature are built in parallel, each by the matching specialist (e.g. `@spring-boot-backend-engineer`), or by `general-purpose` when none fits or is installed.

#### `@issue-architect`

Formulates GitHub or Azure DevOps issues structured to be consumed as prompts by other LLM agents. Scans the project and produces a deterministic, machine-parseable issue description, optionally created via the `issue-tickets` MCP.

**Invoke when:** filing a new issue, work item, or feature request that another agent will later implement.
**Companion command:** `/new-issue <description>`

#### `@issue-implementer`

Implements a GitHub or Azure DevOps issue end-to-end. Pulls the issue via `issue-tickets`, triages the work, delegates to specialist sub-agents and skills, runs the mandatory security gate, iterates until acceptance criteria pass, and optionally opens a pull request.

**Invoke when:** given an issue number or URL and asked to implement it.
**Companion command:** `/implement-issue <issue>`

#### `@pr-reviewer`

Advisory review of a pending diff against Java/Spring Boot best practices and the originating ticket's scope, before a PR is opened. Read-only — never edits code, never blocks.

**Invoke when:** before opening a pull request, or whenever a best-practice pass on a pending diff is wanted.
**Companion command:** `/pr-check [ticket-id-or-branch-context]`

#### `@project-initializer`

Analyzes a project's structure, tech stack, and configuration, then fills documentation stubs (`AGENT.md`, `CLAUDE.md`, `DESIGN.md`, `ARCHITECTURE.md`, `GLOSSARY.md`, `MEMORY.md`) with real, evidence-based project data — never invented content.

**Invoke when:** right after installing the skill set, to generate and populate documentation for a project.

### Backend

#### `@spring-boot-backend-engineer`

Builds, updates, or removes REST controllers, service/repository layers, JPA entities, and configuration classes. Manages dependency injection, writes JUnit 5 tests, and verifies changes by actually compiling, running, and exercising the application.

**Invoke when:** any Spring Boot backend task, regardless of persistence layer or build tool.

### Quality

#### `@tdd-engineer`

Writes a failing test first, confirms it fails for the right reason, implements the minimum code to make it pass, then runs all project checks. Never touches production code before a failing test proves the change is needed.

**Invoke when:** implementing a feature, fixing a bug, or whenever changes should be test-driven rather than direct edits.

### Security

#### `@security-auditor`

Full Spring Boot/Spring MVC/Spring WebFlux security audit specialized in OWASP Top 10:2025 — OWASP Dependency-Check with confirmed-version triage, a grep sweep of every category, optional proof-of-concept exploitation, and a structured report with fixes. Can optionally invoke the `security-scanner` MCP for live HTTP probes.

**Invoke when:** a thorough security review is needed (heavier than the always-on `secure-feature-gate`).

#### `@security-implementor`

Consumes a `@security-auditor` report's Handoff Block and applies every fixable finding — dependency upgrades, `pom.xml` dependency management, code changes — then re-runs OWASP Dependency-Check to verify. Iterates until every fixable issue is resolved or reports exactly why one cannot be fixed.

**Invoke when:** after `@security-auditor` has produced a report with a Handoff Block.

## Adding new agents

Agent files live in `agents/<agent-name>.md`. Add the file there and it appears in the installer. See `templates/AGENT.md` for the full template.

If the agent requires MCP servers, add an entry to `AgentMcpServerRegistry` in `bin/agentic-skills-cli` (the Java installer — see [Requirements](#requirements)).

## Global MCP tools

The CLI can also configure global MCP tools during Install, independent of any skill. Antigravity (`~/.gemini/config/mcp_config.json` → `mcpServers`, shared by its CLI and IDE) and Codex CLI (`~/.codex/config.toml` → `[mcp_servers.<name>]` tables, edited in place, keeping your comments and settings) are configured too.

MCP servers (these and the [local servers](#local-mcp-servers) below) are configured for **OpenCode, Claude Code, Cursor, VS Code, Devin Desktop (Windsurf), Zed, Antigravity and Codex CLI**.

For Devin Desktop (formerly Windsurf), MCP servers are written to `~/.codeium/windsurf/mcp_config.json` and, if the directory `~/.config/devin/` exists, also to `~/.config/devin/mcp_config.json` (`$XDG_CONFIG_HOME/devin` if set; `%APPDATA%\devin` on Windows), as Devin's docs give both. Uninstall clears both.

### `figma-mcp`

MCP server for Figma — gives your AI agent access to Figma files, components, and design tokens. No token or local install needed:

- **Claude Code, Cursor, VS Code, Codex** — configured against Figma's hosted server (`https://mcp.figma.com/mcp`); you sign in with Figma (OAuth) on first use (Codex: `codex mcp login figma-mcp`).
- **OpenCode, Devin Desktop (Windsurf), Zed, Antigravity** — Figma's hosted server only accepts [catalog-listed clients](https://www.figma.com/mcp-catalog/), so these use the Figma desktop app's local server (`http://127.0.0.1:3845/mcp`), available while the desktop app is open with Dev Mode enabled.

### `engram`

Persistent memory MCP for AI agents — must be installed locally first (`brew install gentleman-programming/tap/engram`); the CLI only registers it.

### `context7`

Up-to-date library documentation MCP, configured against Context7's hosted endpoint (`https://mcp.context7.com/mcp`) — nothing runs locally. Works on the free tier with no key; an optional API key (from `context7.com/dashboard`) can be supplied during install for higher rate limits.

Upgrading from 1.x: existing `npx`-based context7/figma entries are migrated to the hosted endpoints automatically; entries you've customised are left alone.

## Local MCP servers

The CLI installs these into your AI agent config. Both are Java/Spring Boot servers inside `agentic-skills.jar`. For Codex CLI, `issue-tickets` credentials are never written to its config; it forwards them via `env_vars` from your shell profile. Antigravity's entry holds the values (its `$VAR` expansion is undocumented).

### `issue-tickets`

MCP server for ticket/issue management — supports **Azure DevOps and GitHub** through a unified interface.

Tools: `pull_ticket`, `create_issue`, `create_pull_request`

**Source auto-detection:** without `source`, `issue-tickets` inspects the project root, first match wins: a `.github/` directory, `azure-pipelines.yml` or `.azure/`, the `.git/config` remote host, `package.json`'s `repository` field, then `pom.xml` (`<scm>`, `<issueManagement><url>`, project `<url>`). GitHub is recognised by `github.com`, Azure DevOps by `dev.azure.com` or `visualstudio.com`. Else the only provider with credentials is used.

Credentials come from environment variables. The installer collects one or more accounts per provider and writes them to your shell profile (`~/.zshrc` or `~/.bashrc`) as base64-encoded JSON; for each provider the first variable set wins:

| Provider | Variables, in precedence order | Account fields |
|---|---|---|
| Azure DevOps | `AZURE_DEVOPS_ACCOUNTS_B64` (base64 JSON) → `AZURE_DEVOPS_ACCOUNTS` (plain JSON) → legacy single account `AZURE_DEVOPS_ORG_URL` + `AZURE_DEVOPS_TOKEN` | `name`, `orgUrl`, `token` |
| GitHub | `GITHUB_ACCOUNTS_B64` (base64 JSON) → `GITHUB_ACCOUNTS` (plain JSON) → legacy single account `GITHUB_TOKEN` | `name`, `token` |

JSON forms are account arrays, e.g. `[{"name":"work","orgUrl":"https://dev.azure.com/myorg","token":"…"}]`. Variables are re-read on every call, so a rotated token needs no server restart. Only providers with credentials are active. **Update token** rotates an expired Azure DevOps or GitHub PAT.

### `security-scanner`

MCP server that actively tests a URL for common web vulnerabilities using real payloads, not just fingerprinting, and writes a detailed report. No credentials needed — every call is gated by a project-local allowlist (`.security-scanner/allowlist.json`) instead of install-time secrets.

> [!IMPORTANT]
> `environment` in the allowlist must be `local`, `dev`, `staging`, or `test` — **`prod`/`production` is not a valid value.** There is no override that authorizes scanning a production host; a missing, malformed, or out-of-range allowlist entry fails closed.

Tools:
- `scan_passive({ target })` — missing security headers, cookie flags, CORS misconfiguration, exposed sensitive paths, server/framework fingerprinting.
- `scan_active({ target, categories?, confirm, authorization })` — reflected XSS, SQL/NoSQL injection, open redirect, path traversal, JWT `alg:none`, bounded IDOR probes, and an SSRF timing signal. `confirm: true` and a non-empty `authorization` (e.g. a ticket reference) are required and stamped into the report as an audit trail.
- `get_scan_report({ scanId })` — retrieves a previously written report.

A shared circuit breaker aborts the rest of a scan when the target's error rate or latency degrades sharply. Reports are written to `security-scans/<host>-<timestamp>.json` and `.md` in the calling process's working directory. See `mcp/security-scanner/README.md` for full development docs.

## Hooks and usage data

Installing for **Claude Code** (also **Antigravity**, an OpenCode plugin) copies a hooks jar to `~/.agentic-skills/hooks/` and registers it. Hooks record tool use, model and token counts, prompt/response *lengths* (never text), redacted Bash commands, and skills/agents used, into one local SQLite database, `~/.agentic-skills/data/usage.db`, shared by all projects; nothing leaves your machine unless `ANALYTICS_SERVICE_URL` is set. Analytics hooks always exit 0.

- **Opt-in hooks** (default **no**): `guard` blocks destructive `rm`, force-push to `main`/`master`, `.env`/key reads; `verify` keeps the AI working until your `.agentic-skills/verify.json` checks pass (approve per project: `agentic-skills verify trust`); `context` adds session context. Upgrade refreshes our entries, opt-ins kept.
- `agentic-skills upgrade [--dry-run] [--project <dir>]...` is diff-aware: refreshes only items that differ from the bundled version (hashes in `~/.agentic-skills/state/installed.json`, else content compare), skips and reports items you edited, keeps hook opt-ins, never touches MCP config/credentials, never deletes files; `--dry-run` writes nothing. Prints a per-tool report and summary; new items are listed, not installed (run `agentic-skills`, choose Install). Project installs need `--project`. Exit 0 ok, 1 a step failed (others ran), 2 bad args. A newer-version notice checks GitHub at most once/24h (off: `AGENTIC_SKILLS_NO_UPDATE_CHECK=1`); no self-update.
- `agentic-skills dashboard` serves a read-only localhost dashboard (usage, tools, installed-vs-used skills/agents, sessions, guard blocks).
- `agentic-skills data import [dir…]` (or `--find <root>`) loads old `ai-usage-events.json`; `data prune`.
- `--uninstall` removes our hooks (not yours); the database is kept.

Formats and limits: [docs/hooks.md](docs/hooks.md).

## Development

Everything builds with Maven via the wrapper — no Node.js anywhere:

```bash
./mvnw verify                                   # all modules: unit + integration tests
./mvnw -pl bin/agentic-skills-cli -am package -DskipTests
java -jar bin/agentic-skills-cli/target/agentic-skills.jar --package-root .   # run against this checkout's content
```

| Module | What it is |
|---|---|
| `bin/agentic-skills-cli` | The installer (plain Java 21); builds the self-contained `agentic-skills.jar` |
| `hooks/agentic-skills-hooks`, `data/agentic-skills-usage-store` | The hooks and their SQLite store (plain Java 21) |
| `mcp/issue-tickets`, `mcp/security-scanner` | The local MCP servers (Spring Boot + Spring AI) |
| `evals/agentic-skills-evals` | Behavioral evals for the agents — real, billed model calls via the `claude` CLI, so they're excluded from `verify`; opt in with `-Pbilled-evals` |

Releases are cut by pushing a `v<version>` tag matching the root `pom.xml` `revision`: CI then publishes the jar to GitHub Releases, the Homebrew tap and the JBang catalog via JReleaser.

## Requirements

- **Java 21+ JRE** — that's all. The Homebrew formula installs one for you; otherwise any JRE works (e.g. [Adoptium](https://adoptium.net)). No Node.js, npm, or Maven needed.

Upgrading from the 1.x npm package: `npm uninstall -g agentic-skills-bundle`, then install via one of the options in [Quick start](#quick-start).
