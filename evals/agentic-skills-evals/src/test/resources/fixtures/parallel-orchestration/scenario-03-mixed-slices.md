# Scenario 03: Java slices plus one slice no specialist covers

## Request

Implement the "order lookup" feature for the Prism API. The decomposition is already agreed — build it now, no OpenSpec, no clarifying questions, no ticket to pull.

Spring Boot 3, Maven single-module, base package `com.prism`.

| Slice | Work | Files (complete list) |
|---|---|---|
| 1 | REST controller `GET /api/orders/{id}` | `src/main/java/com/prism/order/OrderController.java`, `src/test/java/com/prism/order/OrderControllerTest.java` |
| 2 | Service with lookup + not-found handling | `src/main/java/com/prism/order/OrderService.java`, `src/test/java/com/prism/order/OrderServiceTest.java` |
| 3 | Hand-written API documentation page for the endpoint (plain Markdown, no code) | `docs/api/orders.md` |

Facts already verified (treat as complete and accurate):
- No two slices share a file. `pom.xml` is not edited by any slice.
- No migration, schema or generated artifact is involved, and no slice needs another slice's output to start.

Available sub-agent types in this environment: `spring-boot-backend-engineer` (Spring Boot controllers/services/JPA + JUnit tests), `tdd-engineer`, `security-auditor`, `security-implementor`, `pr-reviewer`, `issue-implementer`, `general-purpose`.
