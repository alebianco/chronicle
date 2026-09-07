#!/usr/bin/env bash
# PostToolUse hook: run ktlintFormat after a Kotlin file is written or edited.
#
# Why this exists. ktlint is stage 1 of verify.sh and it also gates `git commit`
# via the repo's pre-commit hook, so a style violation is always caught —
# eventually. The cost is *when*: it surfaces minutes later at commit or verify
# time, forcing a re-read and re-edit of a file the agent had already finished.
# The session history shows the workaround this bred — commands prefixed with
# `./gradlew ktlintFormat -q 2>&1|tail -1;` to pre-empt it — and 214 standalone
# ktlintFormat calls.
#
# Formatting at the moment of the edit removes the class entirely.
#
# Cost: ~1s against a warm Gradle daemon (measured), ~7s cold. It runs only for
# .kt/.kts writes, so a docs- or backlog-only session never pays it.
#
# This hook NEVER blocks (always exits 0): formatting is a convenience, and a
# ktlint failure the formatter cannot fix must surface at the verify gate with
# its full report, not as a truncated hook error.

set -uo pipefail

input=$(cat)

path=$(printf '%s' "$input" | /usr/bin/python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
    ti = d.get("tool_input", {}) or {}
    print(ti.get("file_path") or ti.get("notebook_path") or "")
except Exception:
    print("")
' 2>/dev/null)

case "$path" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac

# Locate the enclosing gradle project (the file may sit in a worktree).
dir=$(dirname "$path")
root=""
while [ "$dir" != "/" ] && [ -n "$dir" ]; do
  if [ -x "$dir/gradlew" ]; then root="$dir"; break; fi
  dir=$(dirname "$dir")
done
[ -z "$root" ] && exit 0

# --offline keeps a network hiccup from stalling an edit; ktlint needs nothing
# from the network once the daemon is warm. Failure is silent by design.
if ! out=$(cd "$root" && timeout 90 ./gradlew ktlintFormat -q --offline 2>&1); then
  # Only speak up if ktlint reported violations it could not auto-fix — that is
  # actionable now. Anything else (daemon start, offline miss) stays quiet.
  if printf '%s' "$out" | command grep -q "Lint error"; then
    printf 'ktlintFormat could not auto-fix everything in %s:\n' "$(basename "$path")" >&2
    printf '%s' "$out" | command grep "Lint error" | head -5 >&2
  fi
fi

exit 0
