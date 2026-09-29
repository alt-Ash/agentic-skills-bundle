---
name: strapi-version-migrator
description: Guides safe Strapi version upgrades by applying official migration guides step by step. Use when the user wants to upgrade Strapi, migrate between Strapi versions, or asks about Strapi breaking changes between versions.
---

# Strapi Version Migrator

## Token Discipline

Load only the migration guide for the required Strapi hop. Keep API consumer details scoped to projects that actually expose REST or GraphQL consumers.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## Workflow

Copy this checklist and track progress:

```
Strapi Migration Progress:
- [ ] 1. Identify current and target Strapi versions
- [ ] 2. Read the relevant migration guide(s)
- [ ] 3. Ensure all plugins are compatible with the target version
- [ ] 4. Backup database and code
- [ ] 5. Run the automated upgrade tool
- [ ] 6. Fix any __TODO__ items left by codemods
- [ ] 7. Apply remaining manual breaking-change fixes
- [ ] 8. Migrate API consumers (REST and/or GraphQL)
- [ ] 9. Run tests and verify
- [ ] 10. Run build and verify
```

## Available migration guides

| From → To | Guide |
|---|---|
| v4 → v5 | [v4-to-v5.md](v4-to-v5.md) |

Emit compact context after version detection:

```text
Context: strapi-version-migrator; Strapi app; pkg=<manager>; ts=<yes|no>; versions=Strapi <from> -> <to>; files=<read>; gaps=<items>
```

## Step 1 — Identify versions

```bash
node -e "const p = require('./package.json'); console.log(p.dependencies?.['@strapi/strapi'] || p.devDependencies?.['@strapi/strapi'] || 'not found')"
```

## Step 2 — Read the migration guide

Load the appropriate file for the version pair. It contains the automated upgrade steps, breaking changes, and required manual updates.

## Step 3 — Check plugin compatibility

Before upgrading, verify every plugin in use is compatible with the target Strapi version. Check the [Strapi Marketplace](https://market.strapi.io/plugins?version=v5) for compatibility status.

Incompatible plugins will block the upgrade — resolve these before proceeding.

## Step 4 — Backup

- **Database**: copy `data.db` from `.tmp/` (SQLite) or follow PostgreSQL/MySQL docs.
- **Code**: create a dedicated git branch, or copy the project to a safe location if not using git.

## Step 5 — Run the automated upgrade tool

```bash
# Ensure you are on the latest v4 patch first
npx @strapi/upgrade minor

# Then run the major upgrade
npx @strapi/upgrade major
```

The upgrade tool updates dependencies and runs codemods that handle a large portion of the breaking changes automatically.

## Step 6 — Fix __TODO__ items from codemods

After the upgrade tool runs, search the codebase for `__TODO__` comments left by codemods:

```bash
grep -r "__TODO__" src/ --include="*.ts" --include="*.js"
```

These typically appear in files where the codemod could only partially migrate from the Entity Service API to the Document Service API. Each must be resolved manually.

Refer to the [Entity Service → Document Service migration guide](https://docs.strapi.io/cms/migration/v4-to-v5/additional-resources/from-entity-service-to-document-service) and the [Document Service API reference](https://docs.strapi.io/cms/api/document-service).

## Step 7 — Apply remaining manual fixes

Work through the breaking changes listed in the migration guide, paying special attention to:

- **Database**: MySQL v5 dropped; SQLite must use `better-sqlite3`; MySQL must use `mysql2`
- **Lifecycle hooks**: now triggered by Document Service API methods, not Entity Service
- **Configuration**: some env-only options moved to server config; config filenames have strict requirements
- **Admin panel**: `@strapi/helper-plugin` removed — see the [helper-plugin migration reference](https://docs.strapi.io/cms/migration/v4-to-v5/additional-resources/helper-plugin)
- **Content API**: `id` replaced by `documentId`; response format is flattened (no `data.attributes` wrapper); `publicationState` replaced by `status`

## Step 8 — Migrate API consumers

### REST

Use the retro-compatibility header during the transition:

```http
Strapi-Response-Format: v4
```

Add this header to all HTTP clients, SDKs, and middleware that still expect the v4 `data.attributes` wrapper. Then migrate each consumer:

1. Remove `data.attributes` access — use the flat response directly.
2. Replace numeric `id` with `documentId` everywhere.
3. Remove `Strapi-Response-Format: v4` once the consumer is updated and tests pass.

### GraphQL

Enable compatibility mode in `config/plugins.js` during transition:

```js
module.exports = {
  graphql: {
    config: {
      v4CompatibilityMode: true,
    },
  },
};
```

Then migrate each query/mutation:

1. Swap `id` → `documentId`.
2. Replace `data { attributes { ... } }` with flat field selection.
3. Migrate pagination to `nodes` / `pageInfo` (drop `_connection` wrapper for non-Relay clients).
4. Set `v4CompatibilityMode: false` once all consumers are updated.

## Step 9 — Verify tests

```bash
npm test
# or
yarn test
```

## Step 10 — Verify build

```bash
npm run build
# or
yarn build
```

Compact handoff:

```text
Handoff: strapi-version-migrator; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=Strapi <from> -> <to>, api-consumers=<REST|GraphQL|none>
```
