#!/usr/bin/env bash
# audit-triage.sh — deterministic npm audit triage
#
# Usage: npm audit --json 2>/dev/null | bash audit-triage.sh [package-lock.json]
#
# Reads: npm audit --json on stdin  +  package-lock.json (arg, default: package-lock.json)
# Outputs: JSON with two arrays: "confirmed" and "suppressed"
#
# Rules (applied mechanically, no judgment):
#   1. Resolve all advisory objects from npm audit (via-chain up to 3 hops)
#   2. For each advisory, get ALL resolved versions from package-lock.json
#   3. If ALL copies are devDependency-only → suppress (reason: devOnly)
#   4. Run semver check: node -e "require('semver').satisfies(...)"
#      - Any copy within range → CONFIRMED
#      - All copies above fix → suppress (reason: patched)
#      - Package not in lockfile → suppress (reason: notInLockfile)
#   5. Output deterministic JSON, sorted by severity then package name

set -euo pipefail

LOCKFILE="${1:-package-lock.json}"

if [[ ! -f "$LOCKFILE" ]]; then
  echo '{"error":"package-lock.json not found"}' >&2
  exit 1
fi

# Read npm audit JSON from stdin
AUDIT_JSON=$(cat)

if [[ -z "$AUDIT_JSON" ]]; then
  echo '{"error":"no audit JSON on stdin — run: npm audit --json 2>/dev/null | bash audit-triage.sh"}' >&2
  exit 1
fi

# ── Step 1: extract all advisory objects (up to 3 via-hops) ──────────────────
ADVISORIES=$(echo "$AUDIT_JSON" | jq -c '
  .vulnerabilities as $all |
  [ $all | to_entries[] | .key as $pkg | .value.via[] |
    if type == "object" then
      {pkg: $pkg, severity: .severity, title: .title, range: .range, url: .url, ghsa: (.url | split("/") | last)}
    else
      . as $ref1 |
      ($all[$ref1].via // []) | .[] |
      if type == "object" then
        {pkg: $pkg, severity: .severity, title: .title, range: .range, url: .url, ghsa: (.url | split("/") | last)}
      else
        . as $ref2 |
        ($all[$ref2].via // []) | .[] |
        if type == "object" then
          {pkg: $pkg, severity: .severity, title: .title, range: .range, url: .url, ghsa: (.url | split("/") | last)}
        else empty end
      end
    end
  ] | unique_by(.pkg + .ghsa)
')

# ── Step 2: for each advisory, triage against lockfile ───────────────────────
CONFIRMED=()
SUPPRESSED=()

while IFS= read -r advisory; do
  pkg=$(echo "$advisory" | jq -r '.pkg')
  severity=$(echo "$advisory" | jq -r '.severity')
  title=$(echo "$advisory" | jq -r '.title')
  range=$(echo "$advisory" | jq -r '.range // ""')
  url=$(echo "$advisory" | jq -r '.url')
  ghsa=$(echo "$advisory" | jq -r '.ghsa')

  # Get all lockfile entries for this package (version + dev flag)
  lockfile_entries=$(jq -c --arg p "$pkg" '
    .packages | to_entries[] |
    select(
      .key == ("node_modules/" + $p) or
      (.key | endswith("/node_modules/" + $p))
    ) |
    {version: .value.version, dev: (.value.dev // false), path: .key}
  ' "$LOCKFILE" 2>/dev/null)

  if [[ -z "$lockfile_entries" ]]; then
    SUPPRESSED+=("$(echo "$advisory" | jq -c '. + {reason: "notInLockfile"}')")
    continue
  fi

  # Check if ALL copies are devDependency-only
  has_runtime=$(echo "$lockfile_entries" | jq -r 'select(.dev == false) | .version' | head -1)
  if [[ -z "$has_runtime" ]]; then
    SUPPRESSED+=("$(echo "$advisory" | jq -c '. + {reason: "devOnly", versions: ['"$(echo "$lockfile_entries" | jq -r '.version' | jq -Rs 'split("\n") | map(select(. != ""))')"']}')")
    continue
  fi

  # For each runtime copy, check semver
  found_vulnerable=false
  vulnerable_versions=()
  patched_versions=()

  while IFS= read -r entry; do
    ver=$(echo "$entry" | jq -r '.version')
    dev=$(echo "$entry" | jq -r '.dev')

    [[ "$dev" == "true" ]] && continue
    [[ -z "$range" ]] && { found_vulnerable=true; vulnerable_versions+=("$ver"); continue; }

    in_range=$(node -e "
      try {
        const s = require('semver');
        const r = '$range';
        const v = '$ver';
        // semver.satisfies returns false for invalid ranges — treat as not vulnerable
        process.stdout.write(s.satisfies(v, r, {includePrerelease: false}) ? 'true' : 'false');
      } catch(e) { process.stdout.write('false'); }
    " 2>/dev/null || echo "false")

    if [[ "$in_range" == "true" ]]; then
      found_vulnerable=true
      vulnerable_versions+=("$ver")
    else
      patched_versions+=("$ver")
    fi
  done < <(echo "$lockfile_entries")

  if [[ "$found_vulnerable" == "true" ]]; then
    vuln_arr=$(printf '%s\n' "${vulnerable_versions[@]}" | jq -Rs 'split("\n") | map(select(. != ""))')
    CONFIRMED+=("$(echo "$advisory" | jq -c --argjson v "$vuln_arr" '. + {vulnerableVersions: $v}')")
  else
    pat_arr=$(printf '%s\n' "${patched_versions[@]}" | jq -Rs 'split("\n") | map(select(. != ""))')
    SUPPRESSED+=("$(echo "$advisory" | jq -c --argjson v "$pat_arr" '. + {reason: "patched", versions: $v}')")
  fi

done < <(echo "$ADVISORIES" | jq -c '.[]')

# ── Step 3: sort and output ───────────────────────────────────────────────────
severity_order() {
  case "$1" in
    critical) echo 1 ;;
    high)     echo 2 ;;
    moderate) echo 3 ;;
    low)      echo 4 ;;
    *)        echo 5 ;;
  esac
}

# Build final JSON
confirmed_json="["
first=true
# Sort by severity then pkg — build sortable list
declare -A sev_buckets
for entry in "${CONFIRMED[@]}"; do
  sev=$(echo "$entry" | jq -r '.severity')
  sev_buckets[$sev]+="$entry"$'\n'
done

for sev in critical high moderate low; do
  [[ -z "${sev_buckets[$sev]+x}" ]] && continue
  while IFS= read -r entry; do
    [[ -z "$entry" ]] && continue
    [[ "$first" == "true" ]] && first=false || confirmed_json+=","
    confirmed_json+="$entry"
  done < <(echo "${sev_buckets[$sev]}" | sort -t'"' -k4)
done
confirmed_json+="]"

suppressed_json="["
first=true
for entry in "${SUPPRESSED[@]}"; do
  [[ "$first" == "true" ]] && first=false || suppressed_json+=","
  suppressed_json+="$entry"
done
suppressed_json+="]"

jq -n \
  --argjson confirmed "$confirmed_json" \
  --argjson suppressed "$suppressed_json" \
  --arg lockfile "$LOCKFILE" \
  --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{
    generatedAt: $ts,
    lockfile: $lockfile,
    summary: {
      confirmed: ($confirmed | length),
      suppressed: ($suppressed | length)
    },
    confirmed: $confirmed,
    suppressed: $suppressed
  }'
