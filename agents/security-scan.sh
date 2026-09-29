#!/usr/bin/env bash
# OWASP A01-A10 source code scanner for Node.js projects.
# Usage: bash security-scan.sh [target-dir]
# Output: one labeled section per OWASP category, listing matching file:line entries.
# Exit 0 always — empty sections mean no matches found.

TARGET="${1:-.}"
GREP_OPTS=(--include="*.ts" --include="*.js" --exclude-dir=node_modules -rn)

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
run_grep "routes-no-guard" \
  -E "router\.|app\.get|app\.post|app\.put|app\.delete|@Get\(|@Post\(|@Put\(|@Delete\(|@Patch\(" |
  grep -v "auth\|guard\|Guard\|middleware\|protect\|verify\|Interceptor" 2>/dev/null || true
grep "${GREP_OPTS[@]}" \
  -E "req\.(params|query|body)\." "$TARGET" 2>/dev/null \
  | grep -E "findById|findOne|findByPk|where.*id" | head -20 | sed 's/^/  [idor] /'
run_grep "cors-wildcard" -E "enableCors|cors\("
run_grep "cors-header" "Access-Control-Allow-Origin"
run_grep "jwt-no-algorithms" "jwt\.verify" | grep -v "algorithms:" 2>/dev/null || true
run_grep "logout" -E "logout|signOut|session\.destroy"
run_grep "csrf" -E "csurf|csrf|doubleCsrf|SameSite"

echo ""
echo "=== A02 — Cryptographic Failures ==="
run_grep "weak-hash" -E "createHash.*(md5|sha1)"
run_grep "math-random" "Math\.random()" | grep -v "test\|spec\|mock\|seed\|jitter\|retry" 2>/dev/null || true
run_grep "hardcoded-jwt" -E "jwt\.(sign|verify)" | grep -v "process\.env\|config\." 2>/dev/null || true
run_grep "tls-disabled" -E "rejectUnauthorized.*false|NODE_TLS_REJECT_UNAUTHORIZED"
run_grep "bcrypt" -E "bcrypt\.(hash|genSalt)"

echo ""
echo "=== A03 — Injection ==="
grep "${GREP_OPTS[@]}" \
  -E "db\.query|pool\.query|knex\.raw|sequelize\.query" "$TARGET" 2>/dev/null \
  | grep -v '\$[0-9]\|?' | head -20 | sed 's/^/  [sql-injection] /'
run_grep "nosql-injection" -E "\.(find|findOne|findById)\(" | grep -E "req\.(body|params|query)" 2>/dev/null || true
run_grep "command-injection" -E "exec\(|execSync\(|spawn\(" | grep -E "req\.(body|params|query)" 2>/dev/null || true
run_grep "eval" -E "eval\(|new Function\("
run_grep "path-traversal" -E "fs\.readFile|fs\.writeFile|path\.join|path\.resolve" | grep -E "req\.(body|params|query)" 2>/dev/null || true

echo ""
echo "=== A04 — Insecure Design ==="
run_grep "rate-limit" -E "rateLimit|throttle|@Throttle|ThrottlerModule|express-rate-limit"
run_grep "file-upload" -E "multer|busboy|formidable|@UseInterceptors.*FileInterceptor"
run_grep "password-reset" -E "reset.*password|forgot.*password|verify.*token|sendOtp|verifyOtp"

echo ""
echo "=== A05 — Security Misconfiguration ==="
run_grep "helmet" "helmet"
run_grep "stack-trace-leak" -E "err\.(stack|message)" | grep -E "res\.(send|json)" 2>/dev/null || true
run_grep "hardcoded-secrets" -E "password\s*=\s*['\"].|secret\s*=\s*['\"].|apiKey\s*=\s*['\"]." | grep -v "test\.\|spec\.\|example\|placeholder\|process\.env\|config\." 2>/dev/null || true
echo "  [env-git-tracked]"
git -C "$TARGET" ls-files .env .env.* 2>/dev/null | sed 's/^/    /'
git -C "$TARGET" log --all --oneline -- ".env" ".env.*" 2>/dev/null | head -5 | sed 's/^/    git-log: /'
run_grep "x-powered-by" -E "x-powered-by|trust proxy|trustProxy"
jq -r '(.dependencies // {}) | keys[]' "$TARGET/package.json" 2>/dev/null | grep -E 'xml2js|fast-xml-parser|libxmljs' | sed 's/^/  [xxe-risk] /'
jq -r '(.dependencies // {}) | to_entries[] | select(.value | test("^[\\^~]")) | "  [float-version] \(.key): \(.value)"' "$TARGET/package.json" 2>/dev/null
jq -r '(.scripts // {}) | to_entries[] | select(.key | test("install|prepare")) | "  [postinstall] \(.key): \(.value)"' "$TARGET/package.json" 2>/dev/null

echo ""
echo "=== A06 — Vulnerable & Outdated Components ==="
echo "  Covered in Step 2 (npm audit triage). No grep needed."

echo ""
echo "=== A07 — Identification & Authentication Failures ==="
run_grep "jwt-no-algorithms" "jwt\.verify" | grep -v "algorithms:" 2>/dev/null || true
run_grep "session-config" -E "session\(\{" -A10 | grep -E "secure|httpOnly|sameSite|maxAge" 2>/dev/null || true
run_grep "passport" -E "new LocalStrategy|new JwtStrategy|passport\."

echo ""
echo "=== A08 — Software & Data Integrity Failures ==="
run_grep "unsafe-deser" -E "JSON\.parse|unserialize|node-serialize" | grep -E "req\.(body|query|params|cookies)" 2>/dev/null || true
run_grep "unsigned-cookies" "req\.cookies\." | grep -v "signedCookies" 2>/dev/null || true
grep -rn --include="*.html" --include="*.ejs" --include="*.pug" --include="*.hbs" \
  --exclude-dir=node_modules -E "<script.*src=|<link.*href=" "$TARGET" 2>/dev/null \
  | grep -v "integrity=" | head -20 | sed 's/^/  [sri-missing] /'

echo ""
echo "=== A09 — Security Logging & Monitoring Failures ==="
run_grep "sensitive-in-log" -E "console\.(log|error)" | grep -E "password|token|login|auth|fail" | grep -v "test\|spec" 2>/dev/null || true
run_grep "sensitive-in-logger" -E "\.(log|info|warn|error)" | grep -E "password|token|secret|credit" 2>/dev/null || true
run_grep "swallowed-catch" "catch" -A3 | grep -B1 "^\s*}" | grep "catch" | grep -v "log\|logger\|next(" 2>/dev/null || true

echo ""
echo "=== A10 — Server-Side Request Forgery ==="
run_grep "ssrf" -E "fetch\(|axios\.|http\.request|got\(" | grep -E "req\.(body|params|query)" 2>/dev/null || true
run_grep "no-timeout" -E "fetch\(|axios\.|http\.request" | grep -v "timeout" 2>/dev/null || true
run_grep "error-leak" -E "err\.(stack|message)" | grep -E "res\.(send|json)" 2>/dev/null || true
