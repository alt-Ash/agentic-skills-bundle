# Scenario 01: Code Findings Audit (Vortex API)

## Request

Audit the Vortex Spring Boot API for security vulnerabilities. Exploit all confirmed findings. Produce the full report including the HANDOFF BLOCK.

## Pre-scanned project (Steps 1–3 complete)

> Steps 1–3 have been fully executed. Treat the output below as complete and accurate. Do NOT run any Bash, Grep, or filesystem commands.

### Step 1 — Project shape

```
# Spring Boot starters / auth / security libs present:
spring-boot-starter-web
spring-boot-starter-data-jpa
jjwt (io.jsonwebtoken)
postgresql (driver)

# NOT present (no matches):
spring-boot-starter-security's HeadersConfigurer customization
bucket4j / resilience4j-ratelimiter
RateLimiter

# Entry point
src/main/java/com/vortex/VortexApiApplication.java

# JDK version: 17 (<java.version>17</java.version> in pom.xml)
# Build tool: Maven, pom.xml present
```

`pom.xml` (abridged):
```xml
&lt;project&gt;
  &lt;groupId&gt;com.vortex&lt;/groupId&gt;
  &lt;artifactId&gt;vortex-api&lt;/artifactId&gt;
  &lt;version&gt;1.0.0&lt;/version&gt;
  &lt;dependencies&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;&lt;artifactId&gt;spring-boot-starter-web&lt;/artifactId&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;&lt;artifactId&gt;spring-boot-starter-data-jpa&lt;/artifactId&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;io.jsonwebtoken&lt;/groupId&gt;&lt;artifactId&gt;jjwt-api&lt;/artifactId&gt;&lt;version&gt;0.12.3&lt;/version&gt;&lt;/dependency&gt;
    &lt;dependency&gt;&lt;groupId&gt;org.postgresql&lt;/groupId&gt;&lt;artifactId&gt;postgresql&lt;/artifactId&gt;&lt;/dependency&gt;
  &lt;/dependencies&gt;
&lt;/project&gt;
```

### Step 2 — OWASP Dependency-Check triage output

```json
{
  "confirmed": [],
  "suppressed": [],
  "summary": {
    "total": 0,
    "confirmed": 0,
    "suppressed": 0
  }
}
```

No confirmed vulnerable dependencies. No dependency findings.

### Step 3 — OWASP A01–A10 grep results

**A01 — Broken Access Control**
```
# Controller methods without @PreAuthorize/@Secured — no matches flagged (endpoints are intentionally public)
# @CrossOrigin(origins = "*") — no matches
# JWT missing algorithm allowlist — no matches
```

**A02 — Cryptographic Failures**
```
# java.util.Random in security context
src/main/java/com/vortex/auth/PasswordResetService.java:31:  String token = new Random().nextLong() + "";

# Hardcoded JWT secret — no matches (uses @Value("${jwt.secret}") from environment)
# Weak hash (MD5/SHA-1) — no matches
# TLS trust manager disabled — no matches
```

`src/main/java/com/vortex/auth/PasswordResetService.java` (lines 24–38, full context):
```java
@Service
public class PasswordResetService {

    private final UserRepository userRepository;

    public PasswordResetService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void requestReset(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));

        String token = new Random().nextLong() + "";   // line 31
        user.setResetToken(token);
        userRepository.save(user);
    }
}
```

**A03 — Injection**
```
# JPQL/native-query injection via string concatenation
src/main/java/com/vortex/user/UserRepository.java:19:  @Query(value = "SELECT * FROM users WHERE id = " + "?1", nativeQuery = true)

# SpEL/script evaluation of untrusted input — no matches
# Command injection via Runtime.exec/ProcessBuilder — no matches
# Path traversal — no matches
```

`src/main/java/com/vortex/user/UserController.java` (lines 15–27, full context):
```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/{id}")
    public ResponseEntity&lt;?&gt; getUser(@PathVariable String id) {
        try {
            User user = userRepository.findByIdRaw(id);   // line 23, see UserRepository below
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));   // also leaks exception message
        }
    }
}
```

`src/main/java/com/vortex/user/UserRepository.java` (the offending native query):
```java
public interface UserRepository extends JpaRepository&lt;User, Long&gt; {

    @Query(value = "SELECT * FROM users WHERE id = " + "?1", nativeQuery = true)   // line 19 — string-built, not parameterized via Spring Data's ?1 binding as intended, concatenation pattern flagged
    User findByIdRaw(@Param("1") String id);
}
```

