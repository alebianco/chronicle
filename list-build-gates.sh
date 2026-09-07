#!/usr/bin/env bash
# Lists the structural build gates — tests that scan the source tree and fail the
# build on a forbidden pattern, rather than testing behaviour.
#
# The rule text is the first KDoc line of each test, so this table cannot drift
# from what is actually enforced: change the guard, change its KDoc, re-run this.
#
#   ./list-build-gates.sh                 # markdown table
#   ./list-build-gates.sh --check         # fail if any gate lacks a KDoc summary
#
# Regenerates the table in backlog/docs/reference/09-enforced-rules.md.
set -uo pipefail
cd "$(dirname "$0")"

check_mode=0
[ "${1:-}" = "--check" ] && check_mode=1
missing=0

# A structural gate reads the source tree. Behaviour tests do not.
is_gate() {
  grep -qE 'File\("app/src|walkTopDown|\.walk\(\)|sourceFiles|kotlinSources' "$1" 2>/dev/null
}

while IFS= read -r f; do
  is_gate "$f" || continue
  name=$(basename "$f" .kt)
  rule=$(sed -n '1,12p' "$f" \
    | grep -E '^[[:space:]]?\*' \
    | sed 's/^[[:space:]]*\*[[:space:]]*//' \
    | grep -v '^$' \
    | head -1)
  if [ -z "$rule" ]; then
    missing=$((missing + 1))
    rule='**(no KDoc summary — add one)**'
  fi
  printf '| `%s` | %s |\n' "$name" "$rule"
done < <(find app/src/test -name "*Test.kt" | sort)

if [ "$check_mode" = 1 ] && [ "$missing" -gt 0 ]; then
  echo "FAIL: $missing structural gate(s) have no KDoc summary line." >&2
  exit 1
fi
