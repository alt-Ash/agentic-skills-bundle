# agentic-skills-bundle — AI Agent Context

## What this project is

This is a **skill and agent authoring and distribution package** — `agentic-skills-bundle`.

Its purpose is to **create, maintain, and publish** AI agent skills, slash commands, and sub-agents so that downstream consumers can install them into their own projects or global AI agent configs via the CLI tool (`agentic-skills` / `bin/install.js`).

This is NOT a project that uses skills. It is the project that PRODUCES them.

---

## Critical rule: never touch or read global config

**Do not read, inspect, modify, or reference any global AI agent configuration at any time.**

Global paths such as the following are completely out of scope:

- `~/.config/opencode/`
- `~/.config/opencode/skills/`
- `~/.config/opencode/agents/`
- `~/.config/opencode/commands/`
- `~/.claude/`
- `~/.cursor/`
- `~/.vscode/`
- `~/.codeium/`
- `~/.config/zed/`

These paths exist only inside `bin/install.js` as **install targets** — destination paths the CLI writes to when a user runs the installer in their own environment. They are not relevant to development work on this repository.

**Do not validate, check, or troubleshoot anything based on the absence or presence of files in any global config path.**

---

## What "skills", "agents", and "commands" mean in this project

When working in this repository, these terms always refer to the **source files inside this repo**, never to installed copies elsewhere:

| Term | Location in this repo | Description |
|---|---|---|
| **Skill** | `skills/<category>/<skill-name>/` | A directory containing a `SKILL.md` and optional supporting files. This is the content that gets installed. |
| **Agent** | `agents/<agent-name>.md` | A markdown file defining a sub-agent persona. Installed as `@agent-name` in supported tools. |
| **Command** | `.opencode/commands/<command-name>.md` | A slash command markdown file — the single source installed to every tool with `supportsCommands: true` in `bin/install.js`'s `AGENTS` registry (OpenCode and Claude Code). Paired with skills via `SKILL_COMMANDS` in `bin/install.js`. |

---

## Project structure

```
agentic-skills-bundle/
├── bin/
│   └── install.js          # CLI installer — the tool that copies skills/agents/commands
│                           # to target environments. This is what gets published as agentic-skills.
├── skills/
│   ├── frontend/           # Frontend skill directories (each is an installable skill)
│   │   ├── cra-to-vite/
│   │   ├── msw-mocking/
│   │   ├── mui-migration/
│   │   ├── react-best-practices/
│   │   ├── react-migration/
│   │   └── vite-version-migrator/
│   └── backend/            # Backend skill directories
│       └── nodejs-version-migrator/
├── agents/
│   └── react-browser-debugger.md   # Sub-agent definition files
├── .opencode/
│   └── commands/           # Slash command markdown files for OpenCode
│       └── *.md
├── package.json            # npm package: agentic-skills-bundle
└── README.md
```

---

## How skills are structured

Each skill is a plain directory. Drop a new folder under `skills/<category>/` and it is automatically discovered by the installer — no changes to `bin/install.js` are needed.

Recommended structure for a new skill:

```
skills/
  <category>/
    <skill-name>/
      SKILL.md          # Required. Instructions and context for the AI agent that uses this skill.
      ...               # Optional supporting files: migration guides, scripts, reference docs.
```

The `SKILL.md` file is the primary artifact. Write it as instructions for an AI agent that will use the skill after it has been installed into another project.

---

## How companion commands are paired with skills

The mapping is in `bin/install.js` under `SKILL_COMMANDS`:

```js
const SKILL_COMMANDS = {
  'nodejs-version-migrator': ['migrate-node'],
  'cra-to-vite': ['cra-to-vite'],
  'vite-version-migrator': ['migrate-vite'],
  'mui-migration': ['migrate-mui'],
  'react-migration': ['migrate-react'],
};
```

If a skill has a companion slash command, add the command markdown file to `.opencode/commands/` and register the mapping here.

---

## How to validate work in this project

When checking for errors or validating changes:

- **Skill content errors** → check the `SKILL.md` file inside the relevant `skills/` subdirectory
- **CLI installer errors** → check `bin/install.js`
- **Missing command file** → check `.opencode/commands/` and the `SKILL_COMMANDS` map in `bin/install.js`
- **Missing agent file** → check `agents/`
- **Package errors** → check `package.json` and `pnpm-lock.yaml`

Never diagnose a problem as "the skill is not in the correct folder" by checking global config paths. The only correct location for skills, agents, and commands during development is inside this repository's own directories listed above.

---

## The installer (bin/install.js)

The CLI prompts the user to choose:

1. **Install target** — global agent config or a specific project directory
2. **AI agent** — OpenCode, Claude Code, Cursor, VS Code, Windsurf, or Zed AI
3. **Skills** — any combination from `skills/`
4. **Agents** — any combination from `agents/` (for agents that support sub-agents)
5. **Commands** — optional slash commands paired with selected skills or agents (for tools with `supportsCommands: true` — currently OpenCode and Claude Code)

It then copies the selected files to the appropriate destination in the user's environment. This repository is the **source**; the user's environment is the **destination**.
