# Scenario 01: Three independent slices

## Request

Implement the "order lookup" feature for the Prism API. The decomposition is already agreed — build it now, no OpenSpec, no clarifying questions, no ticket to pull.

Spring Boot 3, Maven single-module, base package `com.prism`.

| Slice | Work | Files (complete list) |
|---|---|---|
| 1 | REST controller `GET /api/orders/{id}` | `src/main/java/com/prism/order/OrderController.java`, `src/test/java/com/prism/order/OrderControllerTest.java` |
| 2 | Service with lookup + not-found handling | `src/main/java/com/prism/order/OrderService.java`, `src/test/java/com/prism/order/OrderServiceTest.java` |
| 3 | Spring Data JPA repository + entity | `src/main/java/com/prism/order/OrderRepository.java`, `src/main/java/com/prism/order/OrderEntity.java`, `src/test/java/com/prism/order/OrderRepositoryTest.java` |

Facts already verified (treat as complete and accurate):
- No two slices share a file. No dependency changes are needed — `spring-boot-starter-data-jpa` and `spring-boot-starter-web` are already in `pom.xml`, which no slice edits.
- No database migration or schema file is involved (H2 `ddl-auto=create-drop`).
- No slice depends on another slice's output to start.

Available sub-agent types in this environment: `spring-boot-backend-engineer` (Spring Boot controllers/services/JPA + JUnit tests), `tdd-engineer`, `security-auditor`, `security-implementor`, `pr-reviewer`, `issue-implementer`, `general-purpose`.
