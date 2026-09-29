# Agentic Skills Bundle

A curated collection of reusable AI agent skills, sub-agents, and MCP servers for Node.js and React projects — installed via a single guided CLI into any AI coding assistant's config.

This repo **produces** skills and agents; it does not consume them. `agentic-skills` copies files from here into your own AI tool configuration (globally or per-project).

## Quick start

```bash
# Run without installing
npx agentic-skills-bundle

# Or install globally
npm install -g agentic-skills-bundle
agentic-skills

# pnpm
pnpm add -g agentic-skills-bundle
agentic-skills
```

To remove everything the CLI installed:

```bash
agentic-skills-uninstall
```

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

> [!NOTE]
> For Claude Code, choosing a project target for both skills and agents places everything under the same scope (`.claude/skills` and `.claude/agents`). For Codex CLI, install reusable agent files into `.codex/agents` and keep repo-specific behavior in `AGENTS.md`, which Codex reads by directory scope.

Some skills and agents also ship a companion slash command (see each skill's **Companion command** line below). Commands are only installed for tools that support them:

| Command Tool | Global command path | Project command folder |
|---|---|---|
| OpenCode | `~/.config/opencode/commands` | `.opencode/commands` |
| Claude Code | `~/.claude/commands` | `.claude/commands` |

## Available skills

### Frontend

#### `figma-design-to-code`

Implements a Figma design as production React code. Loads the official Figma MCP skill and adds house conventions: reuse existing components, map colors to theme tokens (not hardcoded hex), follow the project's file structure, and verify in a real browser.

**Use when:** given a Figma URL or file key and asked to implement a screen, page, or component.
Requires: `figma-mcp` (installed automatically when this skill is selected).

#### `figma-generate-library`

Builds or updates a Figma design system by pushing React components and design tokens from the codebase into a Figma file. Adds MUI theme token mapping and Storybook-first component discovery.

**Use when:** syncing the codebase into Figma or bootstrapping a new Figma design system from existing components.
Requires: `figma-mcp` (installed automatically when this skill is selected).

#### `figma-code-connect`

Creates Figma Code Connect mappings (`.figma.ts` files) that link Figma design components to their React code counterparts.

**Use when:** setting up Code Connect for Figma Dev Mode, or linking an existing component library to a Figma design system.
Requires: `figma-mcp` (installed automatically when this skill is selected).

#### `cra-to-vite`

Migrates a Create React App (`react-scripts`) project to Vite and moves tests from Jest to Vitest — dependency swap, `vite.config` creation, `index.html` restructuring, env variable renaming (`REACT_APP_*` → `VITE_*`), TypeScript setup, and CI/CD/Dockerfile/Helm updates.

**Use when:** migrating from CRA to Vite, replacing `react-scripts`, or migrating Jest tests to Vitest.
**Companion command:** `/cra-to-vite`

#### `msw-mocking`

Mock Service Worker (MSW 2.x) setup and usage for React projects — installation, handler authoring, Vitest integration, and test patterns (happy path, error overrides, one-time overrides).

**Use when:** writing tests that involve network requests, adding new API mocks, or setting up MSW for the first time.

#### `mui-best-practices`

Material UI theme setup, palette configuration, component customization, and TypeScript augmentation. Theme-first: all design tokens live in `createTheme()`, and the customization hierarchy is `sx` → `styled()` → `theme.components` → `GlobalStyles`.

**Use when:** building or customizing MUI-based UIs, implementing new components, or reviewing existing MUI usage.

#### `mui-migration`

Guides migration between Material UI major versions (v3→v4, v4→v5, v5→v6, v6→v7, v7→v9), including Grid v2. Routes to the correct version-specific reference and covers codemods, package renames, and breaking changes.

**Use when:** upgrading MUI or updating MUI component APIs.
**Companion command:** `/migrate-mui <from> <to>`

#### `react-best-practices`

Functional-component conventions: hooks usage, Context API patterns, deriving state during render instead of syncing in `useEffect`, `Promise.all` for independent parallel requests, and re-render optimization only where measurably useful.

**Use when:** writing, reviewing, or refactoring components, context providers, routes, or API calls.

#### `react-migration`

Guides migration of React applications to React 18 or 19 — peer dependency audit, root API changes, TypeScript updates, automatic batching, and deprecated API removal.

**Use when:** upgrading React from an older version to 18 or 19.
**Companion command:** `/migrate-react <target>`

#### `vite-version-migrator`

Guides safe Vite version upgrades (v4→v5, v5→v6, v6→v7, v7→v8) — audits breaking changes, updates config and plugins, and verifies builds.

**Use when:** upgrading Vite versions or investigating breaking changes between versions.
**Companion command:** `/migrate-vite <from> <to>`

#### `vue-migration`

Guides migration of Vue 2 applications to Vue 3 — direct vs `@vue/compat` incremental strategy selection, breaking changes, and ecosystem upgrades (Vue Router 4, Pinia/Vuex 4, Vite).

**Use when:** upgrading a Vue 2 app to Vue 3 or updating the Vue ecosystem.

### Backend

#### `nodejs-version-migrator`

Guides safe Node.js version upgrades — audits breaking changes, runs automated codemods, checks npm package compatibility against a target version, and updates `.nvmrc`, `package.json engines`, CI/CD, and Dockerfiles. Includes NestJS-specific migration guides (v8→v9, v9→v10, v10→v11) and supports multi-hop upgrades by chaining guides in order.

**Use when:** upgrading Node.js versions or investigating breaking changes between versions.
**Companion command:** `/migrate-node <from> <to>`

### DevOps

#### `cicd-pipelines`

Authoring, debugging, and best-practice reference for GitHub Actions and Azure DevOps Pipelines, routed by platform to keep context small.

**Use when:** creating, fixing, optimizing, or reviewing a CI/CD pipeline, workflow file, or build configuration.

### Quality

#### `pr-review-checklist`

Reviews a pending diff (uncommitted, staged, or against a base branch) for TypeScript/JavaScript best practices and alignment with the originating ticket's actual scope. Diff-first and token-efficient — reads the change, not the whole repo — and never edits, blocks, or runs a full security audit.

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

Advisory review of a pending diff against TypeScript/JavaScript best practices and the originating ticket's scope, before a PR is opened. Read-only — never edits code, never blocks.

**Invoke when:** before opening a pull request, or whenever a best-practice pass on a pending diff is wanted.
**Companion command:** `/pr-check [ticket-id-or-branch-context]`

#### `@project-initializer`

Analyzes a project's structure, tech stack, and configuration, then fills documentation stubs (`AGENT.md`, `CLAUDE.md`, `DESIGN.md`, `ARCHITECTURE.md`, `GLOSSARY.md`, `MEMORY.md`) with real, evidence-based project data — never invented content.

**Invoke when:** right after installing the skill set, to generate and populate documentation for a project.

### Frontend

#### `@react-frontend-engineer`

Builds, updates, or removes application components in React (or React + Next.js). Library-agnostic — loads whichever UI skill is available (MUI, Radix, shadcn/ui, etc.). Manages state, writes Storybook stories, adds tests, and verifies the actual render via Playwright.

**Invoke when:** any React frontend task, regardless of which component library the project uses.

#### `@mui-frontend-engineer`

Builds, updates, or removes application components using Material UI. Manages state, writes Storybook stories, adds tests, and verifies the actual render via Playwright and Chrome DevTools.

**Invoke when:** any frontend task where the UI is built on top of MUI (narrower scope than `@react-frontend-engineer`).

#### `@react-browser-debugger`

Launches a real browser, reads console errors and network calls, locates the bug in the source code, applies a fix, and iterates until the issue is resolved.

**Invoke when:** there is a visible frontend bug, a React error, a failed network request, or any browser-observable issue.
Requires: `chrome-devtools` MCP, `playwright` MCP (installed automatically by the CLI).

#### `@ux-auditor`

Navigates a running SPA, takes snapshots across key screens, and evaluates the interface against the Laws of UX and color-blindness accessibility principles. Produces a prioritized, implementable report handed off to `@react-frontend-engineer` or `@mui-frontend-engineer`.

**Invoke when:** a UX audit, design review, or actionable improvement plan is wanted.

#### `@figma-style-migrator`

Reads design tokens (colors, typography, spacing, shadows, border radius) from a Figma file and migrates them into the project's styling system (MUI theme, CSS custom properties, or design token files), applying changes only after confirmation.

**Invoke when:** a designer has updated the Figma design system and the codebase needs to reflect those changes, or when bootstrapping a new project's theme from Figma.
Requires: `figma-mcp` (installed automatically by the CLI).

### Quality

#### `@tdd-engineer`

Writes a failing test first, confirms it fails for the right reason, implements the minimum code to make it pass, then runs all project checks. Never touches production code before a failing test proves the change is needed.

**Invoke when:** implementing a feature, fixing a bug, or whenever changes should be test-driven rather than direct edits.

### Security

#### `@security-auditor`

Full Node.js/Express/Fastify/NestJS security audit specialized in OWASP Top 10:2025 — `npm audit` with confirmed-version triage, a full grep sweep across every category, optional live exploitation as proof-of-concept, and a structured report with fixes.

**Invoke when:** a thorough security review is needed (heavier than the always-on `secure-feature-gate`).

#### `@security-implementor`

Consumes a `@security-auditor` report's Handoff Block and applies every fixable finding — dependency upgrades, `package.json` overrides, code changes — then re-runs `npm audit` to verify. Iterates until every fixable issue is resolved or reports exactly why one cannot be fixed.

**Invoke when:** after `@security-auditor` has produced a report with a Handoff Block.

## Adding new agents

Agent files live in `agents/<agent-name>.md`. Add the file there and it appears in the installer. See `templates/AGENT.md` for the full template.

If the agent requires MCP servers, add an entry to `AGENT_MCP_SERVERS` in `bin/install.js`.

## Global MCP tools

The CLI can also configure global MCP tools during Install/Quick install. These are not tied to any specific skill.

### `figma-mcp`

MCP server for Figma — gives your AI agent access to Figma files, components, and design tokens. Uses the official `@figma/mcp` package (installed on demand via `npx`).

| Variable | Required | Description |
|---|---|---|
| `FIGMA_ACCESS_TOKEN` | yes | Personal access token from figma.com → Settings → Security → Personal access tokens |

The CLI prompts for the token and writes it to your shell profile (`~/.zshrc` or `~/.bashrc`) automatically.

### `engram`

Persistent memory MCP for AI agents — must be installed locally first (`brew install gentleman-programming/tap/engram`); the CLI only registers it.

### `context7`

Up-to-date library documentation MCP. Works on the free tier with no key; an optional API key (from `context7.com/dashboard`) can be supplied during install for higher rate limits.

## Local MCP servers

The CLI also builds and installs these from source (`mcp/<name>`) into your AI agent configuration.

### `issue-tickets`

MCP server for ticket/issue management — supports **Azure DevOps and GitHub** through a unified interface.

Tools: `pull_ticket`, `create_issue`, `create_pull_request`

**Source auto-detection:** when `source` is not specified, `issue-tickets` inspects the project for `.github/`, `azure-pipelines.yml`, `.git/config`, and `package.json` to determine the correct provider.

| Variable | Required for | Description |
|---|---|---|
| `AZURE_DEVOPS_ORG_URL` | Azure DevOps | Organisation URL, e.g. `https://dev.azure.com/myorg` |
| `AZURE_DEVOPS_TOKEN` | Azure DevOps | Personal Access Token |
| `GITHUB_TOKEN` | GitHub | Personal Access Token or App installation token |

Only the providers whose env vars are set are active — the rest degrade gracefully. Use **Update token** in the CLI to rotate an expired Azure DevOps or GitHub PAT without reinstalling anything else.

### `security-scanner`

MCP server that actively tests a URL for common web vulnerabilities using real payloads, not just fingerprinting, and writes a detailed report. No credentials needed — every call is gated by a project-local allowlist (`.security-scanner/allowlist.json`) instead of install-time secrets.

> [!IMPORTANT]
> `environment` in the allowlist must be `local`, `dev`, `staging`, or `test` — **`prod`/`production` is not a valid value.** There is no override that authorizes scanning a production host; a missing, malformed, or out-of-range allowlist entry fails closed.

Tools:
- `scan_passive({ target })` — missing security headers, cookie flags, CORS misconfiguration, exposed sensitive paths, server/framework fingerprinting.
- `scan_active({ target, categories?, confirm, authorization })` — reflected XSS, SQL/NoSQL injection, open redirect, path traversal, JWT `alg:none`, bounded IDOR probes, and an SSRF timing signal. `confirm: true` and a non-empty `authorization` (e.g. a ticket reference) are required and stamped into the report as an audit trail.
- `get_scan_report({ scanId })` — retrieves a previously written report.

A shared circuit breaker aborts the rest of a scan the moment the target's error rate or latency degrades sharply. Reports are written to `security-scans/<host>-<timestamp>.json` and `.md` in the calling process's working directory. See `mcp/security-scanner/README.md` for full development docs.

## Requirements

- Node.js ≥ 18
