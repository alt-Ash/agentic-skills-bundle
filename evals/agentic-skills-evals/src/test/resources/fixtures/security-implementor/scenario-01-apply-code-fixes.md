# Scenario 01: Apply Code Fixes (Helios API)

## Request

Apply all open findings from the security audit report below for the Helios Spring Boot API. The HANDOFF BLOCK contains 10 code findings. Produce the corrected code for each finding and emit the updated audit report.

## Pre-scanned source code

> The project "Helios" has been fully scanned. Treat the code below as complete and accurate.
> **Do not call Read, Glob, Bash, or any filesystem tools.**

### pom.xml (abridged)

```xml
&lt;project&gt;
  &lt;groupId&gt;com.helios&lt;/groupId&gt;
  &lt;artifactId&gt;helios-api&lt;/artifactId&gt;
  &lt;version&gt;1.0.0&lt;/version&gt;
  &lt;dependencies&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;&lt;artifactId&gt;spring-boot-starter-web&lt;/artifactId&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;&lt;artifactId&gt;spring-boot-starter-data-jpa&lt;/artifactId&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.security&lt;/groupId&gt;&lt;artifactId&gt;spring-security-crypto&lt;/artifactId&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;io.jsonwebtoken&lt;/groupId&gt;&lt;artifactId&gt;jjwt-api&lt;/artifactId&gt;&lt;version&gt;0.12.3&lt;/version&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.postgresql&lt;/groupId&gt;&lt;artifactId&gt;postgresql&lt;/artifactId&gt;&lt;/dependency&gt;
  &lt;/dependencies&gt;
&lt;/project&gt;
```

### src/main/java/com/helios/HeliosApiApplication.java (full file)

```java
@SpringBootApplication
public class HeliosApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeliosApiApplication.class, args);   // line 8 — no SecurityFilterChain bean defined anywhere
    }
}
```

### src/main/java/com/helios/config/CorsConfig.java (full file)

```java
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*"));   // line 11 — wildcard origin accepted
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

### src/main/java/com/helios/config/JwtConfig.java (full file)

```java
public class JwtConfig {
    public static final String JWT_SECRET = "helios-dev-secret-key-do-not-use-in-prod";   // line 3
    public static final long JWT_EXPIRY_MS = 86_400_000L;
}
```

### src/main/java/com/helios/auth/JwtAuthFilter.java (full file)

```java
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(req);
        if (token == null) {
            res.sendError(401, "No token");
            return;
        }
        try {
            Claims claims = Jwts.parser()   // line 12 — no .verifyWith()/algorithm allowlist configured
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            req.setAttribute("user", claims);
            chain.doFilter(req, res);
        } catch (Exception e) {
            res.sendError(401, "Invalid token");
        }
    }
}
```

### src/main/java/com/helios/error/GlobalExceptionHandler.java (full file)

```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity&lt;?&gt; handle(Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", e.getMessage(), "stack", ExceptionUtils.getStackTrace(e)));   // line 9
    }
}
```

### src/main/java/com/helios/auth/AuthController.java (full file)

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")   // line 20 — no rate limiting applied here
    public ResponseEntity&lt;?&gt; login(@RequestBody LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -&gt; new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .expiration(new Date(System.currentTimeMillis() + JwtConfig.JWT_EXPIRY_MS))
                .signWith(Keys.hmacShaKeyFor(JwtConfig.JWT_SECRET.getBytes()))
                .compact();
        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity&lt;?&gt; forgotPassword(@RequestBody ForgotPasswordRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -&gt; new ResponseStatusException(HttpStatus.NOT_FOUND));
        String resetToken = new Random().nextLong() + "";   // line 54
        user.setResetToken(resetToken);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Reset email sent"));
    }
}
```