**A04 — Insecure Design**
```
# Rate limiting — no matches (bucket4j, resilience4j-ratelimiter, RateLimiter — none present)
# File upload — no matches
# Password reset token entropy — see A02 above (java.util.Random token)
```

**A05 — Security Misconfiguration**
```
# HeadersConfigurer / security headers — no matches in any SecurityFilterChain bean
# CORS — no matches (no CorsConfigurationSource bean)
# Stack trace / exception message leaked — see UserController.java:25 (e.getMessage() returned in response body)
# application.yml secrets tracked in git — git ls-files application.yml: tracked, but jwt.secret sourced from ${JWT_SECRET} env var, not a literal (no finding)
# Actuator endpoints exposed — /actuator/env and /actuator/heapdump reachable with no authentication configured
```

`src/main/java/com/vortex/VortexApiApplication.java` (full file):
```java
@SpringBootApplication
public class VortexApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(VortexApiApplication.class, args);
    }
    // No SecurityFilterChain bean defined anywhere in the project — no headers configuration exists
}
```

**A06 — Vulnerable & Outdated Components**: covered in Step 2. No confirmed findings.

**A07 — Authentication Failures**
```
# Jwts.parser() without algorithm allowlist — no matches (JWT verification not implemented; only Jwts.builder().signWith(...) found for issuing)
# BCryptPasswordEncoder work factor — no matches (BCrypt not in use; passwords are not yet hashed in this early-stage project — pre-existing gap, out of scope for this scenario's findings)
```

**A08 — Data Integrity**
```
# Unsafe deserialization (ObjectInputStream.readObject on untrusted data) — no matches
```

**A09 — Logging Failures**
```
# Sensitive data (password/token/secret) printed via log.info/System.out.println — no matches
# Swallowed exception detail returned to client — UserController.java:25 returns e.getMessage() (see A05)
```

**A10 — SSRF**
```
# RestTemplate/WebClient call with URL sourced from request input — no matches
```

## Pre-baked exploitation results (Step 5)

> User confirmed: **A — run all findings**.
> The curl/script results below ARE the Step 5 exploitation output. Do NOT run any network commands. Use these results verbatim.

### Exploit — C-01 — `java.util.Random` used for cryptographic randomness
**Status:** Confirmed (code review)
**Payload:** N/A — internal token generation; no HTTP endpoint exposes the raw token for direct injection
**Response:** N/A
**Impact:** Reset tokens generated via `new Random().nextLong()` are produced by a 48-bit linear congruential generator, not a CSPRNG. An attacker who observes a small number of generated tokens (or knows the approximate generation time) can predict the internal seed and derive subsequent tokens, enabling account takeover without knowing the victim's password.

### Exploit — C-02 — SQL injection via string-concatenated native query
**Status:** Confirmed
**Payload:** `curl -s "http://localhost:8080/api/users/1'"`
**Response:** `HTTP 500 {"error":"org.postgresql.util.PSQLException: syntax error at or near \"'\""}`
**Impact:** Full read access to any row in the `users` table. Union-based extraction possible via a crafted `id` path value once the native query's string-concatenation pattern is confirmed exploitable.

### Exploit — C-03 — No security headers configured
**Status:** Confirmed
**Payload:** `curl -sI http://localhost:8080/api/users/1`
**Response:**
```
HTTP/1.1 200
Content-Type: application/json
```
**Impact:** No `X-Frame-Options`, `X-Content-Type-Options`, `Strict-Transport-Security`, or `Content-Security-Policy` headers. Browser-based attacks (clickjacking, MIME sniffing, XSS amplification) are unmitigated.

### Exploit — C-04 — No rate limiting on any endpoint
**Status:** Confirmed
**Payload:** `for i in $(seq 1 100); do curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/auth/forgot-password -H 'Content-Type: application/json' -d '{"email":"victim@example.com"}'; done`
**Response:** 100 × `200` — all requests succeed without throttling
**Impact:** `/api/auth/forgot-password` is brute-forceable without limit. Combined with C-01 (weak token entropy), an attacker can enumerate tokens by flooding the endpoint and testing predictions.

## Instructions

⚠️ **CRITICAL: This is a TEST SCENARIO. Do NOT run any commands.**

**Steps 1–3**: SKIP entirely. The "## Pre-scanned project" section above IS your complete Steps 1–3 output. Treat it as fact.

**Step 4**: Assign severity from the fixed catalog for each finding identified in the grep results.

**Step 5 (Exploitation)**: SKIP running any curl commands. The "## Pre-baked exploitation results" section above IS your complete Step 5 output. Incorporate those results verbatim into your report.

**Step 6**: Produce the complete security audit report following your canonical format.
