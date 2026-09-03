#!/usr/bin/env bash
#
# coverage-ratchet.sh — fail the build if unit-test coverage regresses.
#
# Two gates, both from the same JaCoCo report:
#
#   1. Aggregate: one number in coverage-baseline.txt.
#   2. Per package: one number per package in coverage-baseline-packages.txt.
#
# The second exists because the aggregate cannot see *where* coverage sits, and
# in this codebase it sits backwards: `data/model` is above 80% while
# `features/collections` and `features/home` are at 0%, and the average passes.
# A package can therefore rot to nothing while the total rises (cu-135).
#
# Both baselines are plain committed files, so the gate is forge-agnostic and
# every movement shows up in a diff for review (decision-12 rule 6: file over
# app; rule 7: no third-party coverage SaaS).
#
# Usage:
#   ./coverage-ratchet.sh          check current coverage against the baselines
#   ./coverage-ratchet.sh --update rewrite both baselines to the current values
#
set -euo pipefail

cd "$(dirname "$0")"

REPORT="app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"
BASELINE_FILE="coverage-baseline.txt"
PACKAGE_BASELINE_FILE="coverage-baseline-packages.txt"

# Tolerance for codegen/compiler jitter, in percentage points. The aggregate
# baseline only ever moves *up* (the OK branch deliberately does not rewrite
# it), so this is a per-comparison allowance against a high-water mark and not
# a per-run licence to drift downhill: a second consecutive drop is measured
# against the same high baseline and fails.
TOLERANCE=0.05

# Per-package numbers are computed over far fewer instructions, so the same
# absolute jitter is a larger percentage. A package of 400 instructions moves
# 0.25% per instruction.
PACKAGE_TOLERANCE=0.50

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

# `<package name> <coverage>` per line, sorted by package, for a stable diff.
current_packages() {
  python3 - "$REPORT" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
rows = []
for pkg in root.findall("package"):
    for counter in pkg.findall("counter"):
        if counter.get("type") == "INSTRUCTION":
            missed, covered = int(counter.get("missed")), int(counter.get("covered"))
            total = missed + covered
            rows.append((pkg.get("name"), (covered / total * 100) if total else 0.0))
if not rows:
    print("ERROR: no packages in report", file=sys.stderr)
    sys.exit(1)
for name, pct in sorted(rows):
    print(f"{name} {pct:.2f}")
PY
}

if [ "${1:-}" = "--update" ]; then
  printf '%s\n' "$CURRENT" > "$BASELINE_FILE"
  current_packages > "$PACKAGE_BASELINE_FILE"
  echo "coverage-ratchet: baseline updated to ${CURRENT}%"
  echo "coverage-ratchet: per-package baseline updated ($(wc -l < "$PACKAGE_BASELINE_FILE" | tr -d ' ') packages)"
  exit 0
fi

# A missing baseline must never mean "gate passes". coverage-baseline.txt is a
# committed file; if it is absent in CI, the ratchet is enforcing nothing and
# that is exactly the green-no-op this script exists to prevent.
require_baseline() {
  local file="$1" what="$2"
  [ -f "$file" ] && return 0
  if [ -n "${CI:-}" ]; then
    echo "coverage-ratchet: $file is missing." >&2
    echo "coverage-ratchet: it is a committed file — restore it rather than" >&2
    echo "coverage-ratchet: letting CI pass with no $what gate." >&2
    exit 1
  fi
  return 1
}

if ! require_baseline "$BASELINE_FILE" "coverage"; then
  echo "coverage-ratchet: no $BASELINE_FILE; seeding it at ${CURRENT}%"
  echo "coverage-ratchet: commit this file — the gate is inert without it."
  printf '%s\n' "$CURRENT" > "$BASELINE_FILE"
  SEEDED_AGGREGATE=1
fi

