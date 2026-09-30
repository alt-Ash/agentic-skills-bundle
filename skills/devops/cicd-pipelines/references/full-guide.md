---
name: cicd-pipelines
description:
  Expert on CI/CD pipeline authoring, debugging, and best practices for GitHub
  Actions and Azure DevOps Pipelines. Use when the user asks to create, fix,
  optimize, or review a CI/CD pipeline, workflow file, or build configuration.
version: "1.0.0"
category: devops
---

# CI/CD Pipelines Expert

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## Platform reference

Load the relevant platform guide before authoring or reviewing pipelines:

| Platform | Guide |
|---|---|
| GitHub Actions | [platforms/github-actions.md](platforms/github-actions.md) |
| Azure DevOps | [platforms/azure-devops.md](platforms/azure-devops.md) |

Load the guide(s) for the platform(s) involved. If the user has not specified a
platform, ask before proceeding.

## Workflow

```
Pipeline Task Progress:
- [ ] 1. Identify platform and task (create / fix / review / optimize)
- [ ] 2. Load platform guide
- [ ] 3. Audit existing pipeline or gather requirements
- [ ] 4. Implement or recommend changes
- [ ] 5. Validate syntax and logic
- [ ] 6. Summarise changes and remaining risks
```

## Core principles

**Security**
- Never hard-code secrets — use platform secret stores
- Pin third-party actions/tasks to a commit SHA, not a tag
- Apply least-privilege to tokens and service connections
- Scan dependencies and images in the pipeline

**Reliability**
- Fail fast: put cheap, quick checks first (lint → test → build → deploy)
- Use caching for dependencies (Maven `~/.m2`, Gradle `~/.gradle`, or pip/npm for other stacks)
- Set explicit timeouts on every job
- Always clean up temporary credentials and resources

**Maintainability**
- Extract repeated logic into reusable workflows / templates
- Keep pipeline files under 200 lines; split stages into separate files
- Name every step clearly; add comments only when non-obvious
- Use matrix builds to test multiple versions/platforms in parallel

**Performance**
- Parallelize independent jobs
- Cache aggressively (hash lock files, not directories)
- Use the smallest runner/agent that meets the job's needs
- Avoid unnecessary checkouts in jobs that don't need source

## Common patterns

### Branch strategy triggers

Only run expensive jobs on `main`/release branches; run fast checks on every PR.

### Secrets and environment promotion

Use environment-scoped secrets and require manual approval gates for production
deployments.

### Artifact promotion

Build once, publish the artifact, then download it in deploy stages — never
rebuild for each environment.

### Notifications

Add a final step that always runs (`if: always()` / `condition: always()`) to
post success/failure status to Slack, Teams, or email.

## Diagnostics checklist

When a pipeline fails:

1. Read the full error — not just the last line
2. Check runner/agent logs for system-level errors
3. Verify secrets and service connections are set in the correct scope
4. Confirm the trigger fired as expected (branch filters, path filters)
5. Check for rate limits or quota issues on the runner pool
6. Reproduce locally if possible (`act` for GitHub Actions)

## CONTEXT BLOCK template

After gathering requirements, emit:

```
***CONTEXT BLOCK***
Skill     : cicd-pipelines
Platform  : <GitHub Actions | Azure DevOps | both>
Task      : <create | fix | optimize | review>
Repo      : <monorepo | single-app>
Stack     : <Java/Spring Boot (Maven | Gradle) | Node.js | Python | .NET | other>
Envs      : <dev | staging | production>
Runners   : <ubuntu-latest | self-hosted | windows | macos>
Secrets   : <GitHub Secrets | Azure Key Vault | other>
Existing  : <yes — file path | no>
Gaps      : <anything unclear>
***END CONTEXT BLOCK***
```
