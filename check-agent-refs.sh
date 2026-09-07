#!/usr/bin/env bash
# Every agentType named in a workflow must resolve to a real agent, and every
# agent/command file must be well-formed.
#
# A bad agentType fails at RUN time, deep inside a workflow that has already
# spent tokens on earlier stages — and the failure text ("agent type not found")
# names the type, not the file that referenced it. Cheap to check here.
set -uo pipefail
cd "$(dirname "$0")"
fail=0

# 1. agentType references resolve
if [ -d .claude/workflows ]; then
  while IFS= read -r a; do
    [ -z "$a" ] && continue
    if [ -f ".claude/agents/$a.md" ]; then
      echo "ok   agentType '$a' resolves"
    else
      echo "FAIL agentType '$a' has no .claude/agents/$a.md"; fail=1
    fi
  done < <(command grep -rhoE "agentType: '[a-zA-Z0-9_-]+'" .claude/workflows/ 2>/dev/null \
             | sed "s/.*'\(.*\)'/\1/" | sort -u)
fi

# 2. agent frontmatter: name must equal filename, description required
for f in .claude/agents/*.md; do
  [ -f "$f" ] || continue
  b=$(basename "$f" .md)
  n=$(command grep -m1 '^name:' "$f" | sed 's/name: *//' | tr -d '\r')
  d=$(command grep -m1 '^description:' "$f" | sed 's/description: *//')
  [ "$n" = "$b" ] || { echo "FAIL $b: frontmatter name is '$n'"; fail=1; }
  [ -n "$d" ]     || { echo "FAIL $b: no description (it is what triggers the agent)"; fail=1; }
  [ "${#d}" -le 1536 ] || { echo "FAIL $b: description exceeds 1536 chars"; fail=1; }
done

# 3. command frontmatter: description required
for f in .claude/commands/*.md; do
  [ -f "$f" ] || continue
  command grep -q '^description:' "$f" || {
    echo "FAIL $(basename "$f" .md): command has no description"; fail=1; }
done

[ "$fail" -eq 0 ] && echo "OK: agent and command definitions are consistent."
exit "$fail"
