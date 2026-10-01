#!/usr/bin/env bash
# Diff-scoped OWASP Top 10:2025 pattern scanner for Java/Spring Boot projects.
#
# Checks only lines added/changed in the current diff (working tree + staged,
# or against a base branch if one is passed), not the whole repository — this
# is what makes it cheap enough to run on every feature change instead of only
# on demand like the full `@security-auditor` audit.
#
# The severity/OWASP/CWE catalog mirrors agents/security-auditor.md's Step 4
# table. It is intentionally duplicated here rather than shared, because
# skills and agents install independently of each other — keep the two in
# sync by hand if the catalog changes.
#
# Usage: bash diff-security-scan.sh [base-branch]
# Output: one labeled section per matched pattern, listing file:line:code.
# Matches are candidates, not confirmed findings — the calling skill/agent
# must read each hit in context before treating it as real (some patterns,
# e.g. Jwts.builder().signWith(...), legitimately appear in code that isn't
# vulnerable).
# Exit 0 always — no matches means a clean diff, not a script failure.

set -uo pipefail

BASE="${1:-}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT" || exit 0

if [[ -n "$BASE" ]]; then
  DIFF_RANGE="$BASE...HEAD"
  FILES=$(git diff --name-only --diff-filter=ACMR "$DIFF_RANGE" -- '*.java' '*.kt' 2>/dev/null)
else
  FILES=$( { git diff --name-only --diff-filter=ACMR -- '*.java' '*.kt' 2>/dev/null; \
             git diff --staged --name-only --diff-filter=ACMR -- '*.java' '*.kt' 2>/dev/null; } \
           | sort -u)
fi

if [[ -z "$FILES" ]]; then
  echo "No changed .java/.kt files to scan."
  exit 0
fi

# Prints the set of added/changed line numbers for a file in the current diff scope.
changed_lines() {
  local file="$1"
  if [[ -n "$BASE" ]]; then
    git diff -U0 "$DIFF_RANGE" -- "$file" 2>/dev/null
  else
    { git diff -U0 -- "$file" 2>/dev/null; git diff --staged -U0 -- "$file" 2>/dev/null; }
  fi | grep -E '^@@' \
    | sed -E 's/^@@ -[0-9]+(,[0-9]+)? \+([0-9]+)(,([0-9]+))? @@.*/\2 \4/' \
    | awk '{start=$1; count=($2==""?1:$2); for (i=0;i<count;i++) print start+i}'
}

check_pattern() {
  local label="$1" severity="$2" owasp="$3" regex="$4"
  local any=0
  for file in $FILES; do
    [[ -f "$file" ]] || continue
    local lines
    lines=$(changed_lines "$file")
    [[ -z "$lines" ]] && continue
    while IFS=: read -r lineno content; do
      [[ -z "$lineno" ]] && continue
      if printf '%s\n' "$lines" | grep -qx "$lineno"; then
        if [[ $any -eq 0 ]]; then
          echo "[$severity] [$owasp] $label"
          any=1
        fi
        echo "  $file:$lineno:$content"
      fi
    done < <(grep -nE "$regex" "$file" 2>/dev/null)
  done
}

check_pattern "cors-wildcard"          High     A05 'allowedOrigins\(\"\*\"\)|@CrossOrigin\((origins ?= ?)?\"\*\"\)|Access-Control-Allow-Origin: ?\*'
check_pattern "hardcoded-jwt-secret"   Critical A02 'signWith\(|Keys\.hmacShaKeyFor\('
check_pattern "insecure-random"        Critical A02 'new Random\(\)'
check_pattern "weak-hash"              High     A02 'MessageDigest\.getInstance\(.(MD5|SHA-1|SHA1).'
check_pattern "tls-verify-disabled"    High     A02 'checkServerTrusted.*\{\s*\}|TrustAllStrategy|NoopHostnameVerifier'
check_pattern "jpql-native-injection"  High     A03 '@Query\(.*nativeQuery\s*=\s*true|createNativeQuery\(|createQuery\('
check_pattern "spel-injection"         Critical A03 'SpelExpressionParser|new SpelExpression\('
check_pattern "command-injection"      Critical A03 'Runtime\.getRuntime\(\)\.exec\(|new ProcessBuilder\('
check_pattern "path-traversal"         High     A03 'Paths\.get\(|new File\('
check_pattern "eval-dynamic-code"      Critical A03 'ScriptEngine|GroovyShell'
check_pattern "missing-auth-guard"     High     A01 '@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\('
check_pattern "stack-trace-leak"       Moderate A05 'e\.getMessage\(\)|e\.printStackTrace\(\)'
check_pattern "ssrf-outbound"          High     A10 'RestTemplate\(\)\.(getForObject|postForObject|exchange)|WebClient\.(get|post)\(\)'
check_pattern "sensitive-log"          Moderate A09 'log\.(info|debug|warn|error)\(.*(password|token|secret)'
check_pattern "bcrypt-weak-rounds"     Moderate A07 'new BCryptPasswordEncoder\([0-9]'

exit 0
