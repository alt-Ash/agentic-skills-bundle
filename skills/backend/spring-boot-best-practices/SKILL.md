---
name: spring-boot-best-practices
description: >
  Spring Boot best practices for backend projects. Covers dependency injection, transaction
  boundaries, JPA/Hibernate access patterns, exception handling, validation, testing, and
  configuration. Apply when writing, reviewing, or refactoring Spring Boot controllers,
  services, repositories, or entities.
version: "1.0.0"
category: backend
---

# Spring Boot Best Practices

## Token Discipline

Load [references/full-guide.md](references/full-guide.md) only when detailed rule explanations, before/after code examples, or the full CONTEXT/HANDOFF block format are needed. The compact forms below are sufficient for most sessions.

## First Actions

1. Read enough project code to identify Spring Boot version, build tool (Maven/Gradle), JDK version, persistence layer, and nearby package/naming conventions.
2. Treat project conventions as source of truth; this skill is fallback guidance.
3. Emit compact context: `Context: spring-boot-best-practices; Spring Boot app; build=<maven|gradle>; jdk=<version>; versions=Spring Boot <v>, persistence=<JPA|JDBC|none>; files=<read>; gaps=<items>`.

## Core Rules

- Constructor injection only — never `@Autowired` on a field.
- Thin controllers, fat services: request mapping and response shaping in the controller, business logic in the service.
- `@Transactional` boundaries live at the service layer, never the controller.
- Map to/from DTOs at the API boundary; never return or accept a JPA `@Entity` directly in a controller method.
- Validate input with Bean Validation (`@Valid`, `@NotNull`, `@Size`, etc.) at the boundary, not with manual null checks in the method body.
- Handle exceptions via a centralized `@ControllerAdvice`, not scattered try/catch in individual controllers.
- Use `Optional<T>` only for genuinely-absent-is-valid lookups, not as a general nullable wrapper.
- Prefer one immutable `@ConfigurationProperties` class per configuration concern over scattered `@Value` injections.

## Performance Rules

- Avoid N+1 queries: use `@EntityGraph`, a JOIN FETCH query, or a projection DTO before iterating a collection association.
- Mark read-only service methods `@Transactional(readOnly = true)`.
- Default associations to `FetchType.LAZY`; switch to eager only with a demonstrated need.
- Use `@Cacheable`/`@CacheEvict` only where a real, repeated read cost has been identified.
- Size the connection pool deliberately relative to expected concurrent load — don't leave an unexamined default.
- Never block an event-loop thread in WebFlux; offload unavoidable blocking calls to a bounded elastic scheduler.

## Reference Router

| Need | Load full guide section |
|---|---|
| Constructor injection, bean scope/lifecycle | Dependency Injection |
| Transaction boundaries, propagation | Transactions |
| N+1, fetch strategy, projections | JPA/Hibernate |
| Centralized error responses | Exception Handling |
| Bean Validation patterns | Validation |
| Which test slice to use | Testing |
| `@ConfigurationProperties` vs `@Value`, profiles | Configuration |
| Full CONTEXT/HANDOFF block format | Phase 0 / Handoff sections below |

## Done When

- Changes follow existing project patterns.
- No field injection, fat controllers, or entity-leaking DTOs were introduced.
- Available checks pass or blockers are reported.

Compact handoff:

```text
Handoff: spring-boot-best-practices; status=<completed|partial|blocked>; changed=<files>; checks=<commands>; blockers=<none|items>; next=<patterns applied>
```

---

## Full block format (use when a downstream parser, user request, or agent handoff requires it)

### Phase 0 — Gather context

Before applying any pattern from this skill, read enough of the project to answer:

1. What Spring Boot version is installed? (`pom.xml`/`build.gradle`)
2. Maven or Gradle? What JDK version does the build target?
3. What persistence layer is in use? (JPA/Hibernate, plain JDBC, none)
4. What test framework/conventions are in use? (`spring-boot-starter-test`, Testcontainers)
5. Is there an existing controller/service/repository to use as a style reference?

Emit the CONTEXT BLOCK before applying any patterns:

```
***CONTEXT BLOCK***
Skill/Agent : spring-boot-best-practices
Timestamp   : <ISO-8601 date>

### Project
- Type            : <Spring Boot API | Quarkus | Micronaut | monorepo | unknown>
- Build tool       : <Maven | Gradle>
- JDK version      : <version>
- Monorepo        : <yes | no>

### Runtime & tooling versions
- Spring Boot     : <version>
- Persistence     : <JPA/Hibernate | JDBC | none>
- Test runner     : <JUnit 5 | JUnit 5 + Testcontainers | none>
- Linter          : <checkstyle | spotless | none>

### Infrastructure
- CI/CD           : <provider or "unknown">

### Files read
- pom.xml (or build.gradle)
- <any other files read>

### Gaps / unknowns
- <description or "none">
***END CONTEXT BLOCK***
```

### Handoff

After applying this skill, emit:

```
***HANDOFF BLOCK***
Skill/Agent : spring-boot-best-practices
Timestamp   : <ISO-8601 date>
Status      : completed | partial | blocked

### What was done
- <Reviewed / refactored / authored: file list>

### Artifacts produced
| File | Change |
|------|--------|
| <path> | modified — <description> |

### Checks
| Check     | Result |
|-----------|--------|
| tests     | ✅ passed / ❌ failed / ⚪ n/a |
| lint      | ✅ passed / ❌ failed / ⚪ n/a |
| build     | ✅ passed / ❌ failed / ⚪ n/a |

### Blocked items
- <item or "—">

### For the next agent or step
<summary of what was changed and any patterns that were established or enforced>
***END HANDOFF BLOCK***
```
