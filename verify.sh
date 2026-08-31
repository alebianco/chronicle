#!/usr/bin/env bash
#
# verify.sh — Chronicle Unabridged's gate of record.
#
# This script, not any CI provider's config, defines "the build is fine"
# (decision-12 rule 6: file over app). CI is a thin wrapper that calls it;
# a forge-level required check is a convenience, never the source of truth.
#
# Usage:
#   ./verify.sh              full gate: ktlint, unit tests, debug APK, lint
#   ./verify.sh --quick      inner loop: ktlint + unit tests only
#   ./verify.sh --format     run ktlintFormat first, then the full gate
#   ./verify.sh --no-coverage  skip the JaCoCo report + ratchet
#
set -euo pipefail

cd "$(dirname "$0")"

QUICK=false
FORMAT=false
COVERAGE=true

for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=true ;;
    --format) FORMAT=true ;;
    --no-coverage) COVERAGE=false ;;
    -h|--help) sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "verify.sh: unknown option '$arg' (try --help)" >&2; exit 2 ;;
  esac
done

GRADLE="./gradlew"
STAGE_NUM=0

stage() {
  STAGE_NUM=$((STAGE_NUM + 1))
  echo ""
  echo "=============================================================="
  echo "  [$STAGE_NUM] $1"
  echo "=============================================================="
}

if [ "$FORMAT" = true ]; then
  stage "ktlintFormat"
  "$GRADLE" ktlintFormat
fi

stage "ktlintCheck — code style"
"$GRADLE" ktlintCheck

stage "testDebugUnitTest — unit tests"
"$GRADLE" testDebugUnitTest

if [ "$COVERAGE" = true ]; then
  stage "jacocoTestReport — coverage"
  "$GRADLE" jacocoTestReport
  ./coverage-ratchet.sh
fi

if [ "$QUICK" = true ]; then
  echo ""
  echo "=============================================================="
  echo "  QUICK VERIFY PASSED ($STAGE_NUM stages)"
  echo "  Not the full gate — run ./verify.sh before claiming done."
  echo "=============================================================="
  exit 0
fi

stage "assembleDebug — debug APK"
"$GRADLE" assembleDebug

stage "lintDebug — Android lint"
"$GRADLE" lintDebug

# The debug and release source sets each provide their own DebugHooks object, and only the
# release variant catches a twin that has drifted — DebugHooksContract makes the compiler check
# the shape, but it can only check the variant being built. Everything above builds debug only,
# so without this a release-only break lands green and fails the first release build (cu-70).
stage "compileReleaseKotlin — release variant compiles"
"$GRADLE" compileReleaseKotlin

echo ""
echo "=============================================================="
echo "  VERIFY PASSED ($STAGE_NUM stages)"
echo "=============================================================="
