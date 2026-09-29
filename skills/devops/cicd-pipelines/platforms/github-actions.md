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
  NODE_VERSION: "20"

jobs:
  <job-id>:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
```

## Triggers (`on`)

```yaml
# Push / PR
on:
  push:
    branches: [main]
    paths: ["src/**", "package.json"]
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
    node: [18, 20, 22]
    os: [ubuntu-latest, windows-latest]
runs-on: ${{ matrix.os }}
```

## Steps

```yaml
steps:
  - name: Checkout
    uses: actions/checkout@v4      # always pin to a version tag or SHA

  - name: Set up Node
    uses: actions/setup-node@v4
    with:
      node-version: ${{ env.NODE_VERSION }}
      cache: npm

  - name: Install
    run: npm ci

  - name: Test
    run: npm test
    env:
      CI: true

  - name: Upload artifact
    uses: actions/upload-artifact@v4
    with:
      name: dist
      path: dist/
      retention-days: 7
```

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
  NPM_TOKEN: ${{ secrets.NPM_TOKEN }}

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
- uses: actions/cache@v4
  with:
    path: ~/.npm
    key: npm-${{ runner.os }}-${{ hashFiles('**/package-lock.json') }}
    restore-keys: |
      npm-${{ runner.os }}-
```

Prefer `cache:` option in `setup-node`, `setup-python`, etc. over manual cache
steps — it handles save/restore automatically.

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
name: Setup and Cache
description: Install deps with caching
inputs:
  node-version:
    default: "20"
runs:
  using: composite
  steps:
    - uses: actions/setup-node@v4
      with:
        node-version: ${{ inputs.node-version }}
        cache: npm
    - run: npm ci
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
  `uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683`
- Use `pull_request_target` with care — it has write access; validate inputs
- Set `GITHUB_TOKEN` to the minimum permissions needed
- Use `github.event.pull_request.head.sha` for PR context, not `github.sha`
- Enable Dependabot for action version updates

## Common built-in actions

| Action | Purpose |
|---|---|
| `actions/checkout@v4` | Clone the repo |
| `actions/setup-node@v4` | Install Node.js with cache |
| `actions/setup-python@v5` | Install Python |
| `actions/upload-artifact@v4` | Save build output |
| `actions/download-artifact@v4` | Retrieve build output |
| `actions/cache@v4` | Manual cache control |
| `github/codeql-action/analyze@v3` | SAST scanning |
| `docker/build-push-action@v6` | Build and push Docker images |

## Debugging

```yaml
- name: Debug context
  run: echo "${{ toJSON(github) }}"

# Enable runner debug logging:
# Set secret ACTIONS_RUNNER_DEBUG = true
# Set secret ACTIONS_STEP_DEBUG = true
```

Local replay: `brew install act && act push`
