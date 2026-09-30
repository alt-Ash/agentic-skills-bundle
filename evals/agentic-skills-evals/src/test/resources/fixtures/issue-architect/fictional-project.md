# Lumio — Fictional Project Reference

> This document describes a realistic fictional Spring Boot + Java project used for eval testing.
> Use this as a substitute for actual project context. Treat it as Phase 2 output (full project scan).

## Project Overview

**Lumio** is a task-management REST API built with Spring Boot 3.5, Java 21, and Maven. It backs a separate web client (not in this repo). PostgreSQL for persistence, Flyway for schema migrations, stateless JWT auth via Spring Security's OAuth2 resource server support.

## Stack

- **Runtime**: Java 21 (Temurin), Spring Boot 3.5.9
- **Build**: Maven 3.9 via the Maven Wrapper (`./mvnw`)
- **Web**: Spring MVC (`spring-boot-starter-web`), JSON via Jackson
- **Persistence**: Spring Data JPA (Hibernate 6), PostgreSQL 16, Flyway migrations
- **Validation**: Jakarta Bean Validation (`spring-boot-starter-validation`)
- **Security**: Spring Security, stateless JWT (HS256) issued by `AuthController`
- **Testing**: JUnit 5, Mockito, AssertJ, `@WebMvcTest` slices, Testcontainers (PostgreSQL) for repository/integration tests
- **API style**: REST/JSON under `/api`, no GraphQL, no WebFlux

## Directory Structure

```
lumio/
├── src/
│   ├── main/
│   │   ├── java/com/example/lumio/
│   │   │   ├── LumioApplication.java          (@SpringBootApplication entry point)
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java        (SecurityFilterChain, JWT decoder, permits /api/auth/**)
│   │   │   │   └── JwtProperties.java         (@ConfigurationProperties("lumio.jwt"))
│   │   │   ├── auth/
│   │   │   │   ├── AuthController.java        (POST /api/auth/login, /api/auth/logout)
│   │   │   │   ├── AuthService.java           (credential check, token issuance)
│   │   │   │   ├── LoginRequest.java          (record: email, password — no @NotBlank on password yet)
│   │   │   │   └── TokenResponse.java         (record: token, expiresAt)
│   │   │   ├── task/
│   │   │   │   ├── TaskController.java        (GET/POST /api/tasks, PATCH/DELETE /api/tasks/{id})
│   │   │   │   ├── TaskService.java           (business logic, ownership checks)
│   │   │   │   ├── TaskRepository.java        (JpaRepository<Task, UUID>, findByOwnerId only)
│   │   │   │   ├── Task.java                  (@Entity: id, title, description, status, dueDate, owner, timestamps)
│   │   │   │   ├── TaskStatus.java            (enum: TODO, IN_PROGRESS, DONE)
│   │   │   │   ├── CreateTaskRequest.java     (record, @NotBlank title)
│   │   │   │   ├── UpdateTaskRequest.java     (record, all fields optional)
│   │   │   │   └── TaskResponse.java          (record DTO + static from(Task))
│   │   │   ├── user/
│   │   │   │   ├── User.java                  (@Entity: id, email, passwordHash)
│   │   │   │   └── UserRepository.java        (findByEmail)
│   │   │   └── common/
│   │   │       ├── GlobalExceptionHandler.java (@RestControllerAdvice → RFC 7807 ProblemDetail)
│   │   │       └── NotFoundException.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       └── db/migration/
│   │           ├── V1__create_users.sql
│   │           └── V2__create_tasks.sql
│   └── test/
│       └── java/com/example/lumio/
│           ├── auth/AuthControllerTest.java       (@WebMvcTest + MockMvc, AuthService mocked)
│           ├── task/TaskControllerTest.java       (@WebMvcTest + MockMvc, TaskService mocked)
│           ├── task/TaskServiceTest.java          (plain JUnit 5 + Mockito)
│           ├── task/TaskRepositoryIT.java         (@DataJpaTest + Testcontainers PostgreSQL)
│           └── TestcontainersConfiguration.java   (@TestConfiguration, @ServiceConnection PostgreSQLContainer)
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw
├── mvnw.cmd
├── pom.xml
├── compose.yaml                               (local PostgreSQL 16)
└── README.md
```

## Key Files — Content Snapshots

### `pom.xml` (excerpt)

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.9</version>
    <relativePath/>
  </parent>

  <groupId>com.example</groupId>
  <artifactId>lumio</artifactId>
  <version>1.0.0</version>
  <name>lumio</name>
  <description>Task management REST API</description>

  <properties>
    <java.version>21</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

