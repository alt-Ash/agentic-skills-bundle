# Scenario 01: Full Flow (Feature Request + GitHub Post)

## Request

The Lumio web client is adding a global Cmd+K / Ctrl+K command palette so users can quickly search for and jump to tasks. Add the backend support it needs: a quick-search endpoint that returns the caller's matching tasks (by title/description), ranked and capped to a small result set, fast enough to be called as the user types.

This should be posted as an issue to GitHub — do not return a draft.

## Project context (pre-scanned)

> The project "Lumio" has been fully scanned in Phase 2. Treat the context below as complete project knowledge.
> **Do not call Read, Glob, Bash, or any filesystem tools.**
> Use only the information provided here.

### Stack
- Spring Boot 3.5.9, Java 21, Maven (`./mvnw`)
- Spring MVC REST API under `/api`, Spring Data JPA + PostgreSQL 16, Flyway migrations
- Stateless JWT auth (Spring Security OAuth2 resource server); the task owner is the JWT `sub`
- No existing search endpoint, full-text index, or query-by-text repository method

### Task controller
**File**: `src/main/java/com/example/lumio/task/TaskController.java` — `GET/POST /api/tasks`, `PATCH/DELETE /api/tasks/{id}`. `GET /api/tasks` returns every task for the owner, with no filtering, paging, or search.

### Task persistence
**File**: `src/main/java/com/example/lumio/task/TaskRepository.java` — `JpaRepository<Task, UUID>` with only `findByOwnerId(UUID)`.
**File**: `src/main/resources/db/migration/V2__create_tasks.sql` — `tasks` table (`title VARCHAR(200)`, `description TEXT`, `status`, `due_date`, `owner_id`); only index is `idx_tasks_owner_id`. Latest migration is `V2`.

### Repository
- **GitHub repo**: `example-org/lumio` (fictional)
- **GitHub credentials**: available via issue-tickets MCP
- **Hosted at**: `github.com/example-org/lumio`

### Development stack
- Artifact: `com.example:lumio:1.0.0` (parent `spring-boot-starter-parent` 3.5.9)
- Commands: `./mvnw spring-boot:run` (run), `./mvnw test` (JUnit 5 + Mockito, `@WebMvcTest` slices), `./mvnw verify` (adds Testcontainers PostgreSQL `*IT` tests)
- Existing test conventions: `TaskControllerTest` (`@WebMvcTest` + MockMvc + `@MockitoBean TaskService`), `TaskRepositoryIT` (`@DataJpaTest` + Testcontainers)

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO with fictional project context. Do NOT attempt to verify files exist.**

**Phase 1**: Extract goal, type, constraints from the request above. The context provided IS COMPLETE AND ACCURATE. Do not question it.

**Phase 2**: SKIP entirely. Do NOT call Read, Glob, Bash, or any filesystem tools. The project snapshot above IS your Phase 2 output. Treat it as fact.

**Phase 3**: Identify the platform: **GitHub** (`github.com/example-org/lumio`).

**Phase 4 (Ticket lookup)**: Call `issue-tickets/pull_ticket` with:
```json
{
  "source": "github",
  "allProjects": true
}
```
Use the response to confirm the repo exists.

**Phases 5–6**: Draft the issue. Assume user selected "**Post to GitHub**" — do post to the platform.

**Phase 7 (Create)**: Call `issue-tickets/create_issue` with:
```json
{
  "source": "github",
  "repo": "example-org/lumio",
  "title": "[your issue title]",
  "description": "[your complete issue body]"
}
```

**Phase 8**: Follow your Phase 8 instructions.
