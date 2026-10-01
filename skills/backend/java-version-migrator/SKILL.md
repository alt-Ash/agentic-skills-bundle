---
name: java-version-migrator
description: Guides safe Java/JDK version upgrades by auditing breaking changes, running automated migration tooling (OpenRewrite recipes), and updating build configs. Use when the user wants to upgrade Java/JDK versions, migrate from one JDK version to another, or asks about Java breaking changes between versions.
---

# Java Version Migrator

## Token Discipline

Load this primary router first. Load detailed docs only for the required hop or detected concern.

| Condition | Load |
|---|---|
| Need full legacy workflow, Docker/native-library tables, dependency audit decision tree, or license detail | [references/full-guide.md](references/full-guide.md) |
| JDK 17 -> 21 | [migrations/jdk17-to-jdk21.md](migrations/jdk17-to-jdk21.md) |
| JDK 21 -> 25 | [migrations/jdk21-to-jdk25.md](migrations/jdk21-to-jdk25.md) |
| Spring Boot major-version boundary crossed (2.x -> 3.x) | [framework-migrations/spring-boot/SKILL.md](framework-migrations/spring-boot/SKILL.md) |

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## First Actions

1. Identify current and target JDK versions from user prompt, `java -version`, `pom.xml` (`<java.version>`/`<maven.compiler.release>`) or `build.gradle` (`sourceCompatibility`/toolchain block), and `.sdkmanrc` only as needed.
2. Determine migration hops. Never skip an intermediate guide when one exists.
3. Detect the Spring Boot major version in use (`spring-boot-starter-parent` version in `pom.xml`, or the Spring Boot Gradle plugin version) — a 2.x -> 3.x crossing imposes its own breaking changes independent of the JDK hop.
4. Emit compact context: `Context: java-version-migrator; <project type>; build=<maven|gradle>; versions=JDK <from> -> <to>; files=<read>; gaps=<items>`.

## Context Block

When full blocks are required, emit this after First Actions and before any file change:

```
***CONTEXT BLOCK***
Skill/Agent : java-version-migrator
Timestamp   : <ISO-8601 date>

### Project
- Type            : <Spring Boot API | Quarkus | Micronaut | plain Java library | monorepo | unknown>
- Build tool      : <Maven (./mvnw) | Gradle (./gradlew) | unknown>
- Multi-module    : <yes — modules: <list> | no>

### Runtime & tooling versions
- JDK version     : <current version + distribution, e.g. "17 temurin">
- Spring Boot     : <version | "—">
- Test runner     : <JUnit 5 | JUnit 4 | TestNG | none>
- OpenRewrite     : <rewrite-maven-plugin / Gradle plugin version | not configured>

### Migration context
- Current version : <e.g. "JDK 17">
- Target version  : <e.g. "JDK 21">
- Migration hops  : <e.g. "17 → 21 (single hop)" | "17 → 21 → 25">
- Spring Boot hop : <e.g. "2.7 → 3.x (framework migrator required)" | "none">
- Native/JNI      : <yes — libraries: <list> | no>

### Infrastructure
- CI/CD           : <GitHub Actions | Azure Pipelines | GitLab CI | none | unknown>
- Docker          : <yes — base image: <image:tag> | no>
- JDK pin files   : <.sdkmanrc | .java-version | toolchains.xml | none>

### Files read
- pom.xml (or build.gradle / build.gradle.kts)
- <any other files read>

### Gaps / unknowns
- <description or "none">
***END CONTEXT BLOCK***
```

## Workflow

| Step | Action |
|---|---|
| 1 | Load only the migration guide(s) for required JDK hops |
| 2 | Audit deprecated/removed APIs listed in loaded guide(s) |
| 3 | Run OpenRewrite recipes from loaded guide(s), if any |
| 4 | If crossing a Spring Boot major-version boundary, run the Spring Boot framework migrator before build/tooling finalization |
| 5 | Audit direct Maven/Gradle dependencies for target-JDK compatibility |
| 6 | Update `pom.xml`/`build.gradle` Java version and toolchain declarations |
| 7 | Update CI, Docker, and tool configs where present |
| 8 | Run build, tests, and Docker build where available |
| 9 | Run license scan only if the project declares a license and dependency changes are final |

## Critical Rules

- Do not proceed to the next hop until tests/build for the current hop are green or blocked with reason.
- Update final runtime declarations (`pom.xml`/`build.gradle` Java version, `.sdkmanrc`, CI, Docker) consistently.
- JNI/native-library integrations can fail after a base-image or JDK change; load the full guide if the project links against native libraries via JNI/Project Panama.
- Do not blindly bump major dependency versions; read the dependency's release notes/migration guide first.
- If any required dependency cannot support the target JDK, stop and report the blocker.

## Minimal Commands

```bash
java -version
mvn help:evaluate -Dexpression=java.version -q -DforceStdout
<skill-dir>/scripts/check-java-compat.sh <TARGET_VERSION>
```

Use the compatibility script path resolved by the agent environment.

## Done When

- Build-file and tooling declarations point to the target JDK version.
- Loaded migration-guide breaking changes are handled.
- Spring Boot major-version requirements are satisfied, if applicable.
- Available tests, build, Docker build, and license scan pass or blockers are reported.

Compact handoff:

```text
Handoff: java-version-migrator; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=JDK <from> -> <to>, framework=Spring Boot <version|none>, dependency blockers=<items>
```

## Handoff Block

When full blocks are required, emit this instead of the compact handoff:

```
***HANDOFF BLOCK***
Skill/Agent : java-version-migrator
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- Migrated JDK <from> -> <to> via hops: <list>
- Spring Boot: <from> -> <to> | unchanged
- OpenRewrite recipes run: <recipe names | none>
- Removed/deprecated API usages fixed: <count + summary>

### Artifacts produced
| File | Change |
|------|--------|
| pom.xml / build.gradle | modified — Java release/toolchain -> <to> |
| <CI file, Dockerfile, .sdkmanrc> | modified — <description> |
| <source file> | modified — <API replacement> |

### Checks
| Check        | Result |
|--------------|--------|
| compile      | ✅ passed / ❌ failed / ⚪ n/a |
| tests        | ✅ passed / ❌ failed / ⚪ n/a |
| build        | ✅ passed / ❌ failed / ⚪ n/a |
| docker build | ✅ passed / ❌ failed / ⚪ n/a |
| license scan | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <dependency or API that cannot support the target JDK, with reason | "—">

### For the next agent or step
<Final JDK/Spring Boot versions, remaining dependency blockers, and any runtime flags (e.g. --add-opens) that were added and should be revisited>
***END HANDOFF BLOCK***
```
