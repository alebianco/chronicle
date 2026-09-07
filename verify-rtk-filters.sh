#!/usr/bin/env bash
# Proves the project-local RTK filters in .rtk/filters.toml actually filter.
#
# A filter that silently stops matching looks exactly like a filter that is
# working — the command still succeeds, you just pay full token price. That is
# how the first [filters.gradlew] attempt went unnoticed: RTK has a BUILT-IN
# gradlew handler which takes precedence, so the custom one was inert (45 -> 44
# lines) while looking plausible.
#
# So this asserts on the numbers, and fails loudly.
#
#   ./verify-rtk-filters.sh
set -uo pipefail
cd "$(dirname "$0")"

fail=0
say() { printf '%s\n' "$*"; }

if ! command -v rtk >/dev/null 2>&1; then
  say "SKIP: rtk not installed — filters are an optimisation, not a requirement."
  exit 0
fi

if ! rtk trust --list 2>/dev/null | command grep -q "$(pwd)/.rtk/filters.toml"; then
  say "WARN: .rtk/filters.toml is not trusted here. Run: rtk trust -y"
  say "      (RTK re-prompts whenever the file's sha256 changes.)"
  fail=1
fi

# --- adb logcat -----------------------------------------------------------
# Drive a stub 'adb' so the test needs no device and is deterministic.
tmp=$(mktemp -d)
trap 'command rm -rf "$tmp"' EXIT

command cat > "$tmp/logcat.txt" <<'EOF'
--------- beginning of main
09-06 19:22:01.111  1234  1234 I ActivityManager: Start proc 9876:io.github.mattpvaughn.chronicle.debug/u0a123
09-06 19:22:01.222  9876  9876 D nativeloader: Configuring clns-6 for other apk
09-06 19:22:01.223  9876  9876 V GraphicsEnvironment: ANGLE Developer option set to: 'default'
09-06 19:22:01.224  9876  9876 D CompatibilityChangeReporter: Compat change id reported: 171979766
09-06 19:22:01.225  9876  9876 I ForceDarkHelperStubImpl: initialize
09-06 19:22:01.300  9876  9876 I Chronicle: MockPlexServer started on 127.0.0.1:41234
09-06 19:22:01.410  9876  9901 D OpenGLRenderer: Skia GL Pipeline
09-06 19:22:01.500  9876  9876 W Timber: Book 12345 resolved 0 chapters
09-06 19:22:01.600  9876  9876 E Chronicle: Failed to fetch album 12345
09-06 19:22:01.700  9876  9876 D BufferQueueProducer: queueBuffer time 12ms
09-06 19:22:01.701  9876  9876 I chatty  : uid=10123 identical 14 lines
09-06 19:22:01.800  9876  9876 V MediaSessionCompat: setPlaybackState PLAYING
EOF

command mkdir -p "$tmp/bin"
printf '#!/bin/sh\ncommand cat %s/logcat.txt\n' "$tmp" > "$tmp/bin/adb"
chmod +x "$tmp/bin/adb"

raw=$(PATH="$tmp/bin:$PATH" adb -s 1.2.3.4:5555 logcat -d 2>/dev/null | command grep -c .)
fil=$(PATH="$tmp/bin:$PATH" rtk adb -s 1.2.3.4:5555 logcat -d 2>/dev/null | command grep -c .)

say "adb logcat: $raw lines raw -> $fil lines filtered"

if [ "$fil" -ge "$raw" ]; then
  say "FAIL: the adb-logcat filter had no effect. Either the match_command regex"
  say "      stopped matching, or the file is untrusted. Check: rtk trust --list"
  fail=1
fi

# The signal must survive: app logs and every warning/error line.
kept=$(PATH="$tmp/bin:$PATH" rtk adb -s 1.2.3.4:5555 logcat -d 2>/dev/null)
for must in "I Chronicle:" "W Timber:" "E Chronicle:"; do
  if ! printf '%s' "$kept" | command grep -q "$must"; then
    say "FAIL: filter dropped '$must' — it must never remove app or W/E lines."
    fail=1
  fi
done

# The noise must be gone.
for gone in "nativeloader" "OpenGLRenderer" "chatty"; do
  if printf '%s' "$kept" | command grep -q "$gone"; then
    say "FAIL: filter kept noise line '$gone'."
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  say "OK: RTK project filters verified."
else
  say ""
  say "One or more RTK filter checks failed. This costs tokens, not correctness —"
  say "the build is unaffected."
fi
exit "$fail"
