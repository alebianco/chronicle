#!/usr/bin/env bash
# One-time local setup for a fresh clone or a new worktree.
#
# Everything here is *local* state git cannot carry: the pre-commit hook and the
# RTK filter trust. Both fail silently when missing — a fresh clone commits with
# no ktlint gate and pays full token price on adb output, with nothing to say so.
#
#   ./setup-repo.sh          # install
#   ./setup-repo.sh --check  # report only, non-zero if something is missing
set -uo pipefail
cd "$(dirname "$0")"

check_only=0
[ "${1:-}" = "--check" ] && check_only=1
missing=0

say()  { printf '%s\n' "$*"; }
need() { missing=$((missing + 1)); }

# --- 1. the pre-commit hook ------------------------------------------------
# `pre-commit` is tracked at the repo root, but git never installs a tracked
# file as a hook. It has to be copied (or symlinked) into the hooks dir, which
# for a worktree is the COMMON git dir, not .git.
hooks_dir="$(git rev-parse --git-common-dir 2>/dev/null)/hooks"
target="$hooks_dir/pre-commit"

if [ ! -f pre-commit ]; then
  say "!! pre-commit is missing from the repo root."
  need
elif [ -x "$target" ] && command diff -q pre-commit "$target" >/dev/null 2>&1; then
  say "ok  pre-commit hook installed and current"
else
  if [ "$check_only" = 1 ]; then
    if [ -e "$target" ]; then say "!! pre-commit hook is STALE (differs from ./pre-commit)"
    else say "!! pre-commit hook NOT installed — commits skip the ktlint gate"; fi
    need
  else
    command mkdir -p "$hooks_dir"
    command cp -f pre-commit "$target" && chmod +x "$target"
    say "ok  pre-commit hook installed -> $target"
  fi
fi

# --- 2. RTK filter trust ---------------------------------------------------
# Purely a token optimisation; absence is not an error.
if ! command -v rtk >/dev/null 2>&1; then
  say "--  rtk not installed (optional; skipping filter trust)"
elif [ ! -f .rtk/filters.toml ]; then
  say "--  no .rtk/filters.toml (skipping)"
elif rtk trust --list 2>/dev/null | command grep -q "$(pwd)/.rtk/filters.toml"; then
  say "ok  rtk filters trusted"
else
  if [ "$check_only" = 1 ]; then
    say "!! rtk filters NOT trusted — adb output is unfiltered. Run: rtk trust -y"
    need
  else
    rtk trust -y >/dev/null 2>&1 && say "ok  rtk filters trusted" \
      || { say "!! rtk trust failed"; need; }
  fi
fi

# --- 3. local.properties ---------------------------------------------------
# Gitignored, so a new WORKTREE has none and every Gradle task fails with
# "SDK location not found" — which reads like a broken build, not missing setup.
if [ -f local.properties ]; then
  say "ok  local.properties present"
else
  main_root=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null | sed 's|/\.git$||')
  if [ -f "$main_root/local.properties" ] && [ "$check_only" = 0 ]; then
    command cp "$main_root/local.properties" local.properties
    say "ok  local.properties copied from $main_root"
  elif [ -n "${ANDROID_HOME:-}" ] && [ "$check_only" = 0 ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
    say "ok  local.properties written from \$ANDROID_HOME"
  else
    say "!! local.properties missing — Gradle will fail with 'SDK location not found'"
    say "   Write: sdk.dir=\$ANDROID_HOME"
    need
  fi
fi

say ""
if [ "$missing" -gt 0 ]; then
  [ "$check_only" = 1 ] && say "$missing item(s) need attention — run ./setup-repo.sh to fix."
  exit 1
fi
say "Repo setup complete."
