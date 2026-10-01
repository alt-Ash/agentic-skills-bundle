---
name: java-version-migrator
description:
  Guides safe Java/JDK version upgrades by auditing breaking changes, running
  automated migration tooling (OpenRewrite recipes), and updating build configs.
  Use when the user wants to upgrade Java/JDK versions, migrate from one JDK
  version to another, or asks about Java breaking changes between versions.
version: "1.0.0"
category: backend
---

# Java Version Migrator

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## Workflow

Copy this checklist and track progress:

```
Migration Progress:
- [ ] 1. Identify current and target JDK versions
- [ ] 2. Read the relevant migration guide
- [ ] 3. Audit the codebase for affected patterns
- [ ] 4. Run OpenRewrite recipes
- [ ] 5. Audit Maven/Gradle dependencies for JDK version compatibility
- [ ] 6. Update Java version declarations in pom.xml/build.gradle
- [ ] 7. Update CI/CD and tooling configs (.sdkmanrc, Dockerfile, etc.)
- [ ] 8. Run tests and verify
- [ ] 9. Run build and verify
- [ ] 10. Run Docker build and verify
- [ ] 11. Check for license violations (if project has a license)
```

## Available migration guides

| From → To | Guide                                                      |
| --------- | ------------------------------------------------------------------------ |
| JDK 17 → 21 | [migrations/jdk17-to-jdk21.md](migrations/jdk17-to-jdk21.md) |
| JDK 21 → 25 | [migrations/jdk21-to-jdk25.md](migrations/jdk21-to-jdk25.md) |

## Multi-hop migrations

If the gap between current and target versions is not covered by a single guide,
chain the available guides in order. Complete all 11 steps for each hop before
starting the next.

**Example: JDK 17 → 25 with guides for 17→21 and 21→25**

```
Hop 1: JDK 17 → 21  (follow all 11 steps, run tests + build + docker, confirm green)
Hop 2: JDK 21 → 25  (follow all 11 steps, run tests + build + docker, confirm green)
```

Rules:

- Never skip an intermediate version that has a guide.
- Do not proceed to the next hop until tests pass for the current one.
- Update `pom.xml`/`build.gradle` Java version, `.sdkmanrc`, and tooling configs
  only on the final hop (or keep them in sync at each hop — user's choice, ask
  if unclear).

If no guide exists for an intermediate hop, note the gap to the user and proceed
with manual review of the JDK release notes for that version range.

## Framework migrations

Spring Boot has its own major-version boundaries with breaking changes independent
of the JDK hop. If the project uses Spring Boot and is crossing a major-version
boundary (currently: 2.x → 3.x), follow its dedicated migration skill **after**
completing JDK steps 1–4 (breaking-change fixes) but **before** running tests.

| Framework | Migration skill |
|---|---|
| Spring Boot | [framework-migrations/spring-boot/SKILL.md](framework-migrations/spring-boot/SKILL.md) |

**How to detect if a framework migration is needed:**

1. Check `pom.xml`/`build.gradle` for the `spring-boot-starter-parent` version or Spring Boot Gradle plugin version.
2. Check Spring Boot's migration guide for the JDK version requirements of each major release (Spring Boot 3 requires JDK 17+).
3. If the target JDK version enables or requires a Spring Boot major-version bump, include the framework migration as part of this workflow.

When a framework migration is needed, expand the checklist:

```
- [ ] 5a. Run Spring Boot framework migration (read framework skill, follow its steps)
```

Complete the framework migration and confirm tests pass before continuing with step 6.

## Step 1 — Identify versions

If not told explicitly, check:

```bash
java -version
mvn help:evaluate -Dexpression=java.version -q -DforceStdout
cat .sdkmanrc 2>/dev/null
```

After identifying versions, emit the CONTEXT BLOCK:

