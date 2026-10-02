#!/usr/bin/env bash
# Fails if any module pom's <revision> differs from the root pom's <revision>.
# Modules with their own parent (mcp/*, cli, hooks, evals) cannot inherit it.
set -euo pipefail
cd "$(dirname "$0")/.."
rev() { sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$1" | head -n1; }
root="$(rev pom.xml)"
[ -n "$root" ] || { echo "::error::no <revision> in root pom.xml"; exit 1; }
status=0
while IFS= read -r pom; do
  v="$(rev "$pom")"
  [ -n "$v" ] || continue
  if [ "$v" != "$root" ]; then
    echo "::error file=$pom::revision $v != root revision $root"
    status=1
  fi
done < <(find . -name pom.xml -not -path '*/target/*' -not -path './pom.xml' -not -path './.claude/*' -not -path './.worktrees/*')
[ "$status" -eq 0 ] && echo "All module revisions match root ($root)"
exit "$status"
