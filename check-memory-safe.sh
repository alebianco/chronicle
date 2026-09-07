#!/usr/bin/env bash
# Refuses to let anything sensitive into backlog/memory/, which is committed.
#
# Repo-side memories are shared: they reach CI, a fresh clone, and anyone the
# repo is ever handed to. The whole reason auto-memory lives outside the repo is
# that it is written automatically and nobody reviews it — so moving it in needs
# a gate, not an intention.
#
# The audit that motivated the split: of 24 auto-memories, 22 were repo-safe and
# ONE was not — it carried the tablet's LAN address, the Plex server's name, and
# the owner's personal phone serial. That one stays local. This script is what
# keeps the next one like it from being committed by accident.
#
#   ./check-memory-safe.sh            # scan backlog/memory/
#   ./check-memory-safe.sh <file>...  # scan specific files
#
# Exit 1 on a finding. Deliberately conservative: a false positive costs a
# rewording, a false negative publishes a household's private address.
set -uo pipefail
cd "$(dirname "$0")"

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  [ -d backlog/memory ] || { echo "no backlog/memory/ — nothing to check"; exit 0; }
  while IFS= read -r f; do targets+=("$f"); done < <(find backlog/memory -name '*.md')
fi
[ ${#targets[@]} -eq 0 ] && { echo "no memory files to check"; exit 0; }

findings=0
report() { printf '  %s:%s\n    %s\n' "$1" "$2" "$3"; findings=$((findings + 1)); }

# Each rule is "pattern|explanation". Keep them narrow enough to be actionable.
scan() {
  local file="$1" label="$2" pattern="$3" why="$4"
  while IFS=: read -r ln text; do
    [ -z "$ln" ] && continue
    report "$file:$ln" "$label" "$why -> $(printf '%s' "$text" | sed 's/^[[:space:]]*//' | cut -c1-90)"
  done < <(command grep -noE "$pattern" "$file" 2>/dev/null | head -3)
}

for f in "${targets[@]}"; do
  [ -f "$f" ] || continue

  # Private network addresses — a household's LAN layout.
  scan "$f" "private-ip" \
    '\b(192\.168\.[0-9]{1,3}\.[0-9]{1,3}|10\.([0-9]{1,3}\.){2}[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.([0-9]{1,3})\.[0-9]{1,3})\b' \
    "private LAN address"

  # Device serials (adb): 8+ upper-alnum containing BOTH a digit and a letter.
  # Two stages, because a single regex here either misses real serials
  # (HVA067JE, RFCTA0ZQ74X) or flags ordinary shouted words. The digit+letter
  # requirement is what excludes CORRECTED / SUCCESSFUL / ANDROID.
  while IFS=: read -r ln text; do
    [ -z "$ln" ] && continue
    report "$f:$ln" "device-serial" "looks like a device serial -> $text"
  done < <(command grep -noE '\b[A-Z0-9]{8,}\b' "$f" 2>/dev/null \
             | command grep -E ':[A-Z0-9]*[0-9]' \
             | command grep -E ':[A-Z0-9]*[A-Z]' | head -3)

  # Absolute home paths leak a username.
  scan "$f" "home-path" \
    '/(Users|home)/[a-zA-Z0-9._-]+' \
    "absolute home path (use ~ instead)"

  # Credentials of any shape.
  scan "$f" "credential" \
    '(X-Plex-Token|accountAuthToken|[Aa]uth[Tt]oken[[:space:]]*[=:][[:space:]]*[A-Za-z0-9_-]{8,}|[Pp]assword[[:space:]]*[=:]|[Ss]ecret[[:space:]]*[=:]|[Aa]pi[_-]?[Kk]ey[[:space:]]*[=:]|Bearer[[:space:]]+[A-Za-z0-9._-]{10,})' \
    "credential-shaped"

  # Plex server hostnames embed the server hash.
  scan "$f" "plex-host" \
    '[a-z0-9-]+\.[a-z0-9]+\.plex\.direct' \
    "Plex direct hostname (contains server hash)"

  # Email addresses.
  scan "$f" "email" \
    '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' \
    "email address"
done

echo ""
if [ "$findings" -gt 0 ]; then
  cat <<'MSG'
FAIL: sensitive content found in committed memory.

backlog/memory/ is committed and shared. Anything machine-specific, private, or
credential-shaped belongs in local auto-memory instead
(~/.claude/projects/<project>/memory/), which is per-machine and never committed.

Fix by either generalising the fact (say "the tablet" rather than its address) or
moving that memory back to local auto-memory. See
backlog/docs/reference/12-agent-memory.md.
MSG
  exit 1
fi
echo "OK: ${#targets[@]} memory file(s) carry no sensitive content."