```
***CONTEXT BLOCK***
Skill/Agent : java-version-migrator
Timestamp   : <ISO-8601 date>

### Project
- Type            : <Spring Boot API | Quarkus | Micronaut | monorepo | unknown>
- Build tool       : <Maven | Gradle | unknown>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- JDK version     : <current version>
- Spring Boot version : <version or "n/a">
- Build tool      : <Maven | Gradle>
- Test runner     : <JUnit 5 | TestNG | none>

### Migration context
- Current version : JDK <vX>
- Target version  : JDK <vY>
- Migration hops  : <e.g. "17 → 21 (single)" or "17 → 21 → 25 (two hops)">

### Infrastructure
- CI/CD           : <provider or "unknown">
- Docker          : <yes | no>

### Files read
- pom.xml / build.gradle
- .sdkmanrc

### Gaps / unknowns
- Spring Boot major-version migration needed: <yes | no | unknown>
- JNI/native-library integrations present: <yes | no | unknown — run check in Step 7>
***END CONTEXT BLOCK***
```

## Step 2 — Read the migration guide

Load the appropriate file from `migrations/` for the version pair. It contains
breaking changes and OpenRewrite recipe commands.

## Step 3 — Audit codebase

Grep for the deprecated patterns listed in the migration guide before running
recipes.

## Step 4 — Run OpenRewrite recipes

Execute the `mvn org.openrewrite.maven:rewrite-maven-plugin:run` commands from
the guide. OpenRewrite recipes are automated and AST-based — review the diff
they produce before committing, the same discipline you'd apply to any
automated refactor.

## Step 5 — Audit Maven/Gradle dependencies for JDK compatibility

For every direct dependency, verify it explicitly supports the target JDK
version (bytecode-manipulation libraries and annotation processors — Lombok,
Mockito, ByteBuddy — are the most common source of incompatibility, since they
depend on JDK internals more directly than most libraries).

### 1. Find potentially incompatible dependencies

Run the compatibility script from the project root:

```bash
<skill-dir>/scripts/check-java-compat.sh <TARGET_VERSION>
# Example:
<skill-dir>/scripts/check-java-compat.sh 21
```

Replace `<skill-dir>` with the path where this skill is installed (e.g.
`.claude/skills/java-version-migrator` or wherever your agent resolves it).

Complement with Maven/Gradle tooling:

```bash
mvn versions:display-dependency-updates   # current vs latest
mvn org.owasp:dependency-check-maven:check  # known CVEs
```

### 2. For each flagged dependency — follow this decision tree

```
Is a newer version of the dependency available?
├── YES → Does the latest version support the target JDK version?
│         ├── YES → Bump to that version.
│         │         Check the dependency's release notes or GitHub for a migration guide.
│         └── NO  → Flag as BLOCKED. Note it in the completion report.
└── NO  → Dependency is unmaintained.
          Find a maintained replacement (search Maven Central, check GitHub issues).
          Flag as BLOCKED if no replacement exists.
```

### 3. Bump compatible dependencies

```xml
<!-- pom.xml -->
<dependency>
  <groupId>...</groupId>
  <artifactId>...</artifactId>
  <version>...</version> <!-- bumped -->
</dependency>
```

Or via the Maven versions plugin:

```bash
mvn versions:use-latest-releases -Dincludes=<groupId>:<artifactId>
```

Always check the dependency's release notes for breaking changes before
bumping a major version. Do not bump major versions blindly.

### 4. Completion report

After auditing all dependencies, produce a summary table:

```
| Dependency          | Old version | New version | Status              | Notes                        |
|---------------------|-------------|-------------|---------------------|------------------------------|
| some-library        | 1.2.3       | 2.0.0       | ✅ Bumped           | Migration: <url>             |
| other-library       | 3.1.0       | 3.1.0       | ✅ Compatible       |                              |
| legacy-lib          | 0.9.0       | —           | 🚨 BLOCKED          | Unmaintained, no replacement |
| another-lib         | 5.0.0       | 6.0.0       | ⚠️ Needs review     | Major bump, check changelog  |
```

Do not proceed to step 6 if any dependency is marked 🚨 BLOCKED — raise it with
the user and agree on a path forward first.

## Step 6 — Update Java version declarations

Maven (`pom.xml`):

```xml
<properties>
  <java.version>21</java.version>
  <maven.compiler.release>21</maven.compiler.release>
</properties>
```

Gradle (`build.gradle`):

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

## Step 7 — Update tooling configs

