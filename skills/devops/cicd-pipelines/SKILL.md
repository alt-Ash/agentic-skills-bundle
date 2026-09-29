---
name: cicd-pipelines
description:
  Expert on CI/CD pipeline authoring, debugging, and best practices for GitHub
  Actions and Azure DevOps Pipelines. Use when the user asks to create, fix,
  optimize, or review a CI/CD pipeline, workflow file, or build configuration.
---

# CI/CD Pipelines Expert

## Token Discipline

Load only platform guide(s) for platform(s) involved. Load [references/full-guide.md](references/full-guide.md) only for the previous full primary workflow.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## Platform Router

| Platform | Load |
|---|---|
| GitHub Actions | [platforms/github-actions.md](platforms/github-actions.md) |
| Azure DevOps | [platforms/azure-devops.md](platforms/azure-devops.md) |

If platform is unclear, ask before proceeding.

## Workflow

1. Identify platform and task: create, fix, optimize, or review.
2. Load only relevant platform guide(s).
3. Audit existing pipeline or gather requirements.
4. Implement or recommend minimal safe changes.
5. Validate syntax, ordering, permissions, secrets, and deployment logic.
6. Emit compact handoff with risks and checks.

## Core Rules

- Never hard-code secrets; use platform secret stores.
- Pin third-party actions/tasks to commit SHA when security matters.
- Apply least privilege to tokens and service connections.
- Put cheap checks before expensive jobs; parallelize independent work.
- Cache by lockfile hash, set job timeouts, and avoid unnecessary checkouts.
- Build once, publish artifact, and promote it across environments.
- Keep repeated logic in reusable workflows/templates.

Compact context:

```text
Context: cicd-pipelines; platform=<GitHub Actions|Azure DevOps|both>; task=<create|fix|optimize|review>; stack=<name>; files=<read>; gaps=<items>
```

Compact handoff:

```text
Handoff: cicd-pipelines; status=<completed|partial|blocked>; changed=<files>; checks=<syntax/tests>; blockers=<none|items>; next=<risk summary>
```

## HANDOFF BLOCK template

After completing the pipeline work, emit:

```
***HANDOFF BLOCK***
Skill/Agent : cicd-pipelines
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- <Pipeline created | fixed | optimized | reviewed>
- Platform: <GitHub Actions | Azure DevOps>
- Trigger: <push | PR | schedule | manual>

### Artifacts produced
| File | Change |
|------|--------|
| <pipeline file path> | created / modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| typecheck | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <anything that could not be resolved, with reason | "—">

### For the next agent or step
<Summary of pipeline state and any remaining risks or next actions>
***END HANDOFF BLOCK***
```
