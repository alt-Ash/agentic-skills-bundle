# Azure DevOps Pipelines Reference

Schema ref: https://learn.microsoft.com/en-us/azure/devops/pipelines/yaml-schema/

## File location

`azure-pipelines.yml` (root) or any path configured in the pipeline definition.

## Top-level structure

```yaml
trigger:
  branches:
    include: [main]

pr:
  branches:
    include: [main]

variables:
  nodeVersion: "20.x"

pool:
  vmImage: ubuntu-latest

stages:
  - stage: Build
    jobs:
      - job: BuildApp
        steps:
          - checkout: self
```

## Triggers

```yaml
# CI trigger
trigger:
  branches:
    include: [main, release/*]
    exclude: [experimental/*]
  paths:
    include: [src/**]
  tags:
    include: ["v*"]

# PR trigger
pr:
  autoCancel: true
  branches:
    include: [main]

# Disable trigger
trigger: none
pr: none

# Scheduled
schedules:
  - cron: "0 2 * * 1-5"
    displayName: Nightly
    branches:
      include: [main]
    always: false              # only run if code changed
```

## Stages, jobs, steps hierarchy

```
pipeline
  └── stages[]
        └── jobs[]
              └── steps[]
```

All three levels are optional — a pipeline can be just `steps:` at the top level
for simple cases, but prefer explicit stages for production pipelines.

## Stages

```yaml
stages:
  - stage: Build
    displayName: Build & Test
    jobs:
      - job: Compile
        steps: [...]

  - stage: Deploy
    displayName: Deploy to Staging
    dependsOn: Build
    condition: succeeded()
    jobs:
      - deployment: DeployWeb
        environment: staging
        strategy:
          runOnce:
            deploy:
              steps: [...]
```

## Jobs

```yaml
jobs:
  - job: Test
    displayName: Run Tests
    pool:
      vmImage: ubuntu-latest
    timeoutInMinutes: 20
    variables:
      TEST_ENV: ci
    steps: [...]

  # Matrix
  - job: MatrixTest
    strategy:
      matrix:
        node18:
          nodeVersion: "18.x"
        node20:
          nodeVersion: "20.x"
      maxParallel: 2
    steps:
      - task: NodeTool@0
        inputs:
          versionSpec: $(nodeVersion)
```

## Deployment jobs

```yaml
- deployment: DeployProd
  displayName: Deploy to Production
  environment: production          # triggers approval gates if configured
  pool:
    vmImage: ubuntu-latest
  strategy:
    runOnce:
      deploy:
        steps:
          - download: current
            artifact: drop
          - script: ./deploy.sh
```

Strategies: `runOnce` | `rolling` | `canary`

## Steps

```yaml
steps:
  - checkout: self
    fetchDepth: 1                  # shallow clone for speed

  - task: NodeTool@0
    inputs:
      versionSpec: $(nodeVersion)

  - script: npm ci
    displayName: Install dependencies

  - bash: |
      npm test
      echo "Tests done"
    displayName: Run tests
    env:
      CI: true

  - pwsh: Write-Host "Windows step"

  - task: PublishTestResults@2
    inputs:
      testResultsFormat: JUnit
      testResultsFiles: "**/junit.xml"

  - task: PublishPipelineArtifact@1
    inputs:
      targetPath: dist
      artifact: drop

  - download: current
    artifact: drop
    patterns: "**/*.zip"
```

### Condition on steps

```yaml
- script: ./notify-failure.sh
  condition: failed()
  displayName: Notify on failure

- script: ./deploy.sh
  condition: and(succeeded(), eq(variables['Build.SourceBranch'], 'refs/heads/main'))
```

Condition functions: `succeeded()` | `failed()` | `always()` | `canceled()` |
`and(...)` | `or(...)` | `eq(var, val)` | `ne(...)` | `contains(...)`

## Variables

