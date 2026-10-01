---
name: spring-boot-version-migrator
description: Guides safe Spring Boot major-version upgrades by applying official migration guides step by step. Use when the user wants to upgrade Spring Boot, migrate between Spring Boot major versions, or asks about Spring Boot breaking changes between versions.
---

# Spring Boot Version Migrator

## Token Discipline

Load only the guide(s) for required Spring Boot hops. Do not load all version files up front.

Use compact context/handoff from `templates/COMPACT-CONTEXT.md` and `templates/COMPACT-HANDOFF.md` unless full blocks are explicitly required.

## Workflow

Copy this checklist and track progress:

```
Spring Boot Migration Progress:
- [ ] 1. Identify current and target Spring Boot versions
- [ ] 2. Read the relevant migration guide(s)
- [ ] 3. Audit the codebase for affected patterns
- [ ] 4. Apply breaking-change fixes
- [ ] 5. Upgrade spring-boot-starter-parent / plugin version
- [ ] 6. Update peer dependencies (JDK minimum, Spring Security, Spring Data, etc.)
- [ ] 7. Run tests and verify
- [ ] 8. Run build and verify
```

## Available migration guides

| From → To | Guide |
|---|---|
| v2 → v3 | [v2-to-v3.md](v2-to-v3.md) |

Emit compact context after version detection:

```text
Context: spring-boot-version-migrator; Spring Boot app; build=<maven|gradle>; versions=Spring Boot <from> -> <to>; files=<read>; gaps=<items>
```

## Multi-hop migrations

If the gap between current and target versions spans multiple guides, chain them in order. Complete all steps for each hop before starting the next. (At the time of writing there is only one documented hop here — v2→v3 — since that is the only Spring Boot major-version boundary that has occurred; if a future v3→v4 guide exists when this skill is next updated, chain it the same way.)

Rules:

- Never skip an intermediate version that has a guide.
- Do not proceed to the next hop until tests pass for the current one.

## Step 1 — Identify versions

```bash
mvn help:evaluate -Dexpression=project.parent.version -q -DforceStdout
# or, for the spring-boot-starter-parent version specifically:
grep -A2 "spring-boot-starter-parent" pom.xml
```

## Step 2 — Read the migration guide

Load the appropriate file for the version pair. It contains breaking changes and required code updates.

## Step 3 — Audit codebase

Before making changes, grep for the patterns listed as breaking changes in the guide. This gives a clear picture of the migration scope.

## Step 4 — Apply breaking-change fixes

Work through each breaking change in the guide and update the codebase. Pay special attention to:

- `javax.*` → `jakarta.*` namespace references (imports, XML schema namespaces, `web.xml`/`persistence.xml` if present)
- Spring Security configuration style (`WebSecurityConfigurerAdapter` subclassing → lambda-based `SecurityFilterChain` bean)
- Minimum JDK version requirement
- Actuator endpoint/property renames between major versions

## Step 5 — Upgrade spring-boot-starter-parent / plugin version

Maven:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.x.x</version>
</parent>
```

Gradle:

```groovy
plugins {
    id 'org.springframework.boot' version '3.x.x'
}
```

Prefer running the OpenRewrite recipe over a purely manual bump:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0
```

## Step 6 — Update peer dependencies

Each Spring Boot major version may change its peer dependency requirements. Check the migration guide for:

- Minimum JDK version (Spring Boot 3 requires JDK 17+)
- Spring Framework version alignment
- Spring Security major version alignment
- Spring Data major version alignment

## Step 7 — Verify tests

```bash
mvn test
```

## Step 8 — Verify build

```bash
mvn package
```

Compact handoff:

```text
Handoff: spring-boot-version-migrator; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=Spring Boot <from> -> <to>, jdk-min=<version>
```