All dependency versions (Spring, Hibernate, Flyway, PostgreSQL driver, JUnit 5, Mockito, Testcontainers) are managed by the Spring Boot parent BOM — none are pinned in this POM.

### `src/main/resources/application.yml`

```yaml
spring:
  application:
    name: lumio
  datasource:
    url: jdbc:postgresql://localhost:5432/lumio
    username: ${LUMIO_DB_USER:lumio}
    password: ${LUMIO_DB_PASSWORD:lumio}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080
  error:
    include-stacktrace: never

lumio:
  jwt:
    secret: ${LUMIO_JWT_SECRET}
    ttl: 8h
```

### `src/main/java/com/example/lumio/task/TaskController.java`

```java
package com.example.lumio.task;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    // NOTE: returns every task for the owner — no filtering, sorting, paging, or search yet.
    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return taskService.listForOwner(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(UUID.fromString(jwt.getSubject()), request);
    }

    @PatchMapping("/{id}")
    public TaskResponse update(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable UUID id,
                               @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(UUID.fromString(jwt.getSubject()), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        taskService.delete(UUID.fromString(jwt.getSubject()), id);
    }
}
```

### `src/main/java/com/example/lumio/task/TaskRepository.java`

```java
package com.example.lumio.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByOwnerId(UUID ownerId);
}
```

### `src/main/java/com/example/lumio/auth/LoginRequest.java`

```java
package com.example.lumio.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// BUG: password has no @NotBlank. An empty password reaches AuthService,
// which fails the BCrypt comparison and returns 401 instead of a 400 with a
// field-level validation error. Expected: 400 ProblemDetail naming "password".
public record LoginRequest(
        @NotBlank @Email String email,
        String password) {
}
```

### `src/main/java/com/example/lumio/auth/AuthController.java`

```java
package com.example.lumio.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        // Stateless JWT: the client discards the token. No server-side denylist yet.
        return ResponseEntity.noContent().build();
    }
}
```

### `src/main/resources/db/migration/V2__create_tasks.sql`

```sql
CREATE TABLE tasks (
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    due_date    DATE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_owner_id ON tasks (owner_id);
```

### `src/test/java/com/example/lumio/task/TaskControllerTest.java` (excerpt)

```java
package com.example.lumio.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskService taskService;

    @Test
    void list_returnsOwnersTasks() throws Exception {
        when(taskService.listForOwner(any())).thenReturn(List.of(TaskFixtures.response("Write report")));

        mockMvc.perform(get("/api/tasks")
                        .with(jwt().jwt(j -> j.subject("7f1c0a52-3c1e-4d0b-9a57-2f3b8c1d9e10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Write report"));
    }
}
```

### `src/test/java/com/example/lumio/TestcontainersConfiguration.java`

```java
package com.example.lumio;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
}
```

### `README.md`

````markdown
# Lumio

A task-management REST API built with Spring Boot 3.5 and Java 21. Serves the Lumio web client.

## Getting Started

Requires JDK 21 and Docker (for PostgreSQL and Testcontainers).

```bash
docker compose up -d          # local PostgreSQL 16
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The API listens on [http://localhost:8080/api](http://localhost:8080/api).

## Development

- `./mvnw test` — unit and `@WebMvcTest` slice tests (JUnit 5 + Mockito)
- `./mvnw verify` — full build including Testcontainers-backed `*IT` integration tests
- `./mvnw package` — build the executable jar (`target/lumio-1.0.0.jar`)

## Architecture

- **Layered by feature**: `controller → service → repository` inside each package (`auth`, `task`, `user`)
- **DTOs as Java records**; entities never leave the service layer
- **Errors** mapped to RFC 7807 `ProblemDetail` by `GlobalExceptionHandler`
- **Schema** owned by Flyway (`ddl-auto: validate`)

## Known Issues

- `LoginRequest.password` lacks `@NotBlank` (empty password yields 401 instead of 400)
- `GET /api/tasks` has no filtering, sorting, paging, or search
- Logout is client-side only; issued JWTs stay valid until expiry
````

---

## Usage in Eval Scenarios

Each scenario `.md` will reference this fictional project. Example:

```markdown
## Project context (pre-scanned)

> The project has been fully scanned. Use the context below as Phase 2 output.
> Do not call Read, Glob, Bash, or any filesystem tools.

**Project**: Lumio (task-management REST API, Spring Boot 3.5 + Java 21)

### Key files:
- src/main/java/com/example/lumio/auth/LoginRequest.java — request record with BUG noted below
- src/main/java/com/example/lumio/task/TaskController.java — task REST endpoints
- src/main/java/com/example/lumio/task/TaskRepository.java — Spring Data JPA repository, no search queries yet
```
