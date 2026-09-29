---
name: nodejs-version-migrator
description: Guides safe Node.js version upgrades by auditing breaking changes, running automated codemods, and updating configs. Use when the user wants to upgrade Node.js versions, migrate from one Node version to another, or asks about Node.js breaking changes between versions.
---

# Node.js Version Migrator

## Token Discipline

Load this primary router first. Load detailed docs only for the required hop or detected concern.

| Condition | Load |
|---|---|
| Need full legacy workflow, Docker/native addon tables, package audit decision tree, or license detail | [references/full-guide.md](references/full-guide.md) |
| Node v14 -> v16 | [migrations/v14-to-v16.md](migrations/v14-to-v16.md) |
| Node v16 -> v20 | [migrations/v16-to-v20.md](migrations/v16-to-v20.md) |
| Node v20 -> v22 | [migrations/v20-to-v22.md](migrations/v20-to-v22.md) |
| Node v22 -> v24 | [migrations/v22-to-v24.md](migrations/v22-to-v24.md) |
| NestJS detected | [framework-migrations/nestjs/SKILL.md](framework-migrations/nestjs/SKILL.md) |
| Strapi detected | [framework-migrations/strapi/SKILL.md](framework-migrations/strapi/SKILL.md) |

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Identify current and target Node versions from user prompt, `node --version`, `package.json`, `.nvmrc`, `.node-version`, Docker, and CI only as needed.
2. Determine migration hops. Never skip an intermediate guide when one exists.
3. Detect framework packages that may impose Node requirements: `@nestjs/*`, `@strapi/strapi`, Express/Fastify majors.
4. Emit compact context: `Context: nodejs-version-migrator; <project type>; pkg=<manager>; ts=<yes|no>; versions=Node <from> -> <to>; files=<read>; gaps=<items>`.

## Workflow

| Step | Action |
|---|---|
| 1 | Load only the migration guide(s) for required Node hops |
| 2 | Audit deprecated/removed patterns listed in loaded guide(s) |
| 3 | Run codemods from loaded guide(s), if any |
| 4 | If framework upgrade is required, run framework migrator before package/tooling finalization |
| 5 | Audit direct dependency `engines.node` compatibility for target Node |
| 6 | Update `package.json` engines and version files |
| 7 | Update CI, Docker, and tool configs where present |
| 8 | Run install, tests, build, and Docker build where available |
| 9 | Run license scan only if project has a license field and dependency changes are final |

## Critical Rules

- Do not proceed to the next hop until tests/build for the current hop are green or blocked with reason.
- Update final runtime declarations (`engines`, `.nvmrc`, CI, Docker) consistently.
- Native addons can fail after base image or Node changes; load full guide if `binding.gyp`, `gypfile`, `sharp`, `canvas`, `bcrypt`, `node-sass`, or `node-gyp` appears.
- Do not blindly bump major dependency versions; read package changelog/migration notes first.
- If any required package cannot support target Node, stop and report blocker.

## Minimal Commands

```bash
node --version
node -e "const p=require('./package.json'); console.log(p.engines?.node || 'no engines.node')"
node <skill-dir>/scripts/check-node-compat.js <TARGET_VERSION>
```

Use the compatibility script path resolved by the agent environment.

## Done When

- Runtime declarations and tooling point to the target Node version.
- Loaded migration-guide breaking changes are handled.
- Framework requirements are satisfied.
- Available tests, build, Docker build, and license scan pass or blockers are reported.

Compact handoff:

```text
Handoff: nodejs-version-migrator; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=Node <from> -> <to>, framework=<name|none>, package blockers=<items>
```
