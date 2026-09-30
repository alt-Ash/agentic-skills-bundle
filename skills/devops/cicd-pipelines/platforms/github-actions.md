# GitHub Actions Reference

Ref: https://docs.github.com/en/actions/reference

## File location

`.github/workflows/<name>.yml`

## Top-level structure

```yaml
name: CI

on: <trigger>

permissions:
  contents: read        # always set explicitly

env:
  JAVA_VERSION: "21"

jobs:
  <job-id>:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
```

## Triggers (`on`)

```yaml
# Push / PR
on:
  push:
    branches: [main]
    paths: ["src/**", "pom.xml", "build.gradle*", "settings.gradle*", "gradle/**"]
  pull_request:
    branches: [main]
    types: [opened, synchronize, reopened]

# Manual
on:
  workflow_dispatch:
    inputs:
      environment:
        type: choice
        options: [staging, production]

# Schedule (UTC cron)
on:
  schedule:
    - cron: "0 2 * * 1-5"

# Reusable workflow call
on:
  workflow_call:
    inputs:
      version:
        type: string
        required: true
    secrets:
      TOKEN:
        required: true
```

## Jobs

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    outputs:
      artifact-name: ${{ steps.set-output.outputs.name }}
    steps: [...]

  deploy:
    needs: build                  # sequential dependency
    runs-on: ubuntu-latest
    environment: production       # requires approval if configured
    steps: [...]
```

### Matrix

```yaml
strategy:
  fail-fast: false
  matrix:
    java: ["17", "21", "25"]
    os: [ubuntu-latest, windows-latest]
runs-on: ${{ matrix.os }}
```

## Steps

```yaml
steps:
  - name: Checkout
    uses: actions/checkout@v7      # always pin to a version tag or SHA

  - name: Set up JDK
    uses: actions/setup-java@v6
    with:
      distribution: temurin          # or zulu, corretto, microsoft, liberica, ...
      java-version: ${{ env.JAVA_VERSION }}
      cache: maven                   # or gradle / sbt — keyed on pom.xml / *.gradle* etc.

  - name: Build and test (Maven)
    run: ./mvnw -B verify            # -B = batch mode, no interactive download progress

  # Gradle equivalent (use cache: gradle above):
  # - name: Build and test (Gradle)
  #   run: ./gradlew build

  - name: Upload artifact
    uses: actions/upload-artifact@v7
    with:
      name: app-jar
      path: target/*.jar             # build/libs/*.jar for Gradle
      retention-days: 7
```

`java-version-file: .java-version` can replace `java-version` to keep the JDK
version in one place. Prefer the project's wrapper (`./mvnw`, `./gradlew`) over
a runner-installed `mvn`/`gradle` so CI uses the pinned build-tool version.

### Conditional steps

```yaml
- name: Deploy
  if: github.ref == 'refs/heads/main' && success()
  run: ./deploy.sh

- name: Notify on failure
  if: failure()
  uses: slackapi/slack-github-action@v1
```

## Secrets and variables

```yaml
# Access secrets
env:
  MAVEN_REPO_TOKEN: ${{ secrets.MAVEN_REPO_TOKEN }}

# GitHub-provided variables
github.sha          # commit SHA
github.ref          # branch/tag ref
github.actor        # user who triggered
github.repository   # owner/repo
github.run_id       # unique run ID
```

Never echo secrets — they are masked but logging them is bad practice.

## Caching

```yaml
- uses: actions/cache@v6
  with:
    path: ~/.m2/repository
    key: maven-${{ runner.os }}-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      maven-${{ runner.os }}-
```

Prefer the `cache:` option in `setup-java` (`maven` | `gradle` | `sbt`), or
`setup-python`/`setup-node` etc. for other stacks, over manual cache steps — it
handles save/restore automatically and hashes the right build files
(`**/pom.xml`, `**/*.gradle*`, `**/gradle-wrapper.properties`, ...).

## Permissions (least privilege)

```yaml
permissions:
  contents: read
  pull-requests: write   # only if needed
  packages: write        # only for publish jobs
```

Set at the workflow level and override per-job if a job needs elevated access.

## Reusable workflows

**Caller:**
```yaml
jobs:
  call-deploy:
    uses: org/repo/.github/workflows/deploy.yml@main
    with:
      environment: production
    secrets: inherit          # or pass named secrets
```

**Callee (`.github/workflows/deploy.yml`):**
```yaml
on:
  workflow_call:
    inputs:
      environment:
        type: string
        required: true
```

## Composite actions (`.github/actions/<name>/action.yml`)

```yaml
name: Setup JDK and Cache
description: Install the JDK with Maven dependency caching and resolve deps
inputs:
  java-version:
    default: "21"
runs:
  using: composite
  steps:
    - uses: actions/setup-java@v6
      with:
        distribution: temurin
        java-version: ${{ inputs.java-version }}
        cache: maven
    - run: ./mvnw -B dependency:go-offline
      shell: bash
```

## Environments and deployment protection

Configure in repo Settings → Environments:
- Required reviewers for production
- Environment secrets (scoped, not available to other envs)
- Deployment branches (only `main` can deploy to production)

```yaml
jobs:
  deploy:
    environment:
      name: production
      url: https://myapp.com
```

## Security hardening

- Pin all third-party actions to a full commit SHA:
  `uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`
- Use `pull_request_target` with care — it has write access; validate inputs
- Set `GITHUB_TOKEN` to the minimum permissions needed
- Use `github.event.pull_request.head.sha` for PR context, not `github.sha`
- Enable Dependabot for action version updates

## Common built-in actions

| Action | Purpose |
|---|---|
| `actions/checkout@v7` | Clone the repo |
| `actions/setup-java@v6` | Install a JDK (`distribution` + `java-version`) with Maven/Gradle cache |
| `actions/setup-node@v7` / `actions/setup-python@v7` | Other stacks (Node.js, Python) |
| `actions/upload-artifact@v7` | Save build output |
| `actions/download-artifact@v8` | Retrieve build output |
| `actions/cache@v6` | Manual cache control |
| `github/codeql-action/analyze@v4` | SAST scanning |
| `docker/build-push-action@v7` | Build and push Docker images |

## Debugging

```yaml
- name: Debug context
  run: echo "${{ toJSON(github) }}"

# Enable runner debug logging:
# Set secret ACTIONS_RUNNER_DEBUG = true
# Set secret ACTIONS_STEP_DEBUG = true
```

Local replay: `brew install act && act push`