- `.sdkmanrc` — set target version
- CI config (`.github/workflows/*.yml`, etc.) — update `java-version`

### Dockerfile updates

**1. Bump the base image**

```dockerfile
# Before
FROM eclipse-temurin:17-jre

# After
FROM eclipse-temurin:21-jre
```

Prefer the same distribution/variant family unless you have a reason to
change it (Temurin, Amazon Corretto, etc. each have their own image tagging
conventions — don't mix distributions mid-migration without reason).

**2. Verify native-library dependencies are still satisfied**

Projects with JNI or Foreign Function & Memory API integrations need matching
OS-level native libraries. After bumping the base image, check that all
required native libraries are still installed in the image.

Grep for JNI/native usage in the project:

```bash
grep -rln "System\.loadLibrary\|native " --include="*.java" src/
```

**3. Rebuild and verify the image**

```bash
docker build -t app:jdk21-test .
docker run --rm app:jdk21-test java -version
docker run --rm app:jdk21-test java -jar app.jar --spring.main.web-application-type=none
```

## Step 8 — Verify tests

```bash
mvn test
```

Flag any test failures caused by the version change and relate them back to the
breaking changes in the migration guide.

## Step 9 — Verify build

```bash
mvn package
```

If the project has a Spring Boot executable jar, confirm it starts:

```bash
java -jar target/*.jar --server.port=0
```

## Step 10 — Verify Docker build

Build the image locally and confirm the container starts correctly:

```bash
# Build
docker build -t app:jdk-upgrade-test .

# Confirm JDK version inside the image matches the target
docker run --rm app:jdk-upgrade-test java -version

# Run tests inside the container's build stage (if multi-stage build)
docker build --target test -t app:jdk-upgrade-test-stage .
```

Common issues:

- **Native-library integrations fail** — the error surfaces at container
  startup or first native call. Identify the missing OS-level library from
  the error message and add it to the image's package-install step.
- **Wrong base-image distribution** — if you switched JDK distributions
  (e.g. Temurin → Corretto) alongside the version bump, re-verify any
  distribution-specific behavior the application depends on.

## Step 11 — Check for license violations

Skip this step if the project has no declared license.

Bumping dependencies during a JDK upgrade can silently introduce dependencies
with incompatible licenses. Run this check after all dependency changes are
final.

### 1. Detect the project license

Check `pom.xml`'s `<licenses>` block or the project's `LICENSE` file.

### 2. Scan dependencies for violations

```bash
mvn org.codehaus.mojo:license-maven-plugin:check
```

Or a dedicated license-scanning tool appropriate to the project's CI setup.

### 3. Handle violations

For each violating dependency:

```
Is a license-compliant version available?
├── YES → Bump to that version and re-run the scan.
└── NO  → Find a replacement dependency or request a legal exception.
          Flag as BLOCKED and raise with the team before proceeding.
```

Do not proceed past this step if any violation remains unresolved.

---

## Handoff

After completing all steps, emit:

```
***HANDOFF BLOCK***
Skill/Agent : java-version-migrator
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated Java from <vX> to <vY>
- OpenRewrite recipes run: <list or "none">
- Dependency audit completed: <N> dependencies checked, <N> bumped, <N> blocked
- Spring Boot framework migration: <completed | skipped | not needed>
- Docker base image updated: <yes | no | n/a>
- CI/CD updated: <yes | no | n/a>
- License check: <passed | blocked: <reason> | skipped>

### Artifacts produced
| File | Change |
|------|--------|
| pom.xml / build.gradle | modified — updated java.version and dependency versions |
| .sdkmanrc | modified — set to <target version> |
| Dockerfile | modified — updated base image to eclipse-temurin:<vY>-jre |
| .github/workflows/*.yml | modified — updated java-version |
| <other files> | modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <dependency name — reason — next: suggested action | "—">

### For the next agent or step
Java migrated from <vX> to <vY>. Spring Boot: <version | none>. Build tool: <Maven | Gradle>.
Docker: updated base image to eclipse-temurin:<vY>-jre.
Any blocked dependencies are listed above and require team sign-off before proceeding.
***END HANDOFF BLOCK***
```
