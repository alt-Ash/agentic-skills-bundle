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

To remove everything the CLI installed:

```bash
agentic-skills --uninstall
```

The jar is self-contained: on first run it unpacks its skills, agents, commands, templates and MCP server jars to `~/.agentic-skills/dist/<version>/`.

## What it does

Running `agentic-skills` opens a menu with four modes:

| Mode | What it does |
|---|---|
| **Quick install** | Pick which AI tools you use, then installs every skill, agent, and MCP server globally in one pass (prompts for credentials as needed). Recommended for first-time setup. |
| **Install** | Pick individual skills and agents, choose global or project scope, review a summary, then install. |
| **Update token** | Rotate an expired Azure DevOps or GitHub PAT used by the `issue-tickets` MCP without reinstalling anything else. |
| **Uninstall** | Remove previously installed skills, agents, and MCP servers for one or more tools. |

## Supported AI tools

| Agent | Global skills path | Project skills folder |
|---|---|---|
| OpenCode | `~/.config/opencode/skills` | `.opencode/skills` |
| Claude Code | `~/.claude/skills` | `.claude/skills` |
| Cursor | `~/.cursor/rules` | `.cursor/rules` |
| Gemini CLI | `~/.gemini/skills` | `.gemini/skills` |
| OpenAI Codex CLI | `~/.codex/skills` | `.codex/skills` |
| VS Code (GitHub Copilot) | `~/.vscode/skills` | `.vscode/skills` |
| Windsurf | `~/.codeium/windsurf/skills` | `.windsurf/rules` |
| Zed AI | `~/.config/zed/skills` | `.zed/skills` |

Agents install into a parallel set of paths and support the same global/project split for tools that support sub-agents:

| Agent Tool | Global agent path | Project agent folder |
|---|---|---|
| OpenCode | `~/.config/opencode/agents` | `.opencode/agents` |
| Claude Code | `~/.claude/agents` | `.claude/agents` |
| Cursor | `~/.cursor/agents` | `.cursor/agents` |
| Gemini CLI | `~/.gemini/agents` | `.gemini/agents` |
| OpenAI Codex CLI | `~/.codex/agents` | `.codex/agents` |
| VS Code (GitHub Copilot) | `~/.copilot/agents` | — (global only) |

> [!NOTE]
> For Claude Code, choosing a project target for both skills and agents places everything under the same scope (`.claude/skills` and `.claude/agents`). For Codex CLI, install reusable agent files into `.codex/agents` and keep repo-specific behavior in `AGENTS.md`, which Codex reads by directory scope.

