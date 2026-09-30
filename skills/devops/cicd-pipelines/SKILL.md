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
- Cache by build-file hash (`pom.xml`, `*.gradle*`, `gradle-wrapper.properties`), set job timeouts, and avoid unnecessary checkouts.
- Build once, publish artifact, and promote it across environments.
- Keep repeated logic in reusable workflows/templates.

Compact context:

```text
Context: cicd-pipelines; platform=<GitHub Actions|Azure DevOps|both>; task=<create|fix|optimize|review>; stack=<java-maven|java-gradle|other>; files=<read>; gaps=<items>
```

Compact handoff:

```text
Handoff: cicd-pipelines; status=<completed|partial|blocked>; changed=<files>; checks=<syntax/tests>; blockers=<none|items>; next=<risk summary>
```

## CONTEXT BLOCK template

When full blocks are required, emit this after gathering requirements and before editing any pipeline file:

```
***CONTEXT BLOCK***
Skill/Agent : cicd-pipelines
Timestamp   : <ISO-8601 date>

### Pipeline
- Platform        : <GitHub Actions | Azure DevOps | both>
- Task            : <create | fix | optimize | review>
- Existing file   : <path, e.g. .github/workflows/ci.yml or azure-pipelines.yml | none>
- Triggers        : <push | PR | schedule | manual | workflow_call>
- Runners/agents  : <ubuntu-latest | windows-latest | self-hosted pool>

### Project
- Type            : <Spring Boot API | Quarkus | Micronaut | monorepo | other stack>
- Build tool      : <Maven (./mvnw) | Gradle (./gradlew) | other>
- JDK version     : <version + distribution, e.g. "21 temurin" | "—">
- Test runner     : <JUnit 5 | JUnit 5 + Testcontainers | none>
- Artifact        : <jar | container image | none>

### Delivery
- Environments    : <dev | staging | production | none>
- Secrets store   : <GitHub Secrets/Environments | Azure Key Vault / Variable Group | other>
- Deploy target   : <App Service | AKS | container registry | Maven repository | none>

### Files read
- <pipeline file(s), pom.xml / build.gradle*, Dockerfile, ...>

### Gaps / unknowns
- <description or "none">
***END CONTEXT BLOCK***
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
| yaml syntax | ✅ passed / ❌ failed / ⚪ n/a |
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <anything that could not be resolved, with reason | "—">

### For the next agent or step
<Summary of pipeline state and any remaining risks or next actions>
***END HANDOFF BLOCK***
```
