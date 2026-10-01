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

> Reference-only detail. Primary `SKILL.md` routing and compact output rules take precedence.

## What this skill does

Provides opinionated, evidence-backed rules for writing maintainable Spring Boot code. Covers
dependency injection, transactions, JPA/Hibernate access patterns, exception handling,
validation, testing, and configuration. Does NOT cover actual implementation work end-to-end
(use the `spring-boot-backend-engineer` agent for that — this skill is the rule set it loads,
not a replacement for it) or security-specific hardening (use `security-auditor`/
`secure-feature-gate` for that).

## When to use it

- Writing, reviewing, or refactoring Spring Boot controllers, services, repositories, or entities
- Reviewing transaction boundaries or persistence access patterns
- Reviewing configuration/profile setup

## Do not use when

- The project has its own coding standards that conflict — always check project conventions
  first and treat this skill as a baseline, not an override
- The task is a full feature build end-to-end (use the `spring-boot-backend-engineer` agent,
  which loads this skill itself)
- The task is security-specific (use `security-auditor` or `secure-feature-gate` instead)

---

## Phase 0 — Gather context

Before applying any pattern from this skill, read enough of the project to answer:

1. What Spring Boot version is installed? (`pom.xml` → `spring-boot-starter-parent` version, or `build.gradle`)
2. Maven or Gradle? What JDK version does the build target?
3. What persistence layer is in use? (JPA/Hibernate, plain JDBC, none)
4. What test framework/conventions are in use?
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

---

## Dependency Injection

- Use constructor injection exclusively. A class's dependencies are `private final` fields, set by a single constructor. Never annotate a field with `@Autowired`.
- If a class has more than ~5-6 constructor parameters, that is a signal it has too many responsibilities — consider splitting it, not adding a builder to hide the problem.
- Use `@Qualifier` or a distinct interface per implementation when multiple beans of the same type exist, rather than `@Primary` as a default escape hatch.
- Prefer package-private or `final` classes for `@Service`/`@Repository` implementations where Spring's CGLIB proxying isn't required (i.e. not using class-based AOP on them) — it communicates the class isn't meant to be extended.
- Avoid `@Lazy` injection as a workaround for a circular dependency — a circular bean dependency is a design smell; break the cycle by extracting the shared concern into a third collaborator.

```java
// bad — field injection, untestable without reflection or a Spring context
@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;
}

// good — constructor injection, trivially testable with `new OrderService(mockRepo)`
@Service
public class OrderService {
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }
}
```

## Transactions

- Place `@Transactional` at the service layer. A controller method should never carry `@Transactional` — the HTTP request/response cycle is not the right transaction boundary.
- Mark read-only methods `@Transactional(readOnly = true)`. This is not just documentation — Hibernate skips the dirty-checking flush for read-only sessions.
- Understand `@Transactional`'s default propagation (`REQUIRED`) before reaching for `REQUIRES_NEW`/`NESTED` — most services never need anything but the default.
- Do not catch and swallow an exception inside a `@Transactional` method if the transaction should roll back — by default Spring only rolls back on unchecked exceptions; a caught-and-logged unchecked exception will NOT trigger rollback unless re-thrown or `rollbackFor` is declared.
- Avoid calling a `@Transactional` method from another method in the *same* class — the Spring AOP proxy is bypassed for self-invocation, so the transactional advice silently never applies.

## JPA/Hibernate

- Default every `@OneToMany`/`@ManyToMany` association to `FetchType.LAZY`. Eager fetching by default is the single most common cause of accidental N+1 queries and unbounded object graphs.
- When a collection association *will* be iterated in the same request, fetch it up front with `@EntityGraph` or an explicit `JOIN FETCH` query — don't rely on lazy-loading-per-item inside a loop.
- Prefer a projection DTO (a Spring Data interface or constructor-expression JPQL projection) over loading a full entity graph when only a few fields are actually needed.
- Use `@Version` for optimistic locking on entities that can be concurrently updated, rather than manual last-write-wins logic.

```java
// bad — N+1: one query for the orders, then one additional query per order for its items
List<Order> orders = orderRepository.findAll();
orders.forEach(order -> order.getItems().size()); // triggers a lazy load per order

// good — a single query with the association fetched up front
@EntityGraph(attributePaths = "items")
List<Order> findAllWithItems();
```

## Exception Handling

- Centralize error-to-HTTP-response mapping in one (or a few, per-domain) `@ControllerAdvice` class using `@ExceptionHandler` methods. A controller method should let a meaningful exception propagate, not catch it locally to build an error response inline.
- Throw specific exception types (`EntityNotFoundException`, a project-specific `DomainException` subtype) from the service layer — not a generic `RuntimeException` with a string message that has to be pattern-matched later.
- Return a consistent error response shape (e.g. `{ "status": ..., "message": ..., "errors": [...] }`) across every `@ExceptionHandler` — an inconsistent error contract is a common source of brittle client code.
- Never let a raw stack trace or internal exception message reach the client response body in a production profile — log it server-side, return a safe summary.

```java
@ControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // collect field errors into ErrorResponse.errors
    }
}
```

## Validation

- Annotate request DTO fields with Bean Validation constraints (`@NotNull`, `@NotBlank`, `@Size`, `@Pattern`, `@Email`, etc.) and add `@Valid` to the controller method parameter — let the framework produce the `MethodArgumentNotValidException` rather than hand-rolling checks.
- For validation rules that span multiple fields (e.g. "endDate must be after startDate"), use a class-level constraint or a custom `ConstraintValidator`, not ad-hoc logic buried in the service.
- Validate at the boundary once — don't re-validate the same DTO fields again deeper in the call stack "just in case."

## Testing

Choose the narrowest test slice that actually exercises what you're testing:

| Slice | Use for | Cost |
|---|---|---|
| Plain unit test (no Spring context, Mockito-mocked dependencies) | Service-layer business logic | Fastest — this should be the majority of tests |
| `@WebMvcTest` | Controller-layer behavior: request mapping, validation, response shape/status | Fast — loads only the web layer |
| `@DataJpaTest` | Repository queries, `@EntityGraph`/fetch behavior, against an in-memory or Testcontainers database | Medium |
| `@SpringBootTest` | True end-to-end integration across the whole context | Slowest — reach for this only when nothing narrower will do |

- Prefer Testcontainers over an in-memory database (H2) for `@DataJpaTest` when the project targets a specific production database (Postgres, MySQL) — H2's SQL dialect differences can hide real bugs.
- Test observable behavior (return values, thrown exceptions, HTTP status/body, persisted state) — not private methods or internal field values.

## Configuration

- Group related configuration into one `@ConfigurationProperties(prefix = "...")` class (ideally an immutable Java `record`) rather than several unrelated `@Value("${...}")` injections scattered across classes.
- Use Spring profiles (`application-{profile}.yml`) for environment-specific values, not conditional logic in Java code branching on an environment name read from a property.
- Never commit real secrets in `application.properties`/`application.yml` — reference an environment variable or a secrets manager, and keep only placeholder/example values in version control.

---

## Done when

- All patterns applied are consistent with the existing project conventions (checked in Phase 0)
- No field injection, fat controllers, entity-leaking DTOs, or unbounded eager fetches were introduced
- Verification checks pass (see Handoff)

---

## Handoff

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
