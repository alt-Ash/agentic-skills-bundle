#!/usr/bin/env bash
# OWASP A01-A10 source code scanner for Java/Spring Boot projects.
# Usage: bash security-scan.sh [target-dir]
# Output: one labeled section per OWASP category, listing matching file:line entries.
# Exit 0 always — empty sections mean no matches found.

TARGET="${1:-.}"
GREP_OPTS=(--include="*.java" --include="*.kt" --exclude-dir=target --exclude-dir=build -rn)

run_grep() {
  local label="$1"; shift
  local result
  result=$(grep "${GREP_OPTS[@]}" "$@" "$TARGET" 2>/dev/null | head -20)
  if [[ -n "$result" ]]; then
    echo "  [$label]"
    echo "$result" | sed 's/^/    /'
  fi
}

echo "=== A01 — Broken Access Control ==="
run_grep "endpoint-no-guard" \
  -E "@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\(" |
  grep -v "PreAuthorize\|Secured\|RolesAllowed\|permitAll\|SecurityFilterChain" 2>/dev/null || true
grep "${GREP_OPTS[@]}" \
  -E "@(PathVariable|RequestParam|RequestBody)" "$TARGET" 2>/dev/null \
  | grep -E "findById|findOne|getById" | head -20 | sed 's/^/  [idor] /'
run_grep "cors-wildcard" -E "allowedOrigins\(\"\*\"\)|@CrossOrigin\((origins ?= ?)?\"\*\"\)"
run_grep "cors-header" "Access-Control-Allow-Origin"
run_grep "jwt-no-algorithm-pin" -E "Jwts\.parser|parserBuilder\(\)" | grep -v "setSigningKey\|verifyWith\|algorithms" 2>/dev/null || true
run_grep "logout" -E "logout\(\)|invalidateSession|SecurityContextHolder\.clearContext"
run_grep "csrf-disabled" -E "csrf\(\)\.disable\(\)|CsrfConfigurer"

echo ""
echo "=== A02 — Cryptographic Failures ==="
run_grep "weak-hash" -E "MessageDigest\.getInstance\(.(MD5|SHA-1|SHA1)."
run_grep "insecure-random" -E "new Random\(\)|java\.util\.Random" | grep -v "test\|Test\|spec\|seed\|jitter\|retry" 2>/dev/null || true
run_grep "hardcoded-jwt-secret" -E "Keys\.hmacShaKeyFor\(.*\.getBytes|signWith\(" | grep -v "System\.getenv\|@Value\|Environment\.\|config\." 2>/dev/null || true
run_grep "static-iv" -E "new IvParameterSpec\(new byte\["
run_grep "tls-verify-disabled" -E "checkServerTrusted.*\{\s*\}|TrustAllStrategy|NoopHostnameVerifier|X509TrustManager"
run_grep "bcrypt-strength" -E "new BCryptPasswordEncoder\([0-9]"

echo ""
echo "=== A03 — Injection ==="
run_grep "jpql-native-concat" -E "@Query\(.*nativeQuery\s*=\s*true|createNativeQuery\(|createQuery\(" |
  grep -E "\+ ?(request|param|input|id|name)" 2>/dev/null || true
run_grep "spel-injection" -E "SpelExpressionParser|new SpelExpression\(" | grep -E "RequestParam|RequestBody|PathVariable" 2>/dev/null || true
run_grep "command-injection" -E "Runtime\.getRuntime\(\)\.exec\(|new ProcessBuilder\(" | grep -E "RequestParam|RequestBody|PathVariable" 2>/dev/null || true
run_grep "eval-dynamic-code" -E "ScriptEngine|GroovyShell|\.eval\("
run_grep "path-traversal" -E "Paths\.get\(|new File\(" | grep -E "RequestParam|RequestBody|PathVariable" 2>/dev/null || true

echo ""
echo "=== A04 — Insecure Design ==="
run_grep "rate-limit" -E "RateLimiter|Bucket4j|@RateLimiter|ThrottlingFilter"
run_grep "file-upload" -E "MultipartFile|@RequestParam.*MultipartFile"
run_grep "password-reset" -E "resetPassword|forgotPassword|verifyToken|sendOtp|verifyOtp"