Some skills and agents also ship a companion slash command (see each skill's **Companion command** line below). Commands are only installed for tools that support them:

| Command Tool | Global command path | Project command folder |
|---|---|---|
| OpenCode | `~/.config/opencode/commands` | `.opencode/commands` |
| Claude Code | `~/.claude/commands` | `.claude/commands` |

## Available skills

### Backend

#### `spring-boot-best-practices`

Spring Boot best practices: dependency injection, transaction boundaries, JPA/Hibernate access patterns, exception handling, validation, testing, and configuration.

**Use when:** writing, reviewing, or refactoring Spring Boot controllers, services, repositories, or entities.

#### `java-version-migrator`

Guides safe Java/JDK version upgrades — audits breaking changes, runs automated migration tooling (OpenRewrite recipes), and updates build configs. Includes a Spring Boot major-version migration guide (2.x→3.x Jakarta EE namespace migration) and supports multi-hop JDK upgrades by chaining guides in order.

**Use when:** upgrading Java/JDK versions, migrating Spring Boot major versions, or investigating breaking changes between versions.
**Companion command:** `/migrate-java <from> <to>`

### DevOps

#### `cicd-pipelines`

Authoring, debugging, and best-practice reference for GitHub Actions and Azure DevOps Pipelines, routed by platform to keep context small.

**Use when:** creating, fixing, optimizing, or reviewing a CI/CD pipeline, workflow file, or build configuration.

### Quality

#### `pr-review-checklist`

Reviews a pending diff (uncommitted, staged, or against a base branch) for Java/Spring Boot best practices and alignment with the originating ticket's actual scope. Diff-first and token-efficient — reads the change, not the whole repo — and never edits, blocks, or runs a full security audit.

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

Bounded iterate-fix-reverify protocol for any caller-supplied gate set — build/test/lint (`./mvnw verify`, `./gradlew check`), an OWASP Dependency-Check re-scan, a live endpoint re-check. Defines cheapest-first gate ordering, introduced-vs-pre-existing failure classification, and a uniform hard-stop template; the caller always supplies the concrete gates and fixes.

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

Full Spring Boot/Spring MVC/Spring WebFlux security audit specialized in OWASP Top 10:2025 — OWASP Dependency-Check with confirmed-version triage, a full grep sweep across every category, optional live exploitation as proof-of-concept, and a structured report with fixes. Can optionally invoke the `security-scanner` MCP for a complementary live-HTTP-probe layer.

**Invoke when:** a thorough security review is needed (heavier than the always-on `secure-feature-gate`).

#### `@security-implementor`

Consumes a `@security-auditor` report's Handoff Block and applies every fixable finding — dependency upgrades, `pom.xml` dependency management, code changes — then re-runs OWASP Dependency-Check to verify. Iterates until every fixable issue is resolved or reports exactly why one cannot be fixed.

**Invoke when:** after `@security-auditor` has produced a report with a Handoff Block.

## Adding new agents

Agent files live in `agents/<agent-name>.md`. Add the file there and it appears in the installer. See `templates/AGENT.md` for the full template.

If the agent requires MCP servers, add an entry to `AgentMcpServerRegistry` in `bin/agentic-skills-cli` (the Java installer — see [Requirements](#requirements)).

## Global MCP tools

The CLI can also configure global MCP tools during Install/Quick install. These are not tied to any specific skill.

MCP servers (these and the [local servers](#local-mcp-servers) below) are configured for **OpenCode, Claude Code, Cursor, VS Code, Windsurf and Zed**. Gemini CLI and Codex CLI receive skills and agents but no MCP configuration yet — add servers to their configs manually if you need them.

### `figma-mcp`

MCP server for Figma — gives your AI agent access to Figma files, components, and design tokens. No token or local install needed:

- **Claude Code, Cursor, VS Code** — configured against Figma's hosted server (`https://mcp.figma.com/mcp`); you sign in with Figma (OAuth) on first use.
- **OpenCode, Windsurf, Zed** — Figma's hosted server only accepts [catalog-listed clients](https://www.figma.com/mcp-catalog/), so these use the Figma desktop app's local server (`http://127.0.0.1:3845/mcp`), available while the desktop app is open with Dev Mode enabled.

### `engram`

Persistent memory MCP for AI agents — must be installed locally first (`brew install gentleman-programming/tap/engram`); the CLI only registers it.

### `context7`

Up-to-date library documentation MCP, configured against Context7's hosted endpoint (`https://mcp.context7.com/mcp`) — nothing runs locally. Works on the free tier with no key; an optional API key (from `context7.com/dashboard`) can be supplied during install for higher rate limits.

Upgrading from 1.x: existing `npx`-based context7/figma entries are migrated to the hosted endpoints automatically; entries you've customised are left alone.

## Local MCP servers

The CLI also installs these into your AI agent configuration. Both are Java/Spring Boot servers shipped prebuilt inside `agentic-skills.jar` — no build step or Maven needed.

### `issue-tickets`

MCP server for ticket/issue management — supports **Azure DevOps and GitHub** through a unified interface.

Tools: `pull_ticket`, `create_issue`, `create_pull_request`

**Source auto-detection:** when `source` is not specified, `issue-tickets` inspects the project root in this order, first match wins: a `.github/` directory, `azure-pipelines.yml` or `.azure/`, the `.git/config` remote host, `package.json`'s `repository` field, then `pom.xml` (`<scm>` `url`/`connection`/`developerConnection`, then `<issueManagement><url>`, then the project `<url>`). GitHub is recognised by `github.com` and Azure DevOps by `dev.azure.com` or `visualstudio.com`, in https, ssh, or `scm:git:` form. Gradle build files are not read. If none of these match and only one provider has credentials configured, that provider is used.

Credentials come from environment variables. The installer collects one or more accounts per provider and writes them to your shell profile (`~/.zshrc` or `~/.bashrc`) as base64-encoded JSON; for each provider the first variable set wins:

| Provider | Variables, in precedence order | Account fields |
|---|---|---|
| Azure DevOps | `AZURE_DEVOPS_ACCOUNTS_B64` (base64 JSON) → `AZURE_DEVOPS_ACCOUNTS` (plain JSON) → legacy single account `AZURE_DEVOPS_ORG_URL` + `AZURE_DEVOPS_TOKEN` | `name`, `orgUrl`, `token` |
| GitHub | `GITHUB_ACCOUNTS_B64` (base64 JSON) → `GITHUB_ACCOUNTS` (plain JSON) → legacy single account `GITHUB_TOKEN` | `name`, `token` |

The JSON forms are arrays of accounts, e.g. `[{"name":"work","orgUrl":"https://dev.azure.com/myorg","token":"…"}]`. Variables are re-read on every call, so a rotated token takes effect without restarting the server. Only the providers with credentials are active — the rest degrade gracefully. Use **Update token** in the CLI to rotate an expired Azure DevOps or GitHub PAT without reinstalling anything else.

### `security-scanner`

MCP server that actively tests a URL for common web vulnerabilities using real payloads, not just fingerprinting, and writes a detailed report. No credentials needed — every call is gated by a project-local allowlist (`.security-scanner/allowlist.json`) instead of install-time secrets.

> [!IMPORTANT]
> `environment` in the allowlist must be `local`, `dev`, `staging`, or `test` — **`prod`/`production` is not a valid value.** There is no override that authorizes scanning a production host; a missing, malformed, or out-of-range allowlist entry fails closed.

Tools:
- `scan_passive({ target })` — missing security headers, cookie flags, CORS misconfiguration, exposed sensitive paths, server/framework fingerprinting.
- `scan_active({ target, categories?, confirm, authorization })` — reflected XSS, SQL/NoSQL injection, open redirect, path traversal, JWT `alg:none`, bounded IDOR probes, and an SSRF timing signal. `confirm: true` and a non-empty `authorization` (e.g. a ticket reference) are required and stamped into the report as an audit trail.
- `get_scan_report({ scanId })` — retrieves a previously written report.

A shared circuit breaker aborts the rest of a scan the moment the target's error rate or latency degrades sharply. Reports are written to `security-scans/<host>-<timestamp>.json` and `.md` in the calling process's working directory. See `mcp/security-scanner/README.md` for full development docs.

## Usage analytics hooks (Claude Code)

When you install for **Claude Code**, the CLI also copies a small hooks jar to `~/.agentic-skills/hooks/` and registers it in `~/.claude/settings.json` for `SessionStart`/`SessionEnd`, `UserPromptSubmit`, `PostToolUse`, `PostToolUseFailure` and `Stop`. Each hook records a usage event — tool name, model and token counts, prompt/response *lengths* (never the prompt or response text), the Bash command for Bash tool calls (with secrets redacted), and a per-session git change summary.

- Events are written **locally, in the project directory Claude Code is running in**: `ai-usage-events.json`, `hooks-events.json` and `.hooks-data/`. Add them to that project's `.gitignore`.
- Nothing leaves your machine unless you set `ANALYTICS_SERVICE_URL`, in which case events are also POSTed there.
- A hook can never block Claude Code: it always exits 0, even on internal errors.
- To stop collecting, remove the `agentic-skills-hooks.jar` entries from the `hooks` block in `~/.claude/settings.json` — **the uninstaller does not remove them yet.**

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
| `hooks/agentic-skills-hooks` | The analytics hooks (plain Java 21) |
| `mcp/issue-tickets`, `mcp/security-scanner` | The local MCP servers (Spring Boot + Spring AI) |
| `evals/agentic-skills-evals` | Behavioral evals for the agents — real, billed model calls via the `claude` CLI, so they're excluded from `verify`; opt in with `-Pbilled-evals` |

Releases are cut by pushing a `v<version>` tag matching the root `pom.xml` `revision`: CI then publishes the jar to GitHub Releases, the Homebrew tap and the JBang catalog via JReleaser.

## Requirements

- **Java 21+ JRE** — that's all. The Homebrew formula installs one for you; otherwise any JRE works (e.g. [Adoptium](https://adoptium.net)). No Node.js, npm, or Maven needed.

Upgrading from the 1.x npm package: `npm uninstall -g agentic-skills-bundle`, then install via one of the options in [Quick start](#quick-start).
