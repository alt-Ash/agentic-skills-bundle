# Scenario 01: New API Endpoint (POST /api/users)

## Request

Implement a `POST /api/users` endpoint in the Prism API using TDD. The endpoint must:
- Accept a JSON body with `name` (string) and `email` (string)
- Return `200` with `{ id, name, email }` on valid input (use `UUID.randomUUID()` for the id)
- Return `400` with `{ "error": "Name is required" }` if `name` is missing or empty
- Return `400` with `{ "error": "Email is required" }` if `email` is missing or empty
- Return `400` with `{ "error": "Invalid email format" }` if `email` does not match a basic email pattern

## Pre-scanned project (Phase 0 complete)

> Phase 0 has been fully executed. Treat the information below as complete and accurate.
> **Do not call Read, Glob, Bash, or any filesystem tools.**

### Directory structure

```
prism-api/
├── src/main/java/com/prism/
│   ├── PrismApiApplication.java
│   ├── user/
│   │   └── UserController.java     ← does not exist yet
│   └── health/
│       └── HealthController.java
├── src/test/java/com/prism/
│   ├── user/
│   │   └── UserControllerTest.java ← does not exist yet
│   └── health/
│       └── HealthControllerTest.java
└── pom.xml
```

### pom.xml (abridged)

```xml
&lt;project&gt;
  &lt;groupId&gt;com.prism&lt;/groupId&gt;
  &lt;artifactId&gt;prism-api&lt;/artifactId&gt;
  &lt;version&gt;1.0.0&lt;/version&gt;
  &lt;properties&gt;
    &lt;java.version&gt;21&lt;/java.version&gt;
  &lt;/properties&gt;
  &lt;dependencies&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;&lt;artifactId&gt;spring-boot-starter-web&lt;/artifactId&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;&lt;artifactId&gt;spring-boot-starter-validation&lt;/artifactId&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;&lt;artifactId&gt;spring-boot-starter-test&lt;/artifactId&gt;&lt;scope&gt;test&lt;/scope&gt;&lt;/dependency&gt;
  &lt;/dependencies&gt;
&lt;/project&gt;
```

### src/main/java/com/prism/PrismApiApplication.java (full file)

```java
@SpringBootApplication
public class PrismApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrismApiApplication.class, args);
    }
}
```

### src/main/java/com/prism/health/HealthController.java (full file)

```java
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity&lt;Map&lt;String, String&gt;&gt; health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
```

### src/test/java/com/prism/health/HealthControllerTest.java (full file — test pattern reference)

```java
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getHealthReturns200WithStatusOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
```

## Pre-baked execution results

> Phase 1, Phase 3, and Phase 5 have been pre-run. Treat the results below as complete and accurate.

### Phase 1 — Test run (build fails to compile — production class missing)

```
[ERROR] /src/test/java/com/prism/user/UserControllerTest.java:[8,25] cannot find symbol
[ERROR]   symbol:   class UserController
[ERROR]   location: package com.prism.user
[ERROR] -> [Help 1]
[ERROR] COMPILATION ERROR
[ERROR] Failed to execute goal ... (default-testCompile) on project prism-api: Compilation failure
```

### Phase 3 — Full test suite (all tests pass)

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.prism.health.HealthControllerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.prism.user.UserControllerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Phase 5 — Project checks

```
$ mvn checkstyle:check
(no violations — exit 0)

$ mvn compile
(no output — exit 0)

$ mvn package -DskipTests
[INFO] BUILD SUCCESS
```

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO. Do NOT call any tools.**

**Phase 0**: SKIP. The "## Pre-scanned project" section above IS your complete Phase 0 output.

**Phase 1**: Write the test file (`src/test/java/com/prism/user/UserControllerTest.java`, using `@WebMvcTest` + `MockMvc`, matching the pattern shown in `HealthControllerTest.java` above) as a code block in your response. Do not call Write or Edit.

**Phase 2**: The pre-baked Phase 1 run IS your test run output. State why the build fails using that output (a compilation error, since the referenced production class doesn't exist yet — Java's compile step is the "red" signal here, not a runtime failure).

**Phase 3**: Write the production code (`src/main/java/com/prism/user/UserController.java` and any required DTO/validation classes) as code blocks in your response. Do not call Write or Edit.

**Phase 4**: Skip.

**Phase 5**: The pre-baked Phase 5 checks ARE your check outputs. All pass.

**Phase 6**: Follow your Phase 6 instructions.