echo ""
echo "=== A05 — Security Misconfiguration ==="
run_grep "security-headers" -E "HeadersConfigurer|headers\(\)\.contentSecurityPolicy|HttpSecurity"
run_grep "stack-trace-leak" -E "e\.getMessage\(\)|e\.printStackTrace\(\)|ExceptionUtils\.getStackTrace" | grep -E "ResponseEntity|@ExceptionHandler" 2>/dev/null || true
run_grep "hardcoded-secrets" -E "password\s*=\s*\"|secret\s*=\s*\"|apiKey\s*=\s*\"" | grep -v "Test\.\|test\.\|example\|placeholder\|System\.getenv\|@Value\|Environment\." 2>/dev/null || true
echo "  [application-properties-git-tracked]"
git -C "$TARGET" ls-files "application.properties" "application*.yml" "application*.yaml" ".env" ".env.*" 2>/dev/null | sed 's/^/    /'
git -C "$TARGET" log --all --oneline -- "application.properties" "application*.yml" "application*.yaml" ".env" ".env.*" 2>/dev/null | head -5 | sed 's/^/    git-log: /'
run_grep "actuator-exposed" -E "management\.endpoints\.web\.exposure\.include\s*=\s*\*|include:\s*\*"

echo ""
echo "=== A06 — Vulnerable & Outdated Components ==="
echo "  Covered in Step 2 (OWASP Dependency-Check triage). No grep needed."

echo ""
echo "=== A07 — Identification & Authentication Failures ==="
run_grep "jwt-no-algorithm-pin" -E "Jwts\.parser|parserBuilder\(\)" | grep -v "setSigningKey\|verifyWith\|algorithms" 2>/dev/null || true
run_grep "session-config" -E "SessionCreationPolicy" -A5 | grep -E "STATELESS|IF_REQUIRED|ALWAYS" 2>/dev/null || true
run_grep "custom-auth-provider" -E "implements AuthenticationProvider|extends.*UserDetailsService"

echo ""
echo "=== A08 — Software & Data Integrity Failures ==="
run_grep "unsafe-deser" -E "ObjectInputStream.*readObject|XMLDecoder\(" | grep -v "Test\." 2>/dev/null || true
run_grep "unsigned-cookies" -E "new Cookie\(" | grep -v "setSecure(true)\|setHttpOnly(true)" 2>/dev/null || true
grep -rn --include="*.html" --include="*.jsp" --exclude-dir=target --exclude-dir=build \
  -E "<script.*src=|<link.*href=" "$TARGET" 2>/dev/null \
  | grep -v "integrity=" | head -20 | sed 's/^/  [sri-missing] /'

echo ""
echo "=== A09 — Security Logging & Monitoring Failures ==="
run_grep "sensitive-in-log" -E "log\.(info|debug|warn|error)\(|System\.out\.println\(" | grep -E "password|token|secret|credential" | grep -v "Test\.\|test\." 2>/dev/null || true
run_grep "swallowed-catch" "catch" -A3 | grep -B1 "^\s*}" | grep "catch" | grep -v "log\.\|logger\.\|throw " 2>/dev/null || true

echo ""
echo "=== A10 — Server-Side Request Forgery ==="
run_grep "ssrf" -E "RestTemplate\(\)\.(getForObject|postForObject|exchange)|WebClient\.(get|post)\(\)|HttpClient\.send\(" | grep -E "RequestParam|RequestBody|PathVariable" 2>/dev/null || true
run_grep "no-timeout" -E "RestTemplate\(\)|WebClient\.builder\(\)" | grep -v "setConnectTimeout\|responseTimeout\|ConnectTimeout" 2>/dev/null || true
run_grep "error-leak" -E "e\.getMessage\(\)|e\.printStackTrace\(\)" | grep -E "ResponseEntity|@ExceptionHandler" 2>/dev/null || true
