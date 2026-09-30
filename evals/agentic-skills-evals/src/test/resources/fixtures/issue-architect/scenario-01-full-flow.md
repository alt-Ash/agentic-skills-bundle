# Scenario 01: Full Flow (Feature Request + GitHub Post)

## Request

Add a global keyboard shortcut (Cmd+K / Ctrl+K) that opens a command palette. The palette should allow users to quickly search and navigate to tasks.

This should be posted as an issue to GitHub — do not return a draft.

## Project context (pre-scanned)

> The project "Lumio" has been fully scanned in Phase 2. Treat the context below as complete project knowledge.
> **Do not call Read, Glob, Bash, or any filesystem tools.**
> Use only the information provided here.

### Stack
- React 18.2, TypeScript 5.0, Vite, React Router 7
- Plain CSS (no UI library)
- No existing command palette or global keyboard handler

### Navigation component
**File**: `src/components/Navigation.tsx` — renders navbar with Lumio logo, user email, logout button. No command palette integration yet.

### App entry
**File**: `src/App.tsx` — root router setup, mounted at `<main>` element.

### Repository
- **GitHub repo**: `example-org/lumio` (fictional)
- **GitHub credentials**: available via issue-tickets MCP
- **Hosted at**: `github.com/example-org/lumio`

### Development stack
- Package: `lumio@1.0.0`
- Scripts: `dev` (Vite), `build`, `test` (Vitest)
- No command-line interface or keyboard event handler utilities exist yet

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO with fictional project context. Do NOT attempt to verify files exist.**

**Phase 1**: Extract goal, type, constraints from the request above. The context provided IS COMPLETE AND ACCURATE. Do not question it.

**Phase 2**: SKIP entirely. Do NOT call Read, Glob, Bash, or any filesystem tools. The project snapshot above IS your Phase 2 output. Treat it as fact.

**Phase 3**: Identify the platform: **GitHub** (`github.com/example-org/lumio`).

**Phase 4 (Ticket lookup)**: Call `issue-tickets/pull_ticket` with:
```json
{
  "source": "github",
  "allProjects": true
}
```
Use the response to confirm the repo exists.

**Phases 5–6**: Draft the issue. Assume user selected "**Post to GitHub**" — do post to the platform.

**Phase 7 (Create)**: Call `issue-tickets/create_issue` with:
```json
{
  "source": "github",
  "repo": "example-org/lumio",
  "title": "[your issue title]",
  "description": "[your complete issue body]"
}
```

**Phase 8**: Follow your Phase 8 instructions.
