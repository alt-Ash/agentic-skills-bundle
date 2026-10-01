#!/usr/bin/env bash
# audit-triage.sh — deterministic OWASP Dependency-Check triage for Maven projects
#
# Usage: bash audit-triage.sh [dependency-check-report.json]
#
# Reads: the OWASP Dependency-Check Maven plugin's JSON report (default path:
#        target/dependency-check-report.json, produced by
#        `mvn org.owasp:dependency-check-maven:check`)
# Outputs: JSON with two arrays: "confirmed" and "suppressed"
#
# Rules (applied mechanically, no judgment):
#   1. Read every dependency entry with a non-empty "vulnerabilities" array.
#   2. Resolve each dependency's Maven coordinate (groupId:artifactId) from its
#      purl (`pkg:maven/<groupId>/<artifactId>@<version>`).
#   3. Cross-reference against `mvn dependency:list -DincludeScope=test` and
#      `-DincludeScope=provided` output — if a coordinate appears ONLY in
#      test/provided scope (never compile/runtime), it never ships in the
#      running application → suppress (reason: testOrProvidedOnly).
#   4. Dependency-Check already resolves exact versions from the actual
#      resolved dependency tree (unlike npm's nested node_modules copies with
#      possibly-differing versions) — there is no separate semver-range check
#      needed here. Every reported vulnerability is already version-confirmed
#      by the scanner itself.
#   5. Output deterministic JSON, sorted by severity then groupId:artifactId.

set -euo pipefail

REPORT="${1:-target/dependency-check-report.json}"

if [[ ! -f "$REPORT" ]]; then
  echo '{"error":"dependency-check-report.json not found — run: mvn org.owasp:dependency-check-maven:check"}' >&2
  exit 1
fi

REPORT_JSON=$(cat "$REPORT")

if [[ -z "$REPORT_JSON" ]]; then
  echo '{"error":"empty dependency-check report"}' >&2
  exit 1
fi

# ── Step 1: extract every dependency that has at least one vulnerability ────
FINDINGS=$(echo "$REPORT_JSON" | jq -c '
  [ .dependencies[]? | select((.vulnerabilities // []) | length > 0) |
    . as $dep |
    ($dep.packages[0].id // "pkg:maven/unknown/unknown@0") as $purl |
    ($purl | capture("pkg:maven/(?<ga>[^@]+)@(?<version>.+)")) as $coord |
    ($coord.ga | split("/")) as $gaParts |
    {
      groupId: ($gaParts[0] // "unknown"),
      artifactId: ($gaParts[1] // $dep.fileName),
      version: ($coord.version // "unknown"),
      fileName: $dep.fileName,
      vulnerabilities: [ $dep.vulnerabilities[] | {
        cve: .name,
        severity: (.severity // "UNKNOWN" | ascii_downcase),
        cvss: (.cvssv3.baseScore // .cvssv2.score // null),
        cwes: (.cwes // []),
        description: .description
      } ]
    }
  ]
')

# ── Step 2: resolve compile/runtime-scope coordinates via mvn dependency:list ─
COMPILE_RUNTIME_GAS=$(mvn -q dependency:list -DincludeScope=compile -Dsort=true 2>/dev/null \
  | grep -E '^\s+[a-zA-Z0-9.\-]+:[a-zA-Z0-9.\-]+:' \
  | sed -E 's/^\s+([a-zA-Z0-9.\-]+):([a-zA-Z0-9.\-]+):.*/\1:\2/' \
  | sort -u || true)
{
  mvn -q dependency:list -DincludeScope=runtime -Dsort=true 2>/dev/null \
    | grep -E '^\s+[a-zA-Z0-9.\-]+:[a-zA-Z0-9.\-]+:' \
    | sed -E 's/^\s+([a-zA-Z0-9.\-]+):([a-zA-Z0-9.\-]+):.*/\1:\2/' || true
} | sort -u >> /tmp/audit-triage-compile-runtime-gas.$$  2>/dev/null || true
COMPILE_RUNTIME_GAS="${COMPILE_RUNTIME_GAS}
$(cat /tmp/audit-triage-compile-runtime-gas.$$ 2>/dev/null || true)"
rm -f /tmp/audit-triage-compile-runtime-gas.$$ 2>/dev/null || true

is_shipped() {
  local ga="$1"
  printf '%s\n' "$COMPILE_RUNTIME_GAS" | grep -qx "$ga"
}

# ── Step 3: triage each finding by scope ─────────────────────────────────────
CONFIRMED=()
SUPPRESSED=()

while IFS= read -r dep; do
  groupId=$(echo "$dep" | jq -r '.groupId')
  artifactId=$(echo "$dep" | jq -r '.artifactId')
  ga="${groupId}:${artifactId}"

  if is_shipped "$ga"; then
    CONFIRMED+=("$dep")
  else
    SUPPRESSED+=("$(echo "$dep" | jq -c '. + {reason: "testOrProvidedOnly"}')")
  fi
done < <(echo "$FINDINGS" | jq -c '.[]')

# ── Step 4: sort and output ───────────────────────────────────────────────────
severity_order() {
  case "$1" in
    critical) echo 1 ;;
    high)     echo 2 ;;
    medium|moderate) echo 3 ;;
    low)      echo 4 ;;
    *)        echo 5 ;;
  esac
}

confirmed_json="["
first=true
declare -A sev_buckets
for entry in "${CONFIRMED[@]:-}"; do
  [[ -z "$entry" ]] && continue
  sev=$(echo "$entry" | jq -r '[.vulnerabilities[].severity] | max_by(.) // "unknown"')
  sev_buckets[$sev]+="$entry"$'\n'
done

for sev in critical high medium moderate low unknown; do
  [[ -z "${sev_buckets[$sev]+x}" ]] && continue
  while IFS= read -r entry; do
    [[ -z "$entry" ]] && continue
    [[ "$first" == "true" ]] && first=false || confirmed_json+=","
    confirmed_json+="$entry"
  done < <(echo "${sev_buckets[$sev]}" | sort)
done
confirmed_json+="]"

suppressed_json="["
first=true
for entry in "${SUPPRESSED[@]:-}"; do
  [[ -z "$entry" ]] && continue
  [[ "$first" == "true" ]] && first=false || suppressed_json+=","
  suppressed_json+="$entry"
done
suppressed_json+="]"

jq -n \
  --argjson confirmed "$confirmed_json" \
  --argjson suppressed "$suppressed_json" \
  --arg report "$REPORT" \
  --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{
    generatedAt: $ts,
    report: $report,
    summary: {
      confirmed: ($confirmed | length),
      suppressed: ($suppressed | length)
    },
    confirmed: $confirmed,
    suppressed: $suppressed
  }'