if ! require_baseline "$PACKAGE_BASELINE_FILE" "per-package coverage"; then
  echo "coverage-ratchet: no $PACKAGE_BASELINE_FILE; seeding it from the report"
  echo "coverage-ratchet: commit this file — the per-package gate is inert without it."
  current_packages > "$PACKAGE_BASELINE_FILE"
  SEEDED_PACKAGES=1
fi

# Nothing to compare against on a first run: both baselines were just written.
if [ -n "${SEEDED_AGGREGATE:-}" ] && [ -n "${SEEDED_PACKAGES:-}" ]; then
  exit 0
fi

# ---------------------------------------------------------------- aggregate --

BASELINE=$(tr -d '[:space:]' < "$BASELINE_FILE")

if ! printf '%s' "$BASELINE" | grep -Eq '^[0-9]+(\.[0-9]+)?$'; then
  echo "coverage-ratchet: $BASELINE_FILE is malformed (got '$BASELINE')." >&2
  echo "coverage-ratchet: expected a bare percentage such as '12.34'." >&2
  exit 1
fi

VERDICT=$(python3 -c "
current, baseline, tol = float('$CURRENT'), float('$BASELINE'), float('$TOLERANCE')
if current + tol < baseline:
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

# ------------------------------------------------------------- per package --
#
# Reports regressions, new packages and departed packages in one pass, then
# rewrites the baseline with every rise ratcheted up. A new package is *seeded,
# not ignored*: silently admitting an untested package is the hole this gate
# exists to close, so it is announced and recorded.

# The gate's own logic, checked before it is trusted. A classifier that has
# rotted would pass everything silently, which is the exact failure this script
# exists to prevent (the repo rule: a check that cannot fail proves nothing).
python3 compare-package-coverage.py --self-test

trap 'rm -f "${PACKAGE_BASELINE_FILE}.next"' EXIT

PACKAGE_REPORT=$(current_packages |
  python3 compare-package-coverage.py "$PACKAGE_BASELINE_FILE" "$PACKAGE_TOLERANCE" \
    "${PACKAGE_BASELINE_FILE}.next")

if grep -q '^REGRESSED ' <<< "$PACKAGE_REPORT"; then
  echo ""
  echo "  PER-PACKAGE COVERAGE REGRESSION"
  echo ""
  printf '  %-58s %8s %8s\n' "package" "baseline" "current"
  grep '^REGRESSED ' <<< "$PACKAGE_REPORT" |
    while read -r _ name was now; do
      printf '  %-58s %8s %8s\n' "$name" "$was" "$now"
    done
  echo ""
  echo "  The aggregate gate cannot see this: a package can rot to nothing"
  echo "  while the total rises. Add tests for the package you changed, or if"
  echo "  the drop is genuinely correct lower its baseline deliberately with"
  echo "  './coverage-ratchet.sh --update' and say why in the commit message."
  exit 1
fi

while read -r kind name a b; do
  case "$kind" in
    ADDED)
      echo "coverage-ratchet: new package $name seeded at ${a}% — commit $PACKAGE_BASELINE_FILE"
      ;;
    DEPARTED)
      echo "coverage-ratchet: package $name is gone (was ${a}%); dropped from the baseline"
      ;;
    RAISED)
      echo "coverage-ratchet: $name rose ${a}% -> ${b}%; ratcheted up"
      ;;
  esac
done <<< "$(grep -E '^(ADDED|DEPARTED|RAISED) ' <<< "$PACKAGE_REPORT" || true)"

if cmp -s "${PACKAGE_BASELINE_FILE}.next" "$PACKAGE_BASELINE_FILE"; then
  echo "coverage-ratchet: all $(grep -c . "$PACKAGE_BASELINE_FILE") packages at or above their floor."
else
  mv "${PACKAGE_BASELINE_FILE}.next" "$PACKAGE_BASELINE_FILE"
  echo "coverage-ratchet: commit $PACKAGE_BASELINE_FILE to lock the per-package changes in."
fi