```yaml
variables:
  - name: appName
    value: my-app
  - group: my-variable-group      # from Library
  - template: vars/common.yml     # from template file

# Runtime expression
- script: echo $(appName)

# Macro syntax (compile-time)
- script: echo ${{ variables.appName }}

# Output variables (set from a step)
- bash: echo "##vso[task.setvariable variable=MY_VAR;isOutput=true]hello"
  name: setVar

- bash: echo $(setVar.MY_VAR)
```

## Secrets

Store in Pipeline → Variables (mark as secret) or in a Variable Group linked to
Azure Key Vault.

```yaml
variables:
  - group: production-secrets     # contains NPM_TOKEN, DATABASE_URL, etc.

steps:
  - script: npm publish
    env:
      NPM_TOKEN: $(NPM_TOKEN)     # never use $(SECRET) directly in script args
```

Never print secret variables — ADO masks them but logging is bad practice.

## Templates

**Step template (`templates/install.yml`):**
```yaml
parameters:
  - name: nodeVersion
    type: string
    default: "20.x"

steps:
  - task: NodeTool@0
    inputs:
      versionSpec: ${{ parameters.nodeVersion }}
  - script: npm ci
```

**Usage:**
```yaml
steps:
  - template: templates/install.yml
    parameters:
      nodeVersion: "22.x"
```

Templates also work for `jobs:`, `stages:`, and `variables:`.

## Environments and approvals

Create environments in Pipelines → Environments. Add checks:
- **Approvals** — named users/groups must approve before deployment
- **Branch control** — only allow deploys from `main`
- **Business hours** — restrict to working hours

```yaml
- deployment: Deploy
  environment:
    name: production
    resourceType: VirtualMachine  # or Kubernetes
```

## Pools

```yaml
# Microsoft-hosted
pool:
  vmImage: ubuntu-latest          # or windows-latest, macos-latest

# Self-hosted
pool:
  name: MyAgentPool
  demands:
    - Agent.OS -equals Linux
    - docker
```

## Resources

```yaml
resources:
  repositories:
    - repository: templates
      type: git
      name: MyOrg/pipeline-templates
      ref: refs/heads/main

  pipelines:
    - pipeline: upstream
      source: "My Upstream Pipeline"
      trigger:
        branches:
          include: [main]

  containers:
    - container: node20
      image: node:20-alpine
```

## Caching

```yaml
- task: Cache@2
  inputs:
    key: 'npm | "$(Agent.OS)" | package-lock.json'
    restoreKeys: |
      npm | "$(Agent.OS)"
    path: $(npm_config_cache)
  displayName: Cache npm
```

## Security hardening

- Use service connections (not raw credentials) for cloud deployments
- Grant service connections only the permissions needed for the job
- Enable branch policies — require PR reviews before merging to `main`
- Use Variable Groups linked to Azure Key Vault for secrets rotation
- Pin task versions (`NodeTool@0` → specify exact version in task properties)
- Enable audit logging in the ADO organisation settings

## Common tasks

| Task | Purpose |
|---|---|
| `NodeTool@0` | Install Node.js |
| `UsePythonVersion@0` | Install Python |
| `DotNetCoreCLI@2` | .NET build/test/publish |
| `Maven@4` / `Gradle@3` | Java builds |
| `Docker@2` | Build/push Docker images |
| `KubernetesManifest@1` | Deploy to AKS |
| `AzureWebApp@1` | Deploy to App Service |
| `AzureFunctionApp@2` | Deploy to Azure Functions |
| `PublishTestResults@2` | Publish JUnit/NUnit results |
| `PublishCodeCoverageResults@2` | Publish coverage |
| `PublishPipelineArtifact@1` | Upload artifact |
| `DownloadPipelineArtifact@2` | Download artifact |
| `Cache@2` | Cache dependencies |

## Diagnostics

```yaml
- bash: env | sort
  displayName: Dump environment variables

# Enable system diagnostics:
# Add variable: System.Debug = true
```

Common failure causes:
- Service connection expired or missing permissions
- Variable group not linked to the pipeline
- Agent pool capacity — check agent queue in Organisation Settings
- `dependsOn` stage not reached (skipped counts as not succeeded)
- Artifact name mismatch between publish and download steps
