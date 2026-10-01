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
  jdkVersion: "1.21"             # Maven@4 jdkVersionOption format

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
    include: [src/**, pom.xml, build.gradle*]
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
        jdk17:
          jdkVersion: "1.17"
        jdk21:
          jdkVersion: "1.21"
      maxParallel: 2
    steps:
      - task: Maven@4
        inputs:
          mavenPOMFile: pom.xml
          goals: verify
          javaHomeOption: JDKVersion
          jdkVersionOption: $(jdkVersion)
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

  # Maven: builds, runs tests, and publishes Surefire JUnit results in one task
  - task: Maven@4
    displayName: Build and test (Maven)
    inputs:
      mavenPOMFile: pom.xml
      goals: verify
      options: -B
      javaHomeOption: JDKVersion
      jdkVersionOption: $(jdkVersion)  # 'default' | '1.21' | '1.17' | '1.11' | ...
      publishJUnitResults: true
      testResultsFiles: "**/surefire-reports/TEST-*.xml"

  # Gradle equivalent (uses the project's wrapper). Gradle@4's jdkVersionOption
  # tops out at '1.17', so for JDK 21 point javaHomeOption at the hosted agent's
  # preinstalled JDK instead (JAVA_HOME_21_X64 on Microsoft-hosted images).
  # - task: Gradle@4
  #   inputs:
  #     gradleWrapperFile: gradlew
  #     tasks: build
  #     javaHomeOption: Path
  #     jdkDirectory: $(JAVA_HOME_21_X64)
  #     publishJUnitResults: true
  #     testResultsFiles: "**/TEST-*.xml"

  - bash: ./mvnw -B verify         # plain-script alternative to the Maven@4 task
    displayName: Run tests (wrapper)

  - pwsh: Write-Host "Windows step"

  - task: PublishTestResults@2     # only needed when tests ran outside Maven@4/Gradle@4
    inputs:
      testResultsFormat: JUnit
      testResultsFiles: "**/TEST-*.xml"

  - task: PublishPipelineArtifact@1
    inputs:
      targetPath: target            # build/libs for Gradle
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
  - group: production-secrets     # contains MAVEN_REPO_TOKEN, DATABASE_URL, etc.

steps:
  - script: ./mvnw -B deploy -s .mvn/settings.xml
    env:
      MAVEN_REPO_TOKEN: $(MAVEN_REPO_TOKEN)  # read via ${env.MAVEN_REPO_TOKEN} in settings.xml; never put $(SECRET) in script args
```

Never print secret variables — ADO masks them but logging is bad practice.

## Templates

**Step template (`templates/maven-build.yml`):**
```yaml
parameters:
  - name: jdkVersion
    type: string
    default: "1.21"

steps:
  - task: Maven@4
    inputs:
      mavenPOMFile: pom.xml
      goals: verify
      javaHomeOption: JDKVersion
      jdkVersionOption: ${{ parameters.jdkVersion }}
```

**Usage:**
```yaml
steps:
  - template: templates/maven-build.yml
    parameters:
      jdkVersion: "1.17"
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
    - container: jdk21
      image: eclipse-temurin:21-jdk
```

## Caching

```yaml
variables:
  MAVEN_CACHE_FOLDER: $(Pipeline.Workspace)/.m2/repository
  MAVEN_OPTS: '-Dmaven.repo.local=$(MAVEN_CACHE_FOLDER)'

steps:
  - task: Cache@2
    inputs:
      key: 'maven | "$(Agent.OS)" | **/pom.xml'
      restoreKeys: |
        maven | "$(Agent.OS)"
        maven
      path: $(MAVEN_CACHE_FOLDER)
    displayName: Cache Maven local repo

  - task: Maven@4
    inputs:
      mavenPOMFile: pom.xml
      mavenOptions: '-Xmx3072m $(MAVEN_OPTS)'  # pass MAVEN_OPTS through or the task overwrites it
```

For Gradle, set the `GRADLE_USER_HOME` variable to `$(Pipeline.Workspace)/.gradle`,
cache that path with `Cache@2` (key e.g. `'gradle | "$(Agent.OS)" | **/build.gradle.kts'`
— swap in `build.gradle` for Groovy DSL — with `restoreKeys` falling back to
`gradle | "$(Agent.OS)"` then `gradle`), pass `--build-cache` in the `Gradle@4`
task's `options` (or set `org.gradle.caching=true` in `gradle.properties`), and
run `./gradlew --stop` as a final step so the daemon doesn't hold files open when
the post-job cache save runs.

## Security hardening

- Use service connections (not raw credentials) for cloud deployments
- Grant service connections only the permissions needed for the job
- Enable branch policies — require PR reviews before merging to `main`
- Use Variable Groups linked to Azure Key Vault for secrets rotation
- Pin task major versions explicitly (`Maven@4`, `Gradle@4`) — never rely on an implicit latest
- Enable audit logging in the ADO organisation settings

## Common tasks

| Task | Purpose |
|---|---|
| `Maven@4` / `Gradle@4` | Java builds (JDK selection + JUnit result publishing built in; `Gradle@4` has no `1.21` JDK option — use `javaHomeOption: Path`) |
| `JavaToolInstaller@1` | Install a specific JDK and set `JAVA_HOME` |
| `NodeTool@0` / `UsePythonVersion@0` / `DotNetCoreCLI@2` | Other stacks (Node.js, Python, .NET) |
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
