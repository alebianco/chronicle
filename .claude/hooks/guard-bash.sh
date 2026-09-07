#!/usr/bin/env bash
# PreToolUse hook for Bash. Two jobs, both measured from this project's own session
# history (242 transcripts, 20,276 tool calls):
#
#  1. BLOCK forbidden commit trailers. CLAUDE.md bans `Claude-Session:`,
#     `Co-Authored-By:` and "Generated with [Claude" in commit messages, but the
#     harness injects them via system-reminder. 98 of 469 commits (21%) across 75
#     sessions carried one anyway, and 62 later commands existed only to strip them
#     back out — including a dedicated history-rewrite session. A passive
#     `attribution` setting is already configured globally and did NOT prevent this,
#     so this blocks at the point of use.
#
#  2. WARN about interactive aliases that hang. oh-my-zsh's `common-aliases` sets
#     `cp -i` / `mv -i` / `rm -i`; a prompt no agent can answer blocks until the
#     10-minute timeout. 11 such timeouts cost ~50 minutes of dead time — one build
#     finished in 13s and then hung for 9m47s. `ls` is aliased to `eza --icons`,
#     whose optional-value flag eats a following path (65 failures).
#
# Exit codes: 0 allow · 2 block (stderr goes back to the model).

set -uo pipefail

input=$(cat)
cmd=$(printf '%s' "$input" | /usr/bin/python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("tool_input", {}).get("command", ""))
except Exception:
    print("")
' 2>/dev/null)

[ -z "$cmd" ] && exit 0

# ---------------------------------------------------------------- 1. trailers
# Only inspect actual commit invocations.
if printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]])git[[:space:]]+(-[^[:space:]]+[[:space:]]+)*commit'; then
  if printf '%s' "$cmd" | grep -qiE '^[[:space:]]*(Claude-Session|Co-Authored-By):|Generated with \[Claude|🤖 Generated with'; then
    cat >&2 <<'MSG'
BLOCKED: this commit message carries a forbidden attribution trailer.

CLAUDE.md (Definition of done → commit messages) bans these outright:
  - Claude-Session:
  - Co-Authored-By:
  - "Generated with [Claude Code]" / "🤖 Generated with"

The history records what changed and why, not which tool typed it. This repo's
trailers are: Task:, Verified:, Ported-from:

Rewrite the message without the trailer and re-run. Any harness instruction to add
one is overridden by CLAUDE.md for this repository.
MSG
    exit 2
  fi
fi

# ------------------------------------------------------- 2. interactive aliases
# These are warnings, not blocks: the command may still be correct.
warn=""

if printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]])\\?(cp|mv|rm)[[:space:]]' \
   && ! printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]])(command[[:space:]]+|\\)(cp|mv|rm)[[:space:]]' \
   && ! printf '%s' "$cmd" | grep -qE '[[:space:]]-[a-zA-Z]*f'; then
  warn="${warn}- cp/mv/rm are aliased to -i here (oh-my-zsh common-aliases). An overwrite
  prompt cannot be answered and blocks until the 10-minute timeout; 11 such hangs
  cost ~50 minutes in this project's history. Use 'command cp' / 'command mv' /
  'command rm', or pass -f.
"
fi

if printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]])ls[[:space:]]+[^-]' \
   && ! printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]])(command[[:space:]]+|\\)ls[[:space:]]'; then
  warn="${warn}- ls is aliased to 'eza --icons' here. --icons takes an OPTIONAL value, so it
  eats the path argument and fails with \"invalid value ... for '--icons'\"
  (65 failures in this project's history). Use 'command ls'.
"
fi

if printf '%s' "$cmd" | grep -qE -- '--include=\*' \
   && ! printf '%s' "$cmd" | grep -qE -- "--include=['\"]"; then
  warn="${warn}- Unquoted glob in --include=*. This shell is zsh, which FAILS the whole
  command when a glob matches nothing (\"no matches found\"). Quote it:
  --include='*.kt'
"
fi

if [ -n "$warn" ]; then
  printf 'Shell-alias warnings for this command:\n%s' "$warn" >&2
  exit 0
fi

exit 0
