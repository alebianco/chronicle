#!/usr/bin/env bash
# SessionStart hook: keep memory honest, across both stores.
#
# Memory lives in two places on purpose:
#
#   backlog/memory/            committed, shared, survives a new machine.
#                              Gated by ./check-memory-safe.sh — no LAN addresses,
#                              serials, home paths, credentials, hosts or emails.
#   ~/.claude/projects/.../    local auto-memory: per-machine, private, curatable
#                              with /memory. Household and machine facts stay here.
#
# This hook does NOT write memories. The case against an automatic writer is
# concrete: `chronicle-coverage-gate-accumulates` asserted in confident prose that
# within-tolerance coverage dips accumulate. They do not —
# compare-package-coverage.py keeps max(baseline, current) and carries a self-test
# saying so, which runs on every ratchet invocation. The memory paired a real
# symptom (a number that would not reproduce — actually a stale UP-TO-DATE JaCoCo
# report) with the wrong mechanism, and cu-204 was filed on that false premise
# after cu-135 had already been filed and closed for the same wrong reason.
#
# An auto-writer would have produced it: surprising observation, confident
# diagnosis, concrete file. Catching it needed the gate's source read and its
# self-test run. Writing a memory is a judgement, not a summary.
#
# It never parses the session transcript — that format is internal to Claude Code
# and documented as changing between versions.
#
# Output is capped: SessionStart stdout lands in every session's context, and an
# unbounded reminder is exactly the bloat it exists to prevent.

set -uo pipefail

LOCAL="$HOME/.claude/projects/-Users-abianco-Workspace-personal-android-chronicle/memory"
REPO="${CLAUDE_PROJECT_DIR:-$PWD}/backlog/memory"
MAX_LINES=16
out=""
emit() { out="${out}$1
"; }

# --- memories flagged as corrected --------------------------------------------
# A memory whose description says CORRECTED/superseded/unverified has already
# slipped once. Naming it keeps the correction from being re-forgotten.
# Dedupe by basename: a memory promoted to the repo may still have a local copy,
# and naming it twice is noise.
flagged=$(command grep -l -iE '^description:.*(CORRECTED|superseded|unverified|was wrong)' \
  "$REPO"/*.md "$LOCAL"/*.md 2>/dev/null \
  | while IFS= read -r f; do basename "$f" .md; done | sort -u | head -3)
if [ -n "$flagged" ]; then
  emit "Corrected memories — trust the correction, not the original claim:"
  while IFS= read -r b; do
    [ -z "$b" ] && continue
    emit "  - $b"
  done <<< "$flagged"
fi

# --- repo-side safety ---------------------------------------------------------
# The guard normally runs before commit; if something sensitive is sitting in the
# working tree, say so now rather than at commit time.
if [ -d "$REPO" ] && [ -x "${CLAUDE_PROJECT_DIR:-$PWD}/check-memory-safe.sh" ]; then
  if ! "${CLAUDE_PROJECT_DIR:-$PWD}/check-memory-safe.sh" >/dev/null 2>&1; then
    emit "backlog/memory/ has content ./check-memory-safe.sh rejects — do not commit it yet."
  fi
fi

# --- index drift, both stores -------------------------------------------------
drift_check() {
  local dir="$1" index="$2" label="$3"
  [ -f "$index" ] || return 0
  local orphans=0 dangling=0 b
  for f in "$dir"/*.md; do
    b=$(basename "$f")
    [ "$b" = "$(basename "$index")" ] && continue
    command grep -q "($b)" "$index" || orphans=$((orphans + 1))
  done
  [ "$orphans" -gt 0 ] && emit "$label: $orphans file(s) missing from its index — they will not be found."
  while IFS= read -r ref; do
    [ -f "$dir/$ref" ] || dangling=$((dangling + 1))
  done < <(command grep -oE '\(([a-z0-9-]+\.md)\)' "$index" 2>/dev/null | tr -d '()')
  [ "$dangling" -gt 0 ] && emit "$label: $dangling index line(s) point at a missing file."
  return 0
}
drift_check "$REPO"  "$REPO/README.md"  "backlog/memory"
drift_check "$LOCAL" "$LOCAL/MEMORY.md" "local auto-memory"

# --- the standing rule --------------------------------------------------------
emit "Memory states what was true when written. Before acting on one that names a file, flag"
emit "or mechanism, verify it still holds — read the code, not the symptom."

[ -n "$out" ] && printf '%s' "$out" | head -"$MAX_LINES"
exit 0
