#!/usr/bin/env bash
#
# verify.sh — Chronicle Unabridged's gate of record.
#
# This script, not any CI provider's config, defines "the build is fine"
# (decision-12 rule 6: file over app). CI is a thin wrapper that calls it;
# a forge-level required check is a convenience, never the source of truth.
#
# Usage:
#   ./verify.sh              full gate: ktlint, unit tests, debug APK, detekt, lint
#   ./verify.sh --quick      inner loop: ktlint + unit tests only
#   ./verify.sh --format     run ktlintFormat first, then the full gate
#   ./verify.sh --no-coverage  skip the JaCoCo report + ratchet
#   ./verify.sh --instrumented add the Espresso suite on two managed emulators
#   ./verify.sh --mutation   add the PIT mutation score, reported and never fatal
#
set -euo pipefail

cd "$(dirname "$0")"

QUICK=false
FORMAT=false
COVERAGE=true
INSTRUMENTED=false
MUTATION=false

for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=true ;;
    --format) FORMAT=true ;;
    --no-coverage) COVERAGE=false ;;
    --instrumented) INSTRUMENTED=true ;;
    --mutation) MUTATION=true ;;
    -h|--help) sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "verify.sh: unknown option '$arg' (try --help)" >&2; exit 2 ;;
  esac
done

# `--quick` returns before the opt-in stages, so combining it with one would accept the flag and
# silently do nothing — the shape of failure this project keeps paying for. Refuse instead.
if [ "$QUICK" = true ] && [ "$MUTATION" = true ]; then
  echo "verify.sh: --mutation cannot be combined with --quick." >&2
  echo "  --quick is the inner loop and stops after the unit tests; mutation testing costs about" >&2
  echo "  a minute and belongs to a full run. Use: ./verify.sh --mutation" >&2
  exit 2
fi

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

stage "check-memory-safe — no private data in committed memory"
./check-memory-safe.sh

stage "check-agent-refs — agent/command definitions resolve"
./check-agent-refs.sh

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

# detekt: complexity, potential bugs and coroutine misuse. Not formatting, style or naming —
# `ktlintCheck` above owns those, and two linters arguing about the same lines produce a build no
# edit satisfies. See app/build.gradle.kts and config/detekt/detekt.yml.
#
# **`detektDebug`, not `detekt`.** The bare task analyses without a classpath, and the rules worth
# having here — `UnsafeCallOnNullableType`, `ElseCaseInsteadOfExhaustiveWhen` — need type
# resolution to decide anything at all. Measured on this tree: 31 findings without it, 120 with.
# A rule that cannot resolve a type does not report a false negative, it reports nothing, and a
# linter finding nothing looks exactly like a clean tree.
#
# **Full gate, not `--quick`.** Measured at ~12s of analysis on top of an already-compiled debug
# variant — and it needs that compile, which `--quick` deliberately does not do. Placed after
# `assembleDebug` so it reuses that compilation rather than forcing its own.
#
# Ratcheted: `config/detekt/baseline-debug.xml` holds today's findings, so this fails on *new* ones
# only.
stage "detektDebug — complexity, potential bugs, coroutines"
"$GRADLE" :app:detektDebug

stage "lintDebug — Android lint"
"$GRADLE" lintDebug

# The debug and release source sets each provide their own DebugHooks object, and only the
# release variant catches a twin that has drifted — DebugHooksContract makes the compiler check
# the shape, but it can only check the variant being built. Everything above builds debug only,
# so without this a release-only break lands green and fails the first release build.
stage "compileReleaseKotlin — release variant compiles"
"$GRADLE" compileReleaseKotlin

# The *test* half of the release variant, which the stage above does not cover. `app/src/test/` is
# shared by every variant, so a test reaching a debug-only symbol compiles under debug and fails
# under release. That is not hypothetical: `MoveSyncLocationHookTest` called a `DebugHooks` member
# the release twin does not declare, and this variant had **never compiled** for as long as the
# hook tests existed — invisible to this gate and to CI, because nothing built it.
stage "compileReleaseUnitTestKotlin — release test sources compile"
"$GRADLE" :app:compileReleaseUnitTestKotlin

# Opt-in, not part of the default gate: it provisions two emulators and takes minutes rather
# than seconds, which would wreck the inner loop. The unit gate must stay fast enough to run on
# every edit. Run this before a release, or when touching Activity/Fragment lifecycle, the media
# session, or anything the unit suite structurally cannot reach.
if [ "$INSTRUMENTED" = true ]; then
  stage "instrumentedCheckGroup — Espresso on API 27 and 35"
  "$GRADLE" instrumentedCheckGroupGroupDebugAndroidTest
fi

# Mutation score. Opt-in and, deliberately, **never fatal**.
#
# Not in the default gate because it costs a measured ~60s on top of a full verify, and it answers
# a different question from the coverage ratchet: "would the tests notice if this code changed?"
# rather than "was this line executed?". That is worth minutes, not seconds.
#
# Not fatal because there is no floor yet. A threshold picked before the first honest measurement is
# either vacuous or blocks the build on day one, and a gate that blocks on day one gets disabled —
# which is strictly worse than not having it. The number is printed so a floor can be ratcheted from
# a real baseline later; until then this stage reports and moves on.
#
# The score is only meaningful because no Robolectric test is in PIT's scope: PIT + Robolectric is
# broken upstream and fails *silently* with false SURVIVED/NO_COVERAGE. `PitestScopeTest` is what
# keeps that true.
if [ "$MUTATION" = true ]; then
  stage "pitestDebug — mutation score (reported, non-blocking)"
  if "$GRADLE" pitestDebug; then
    report="app/build/reports/pitest/debug/mutations.xml"
    if [ -f "$report" ]; then
      killed=$(grep -c "status='KILLED'" "$report" || true)
      survived=$(grep -c "status='SURVIVED'" "$report" || true)
      uncovered=$(grep -c "status='NO_COVERAGE'" "$report" || true)
      total=$((killed + survived + uncovered))
      echo ""
      echo "  mutation score: $killed killed / $total generated"
      echo "  $survived survived, $uncovered with no coverage"
      echo "  A surviving mutant is a change to the code no test objected to."
      echo "  Report: app/build/reports/pitest/debug/index.html"
    else
      echo "  pitestDebug reported success but wrote no $report — not failing the gate on it."
    fi
  else
    echo ""
    echo "  !! pitestDebug FAILED. Not fatal: this stage reports, it does not gate."
    echo "     It is still worth reading — the last time PIT could not run at all, it stayed"
    echo "     broken because nothing in the build ever said so."
  fi
fi

echo ""
echo "=============================================================="
echo "  VERIFY PASSED ($STAGE_NUM stages)"
echo "=============================================================="
