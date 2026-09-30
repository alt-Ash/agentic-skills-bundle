#!/usr/bin/env bash
#
# check-java-compat.sh <target-jdk-version>
#
# Resolves the current Maven project's direct dependencies and reports which
# ones were compiled for a JDK newer than the target version — meaning they
# will NOT run on the target JDK, since JVMs reject class files with a
# bytecode major version newer than their own.
#
# Unlike npm's engines.node (a self-declared, machine-readable field in
# package.json), Java artifacts do not self-declare a minimum JDK version in
# a standardized way. The only reliable, artifact-level signal is the actual
# compiled class-file bytecode major version embedded in each .class file —
# this script reads that directly rather than trusting dependency metadata.
#
# Class-file major-version -> JDK version mapping (major = 44 + JDK version,
# valid for JDK 1.1 through the current major-version numbering scheme):
#   52=8  53=9  54=10 55=11 56=12 57=13 58=14 59=15 60=16
#   61=17 62=18 63=19 64=20 65=21 66=22 67=23 68=24 69=25
#
# Exit codes:
#   0 — all resolved dependencies compatible with the target JDK
#   1 — one or more incompatible dependencies found
#   2 — usage error / Maven not available / no pom.xml found

set -euo pipefail

TARGET="${1:-}"

if [[ -z "$TARGET" ]]; then
  echo "Usage: check-java-compat.sh <target-jdk-version>" >&2
  echo "Example: check-java-compat.sh 21" >&2
  exit 2
fi

if [[ ! -f "pom.xml" ]]; then
  echo "Error: no pom.xml found in $(pwd)" >&2
  echo "(Gradle-project support is not yet implemented in this script — run the equivalent check manually for Gradle projects.)" >&2
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "Error: mvn not found on PATH" >&2
  exit 2
fi

TARGET_MAJOR=$((44 + TARGET))

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo
echo "Java compatibility check — target: JDK ${TARGET} (class-file major version ${TARGET_MAJOR})"
echo "════════════════════════════════════════════════════════════"

# Resolve the project's dependency jars into a flat local directory so we can
# inspect their class files directly.
mvn -q dependency:copy-dependencies -DoutputDirectory="$WORKDIR" -DincludeScope=compile 2>/dev/null || {
  echo "Error: failed to resolve dependencies via 'mvn dependency:copy-dependencies'" >&2
  exit 2
}

compatible=()
incompatible=()
unreadable=()

for jar in "$WORKDIR"/*.jar; do
  [[ -e "$jar" ]] || continue
  name="$(basename "$jar")"

  # Find the first non-module-info .class file in the jar and read its
  # bytecode major version (bytes 6-7 of the class file, big-endian, right
  # after the 4-byte 0xCAFEBABE magic number and the 2-byte minor version).
  class_entry="$(unzip -Z1 "$jar" 2>/dev/null | grep -E '\.class$' | grep -v 'module-info\.class$' | head -1 || true)"
  if [[ -z "$class_entry" ]]; then
    unreadable+=("$name (no readable .class entries)")
    continue
  fi

  major_hex="$(unzip -p "$jar" "$class_entry" 2>/dev/null | od -An -t u1 -j 6 -N 2 | tr -d ' \n')"
  if [[ -z "$major_hex" ]]; then
    unreadable+=("$name (could not read class-file header)")
    continue
  fi

  # od -t u1 with -j 6 -N 2 prints two space-separated decimal bytes; recombine.
  read -r b1 b2 <<< "$(unzip -p "$jar" "$class_entry" 2>/dev/null | od -An -t u1 -j 6 -N 2)"
  major=$(( (b1 << 8) + b2 ))

  if (( major <= TARGET_MAJOR )); then
    compatible+=("$name (class-file major $major)")
  else
    incompatible+=("$name (class-file major $major — requires a newer JDK than $TARGET)")
  fi
done

if [[ ${#incompatible[@]} -gt 0 ]]; then
  echo
  echo "🚨 INCOMPATIBLE (${#incompatible[@]})"
  for entry in "${incompatible[@]}"; do echo "   $entry"; done
fi

if [[ ${#unreadable[@]} -gt 0 ]]; then
  echo
  echo "⚪ COULD NOT DETERMINE — verify manually (${#unreadable[@]})"
  for entry in "${unreadable[@]}"; do echo "   $entry"; done
fi

if [[ ${#compatible[@]} -gt 0 ]]; then
  echo
  echo "✅ COMPATIBLE (${#compatible[@]})"
  for entry in "${compatible[@]}"; do echo "   $entry"; done
fi

echo

if [[ ${#incompatible[@]} -gt 0 ]]; then
  exit 1
fi
exit 0
