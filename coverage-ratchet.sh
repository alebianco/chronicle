#!/usr/bin/env bash
#
# coverage-ratchet.sh — fail the build if unit-test coverage regresses.
#
# The baseline lives in coverage-baseline.txt, a plain committed file, so the
# gate is forge-agnostic and every change to it shows up in a diff for review
# (decision-12 rule 6: file over app; rule 7: no third-party coverage SaaS).
#
# Usage:
#   ./coverage-ratchet.sh          check current coverage against the baseline
#   ./coverage-ratchet.sh --update rewrite the baseline to the current value
#
set -euo pipefail

cd "$(dirname "$0")"

REPORT="app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"
BASELINE_FILE="coverage-baseline.txt"

if [ ! -f "$REPORT" ]; then
  echo "coverage-ratchet: no JaCoCo report at $REPORT" >&2
  echo "coverage-ratchet: run './gradlew jacocoTestReport' first." >&2
  exit 1
fi

# Instruction coverage is JaCoCo's most stable metric across compiler versions.
CURRENT=$(python3 - "$REPORT" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for counter in root.findall("counter"):
    if counter.get("type") == "INSTRUCTION":
        missed, covered = int(counter.get("missed")), int(counter.get("covered"))
        total = missed + covered
        print(f"{(covered / total * 100) if total else 0:.2f}")
        sys.exit(0)
print("ERROR: no INSTRUCTION counter in report", file=sys.stderr)
sys.exit(1)
PY
)

if [ "${1:-}" = "--update" ]; then
  printf '%s\n' "$CURRENT" > "$BASELINE_FILE"
  echo "coverage-ratchet: baseline updated to ${CURRENT}%"
  exit 0
fi

# A missing baseline must never mean "gate passes". coverage-baseline.txt is a
# committed file; if it is absent in CI, the ratchet is enforcing nothing and
# that is exactly the green-no-op this script exists to prevent.
if [ ! -f "$BASELINE_FILE" ]; then
  if [ -n "${CI:-}" ]; then
    echo "coverage-ratchet: $BASELINE_FILE is missing." >&2
    echo "coverage-ratchet: it is a committed file — restore it rather than" >&2
    echo "coverage-ratchet: letting CI pass with no coverage gate." >&2
    exit 1
  fi
  echo "coverage-ratchet: no $BASELINE_FILE; seeding it at ${CURRENT}%"
  echo "coverage-ratchet: commit this file — the gate is inert without it."
  printf '%s\n' "$CURRENT" > "$BASELINE_FILE"
  exit 0
fi

BASELINE=$(tr -d '[:space:]' < "$BASELINE_FILE")

if ! printf '%s' "$BASELINE" | grep -Eq '^[0-9]+(\.[0-9]+)?$'; then
  echo "coverage-ratchet: $BASELINE_FILE is malformed (got '$BASELINE')." >&2
  echo "coverage-ratchet: expected a bare percentage such as '12.34'." >&2
  exit 1
fi

# Tolerance absorbs sub-0.01% jitter from compiler/codegen churn, so unrelated
# PRs are not blocked by rounding.
VERDICT=$(python3 -c "
import sys
current, baseline = float('$CURRENT'), float('$BASELINE')
if current + 0.05 < baseline:
    print('FAIL')
elif current > baseline:
    print('RAISE')
else:
    print('OK')
")

DELTA=$(python3 -c "print(f'{float('$CURRENT') - float('$BASELINE'):+.2f}')")

case "$VERDICT" in
  FAIL)
    echo ""
    echo "  COVERAGE REGRESSION"
    echo "  baseline: ${BASELINE}%   current: ${CURRENT}%   delta: ${DELTA}%"
    echo ""
    echo "  Add tests for what you changed. If the drop is genuinely correct"
    echo "  (e.g. you deleted well-tested dead code), lower the baseline"
    echo "  deliberately with './coverage-ratchet.sh --update' and say why in"
    echo "  the commit message."
    exit 1
    ;;
  RAISE)
    printf '%s\n' "$CURRENT" > "$BASELINE_FILE"
    echo "coverage-ratchet: coverage rose ${BASELINE}% -> ${CURRENT}% (${DELTA}%); baseline ratcheted up."
    echo "coverage-ratchet: commit $BASELINE_FILE to lock the gain in."
    ;;
  OK)
    echo "coverage-ratchet: ${CURRENT}% vs baseline ${BASELINE}% (${DELTA}%) — no regression."
    ;;
esac