### src/main/java/com/helios/order/OrderController.java (full file)

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orderRepository;

    @GetMapping
    public ResponseEntity&lt;?&gt; listOrders(@AuthenticationPrincipal Claims user) {
        return ResponseEntity.ok(orderRepository.findByUserId(user.get("userId", Long.class)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity&lt;?&gt; getOrder(@PathVariable String orderId) {
        try {
            Order order = orderRepository.findByIdRaw(orderId);   // line 31, see OrderRepository below
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
```

`src/main/java/com/helios/order/OrderRepository.java` (the offending native query):
```java
public interface OrderRepository extends JpaRepository&lt;Order, Long&gt; {
    @Query(value = "SELECT * FROM orders WHERE id = " + "?1", nativeQuery = true)
    Order findByIdRaw(@Param("1") String id);
}
```

### src/main/java/com/helios/calc/CalcController.java (full file)

```java
@RestController
@RequestMapping("/api/calc")
public class CalcController {

    private final ExpressionParser parser = new SpelExpressionParser();

    @PostMapping("/evaluate")
    public ResponseEntity&lt;?&gt; evaluate(@RequestBody EvaluateRequest req) {
        try {
            Object result = parser.parseExpression(req.formula()).getValue();   // line 18
            return ResponseEntity.ok(Map.of("result", result));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "Invalid formula"));
        }
    }
}
```

### src/main/java/com/helios/files/FileController.java (full file)

```java
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Path UPLOADS_DIR = Paths.get(System.getProperty("user.dir"), "uploads");

    @GetMapping("/download/{filename}")
    public ResponseEntity&lt;byte[]&gt; download(@PathVariable String filename) throws IOException {
        byte[] content = Files.readAllBytes(Paths.get("./uploads/" + filename));   // line 27
        return ResponseEntity.ok(content);
    }
}
```

## Pre-baked execution results

> Steps 2, 3, and 5 have been pre-run. Treat the results below as complete and accurate.

### Step 2 — OWASP Dependency-Check baseline

```json
{ "critical": 0, "high": 0, "moderate": 0, "low": 0 }
```

All 10 findings are code vulnerabilities, not dependency vulnerabilities.

### Step 3 — Dependency additions done

```
[INFO] Added org.springframework.boot:spring-boot-starter-security:jar:3.3.5
[INFO] mvn dependency-check:check — 0 vulnerabilities across 42 artifacts
```

`pom.xml` now includes `spring-boot-starter-security` (provides the `SecurityFilterChain`/`.headers()` DSL used for the fix).

### Step 5 — Post-fix OWASP Dependency-Check

```json
{ "critical": 0, "high": 0, "moderate": 0, "low": 0 }
```

## Handoff Block

```json
{
  "schema": "security-handoff/v1",
  "targetProject": "helios-api",
  "auditDate": "2026-06-01",
  "codeFindings": [
    {
      "id": "C-01",
      "title": "SQL/JPQL Injection via String-Concatenated Native Query",
      "severity": "High",
      "cwe": "CWE-89",
      "owasp": "A03",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/order/OrderRepository.java:31"
    },
    {
      "id": "C-02",
      "title": "java.util.Random Used for Cryptographic Randomness (Password Reset Token)",
      "severity": "Critical",
      "cwe": "CWE-338",
      "owasp": "A02",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/auth/AuthController.java:54"
    },
    {
      "id": "C-03",
      "title": "Hardcoded JWT Secret",
      "severity": "Critical",
      "cwe": "CWE-321",
      "owasp": "A02",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/config/JwtConfig.java:3"
    },
    {
      "id": "C-04",
      "title": "Dynamic Expression Evaluation of Untrusted Input (SpEL Injection)",
      "severity": "Critical",
      "cwe": "CWE-95",
      "owasp": "A03",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/calc/CalcController.java:18"
    },
    {
      "id": "C-05",
      "title": "Path Traversal in File Download",
      "severity": "High",
      "cwe": "CWE-22",
      "owasp": "A03",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/files/FileController.java:27"
    },
    {
      "id": "C-06",
      "title": "JWT Algorithm Not Pinned During Verification",
      "severity": "High",
      "cwe": "CWE-327",
      "owasp": "A07",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/auth/JwtAuthFilter.java:12"
    },
    {
      "id": "C-07",
      "title": "No Security Headers Configuration",
      "severity": "High",
      "cwe": "CWE-693",
      "owasp": "A05",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/HeliosApiApplication.java:8"
    },
    {
      "id": "C-08",
      "title": "No Rate Limiting on Login Endpoint",
      "severity": "High",
      "cwe": "CWE-770",
      "owasp": "A04",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/auth/AuthController.java:20"
    },
    {
      "id": "C-09",
      "title": "Stack Trace / Internal Error Leaked to Client",
      "severity": "Moderate",
      "cwe": "CWE-209",
      "owasp": "A05",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/error/GlobalExceptionHandler.java:9"
    },
    {
      "id": "C-10",
      "title": "CORS Wildcard Origin Allows Any Domain",
      "severity": "High",
      "cwe": "CWE-942",
      "owasp": "A05",
      "status": "open",
      "type": "code",
      "location": "src/main/java/com/helios/config/CorsConfig.java:11"
    }
  ],
  "dependencyFindings": [],
  "verifyCommand": "mvn org.owasp:dependency-check-maven:check"
}
```

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO. Do NOT call any tools.**

**Steps 2, 3, 5**: SKIP. The "## Pre-baked execution results" section above ARE those steps' outputs. Do not call Bash or Maven.

**Step 4**: For each finding in the HANDOFF BLOCK, determine the correct fix and show it as a before/after code block in your response. Do not call Edit or Write.

**Step 6**: Follow your Step 6 instructions.
