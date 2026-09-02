#!/usr/bin/env bash
#
# capture-screens.sh — screenshot the main screens against the cu-16 mock server.
#
# Gives UI work a before/after baseline that does not depend on a Plex account or
# on whatever a real library happens to contain. Intended for cu-58's
# DataBinding→ViewBinding conversion, where the automated gate can prove the app
# compiles but not that a screen still renders.
#
# Usage: ./capture-screens.sh <output-dir>
#
set -euo pipefail

OUT="${1:?usage: capture-screens.sh <output-dir>}"
# The mock server lives in the debug variant only, so that is the package this drives. It used
# to hardcode the release id: `am start` then targeted a package that is not installed, and the
# foreground assertion below still passed, because `grep -q` matched `...chronicle.debug` as a
# substring of the release name. Overridable for a release-variant capture.
PKG="${CHRONICLE_PKG:-io.github.mattpvaughn.chronicle.debug}"
ACT="$PKG/io.github.mattpvaughn.chronicle.application.MainActivity"
mkdir -p "$OUT"

shot() {
  local name="$1"
  local wait="${2:-3}"
  sleep "$wait"
  adb shell screencap -p /sdcard/_cap.png
  adb pull /sdcard/_cap.png "$OUT/$name.png" >/dev/null
  echo "  captured $name"
}

tap() { adb shell input tap "$1" "$2"; }

# Tap a view by resource id, resolving its centre from the live view hierarchy.
#
# The coordinates here used to be hardcoded for a 2560x1600 tablet, so on any other screen the
# taps landed off-target — on a 1200x1920 device the settings tap was off-screen entirely, and the
# run reported "captured settings" having captured the library again. The duplicate check caught
# it, but only after the fact.
#
# `uiautomator dump` is usable for this since cu-110: the main thread no longer saturates, so the
# UI actually reaches idle and the dump succeeds. Before that it failed with
# "could not get idle state", which is why this script was written around blind coordinates.
tap_id() {
  local id="$1"
  local xml
  xml="$(mktemp)"
  if ! adb shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1; then
    echo "ERROR: uiautomator dump failed; cannot resolve '$id'." >&2
    exit 1
  fi
  adb pull /sdcard/_ui.xml "$xml" >/dev/null 2>&1
  local coords
  coords="$(python3 - "$xml" "$id" <<'PYINNER'
import re, sys
xml, wanted = sys.argv[1], sys.argv[2]
d = open(xml, encoding="utf-8", errors="replace").read()
m = re.search(
    r'<node[^>]*resource-id="[^"]*id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    % re.escape(wanted), d)
if not m:
    sys.exit(1)
x1, y1, x2, y2 = map(int, m.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
PYINNER
)" || { echo "ERROR: '$id' not found in the view hierarchy." >&2; exit 1; }
  rm -f "$xml"
  echo "  tapping $id at $coords"
  # shellcheck disable=SC2086
  adb shell input tap $coords
}

echo "Installing debug APK..."
./gradlew assembleDebug -q >/dev/null
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null

echo "Enabling mock Plex mode..."
adb shell am force-stop "$PKG" || true
# The flag is persisted and applied at Application start, so this launch only
# records it; the process restarts itself and the next launch is mocked.
adb shell am start -n "$ACT" --ez mock_plex true >/dev/null
sleep 6
adb shell am force-stop "$PKG" || true
sleep 2
adb shell am start -n "$ACT" >/dev/null

echo "Capturing..."
shot home 14

# Book details: first cover in the "recently added" row.
tap 640 390
shot book-details 5

# Re-launch rather than pressing BACK: on the details screen BACK exits the app,
# and every later tap then lands on the launcher — which silently produced
# "screenshots" of the home screen instead of failing.
relaunch() {
  adb shell am start -n "$ACT" >/dev/null
  sleep 4
}
relaunch

# Library tab, then settings tab — resolved from the hierarchy, not hardcoded.
tap_id nav_library
shot library 5
tap_id nav_settings
shot settings 4

# A capture that is byte-identical to the previous one means the tap did not change the screen —
# a stale AVD foregrounding another app, or coordinates landing on the tablet taskbar drawn over
# the app's own nav bar. Both happened, and the run still reported "captured" for each screen.
prev=""
dupes=0
for f in "$OUT"/*.png; do
  sum=$(md5 -q "$f" 2>/dev/null || md5sum "$f" | cut -d' ' -f1)
  if [ -n "$prev" ] && [ "$sum" = "$prev" ]; then
    echo "WARNING: $(basename "$f") is identical to the previous capture — the screen did not change."
    dupes=$((dupes + 1))
  fi
  prev="$sum"
done
if [ "$dupes" -gt 0 ]; then
  echo "ERROR: $dupes duplicate capture(s). Screens were not navigated; do not trust this run."
  exit 1
fi

# A screenshot is only evidence if the app was actually in the foreground.
FG=$(adb shell dumpsys activity activities | grep -m1 "ResumedActivity" || true)
# Word-boundary match: a substring test passes when the *other* variant is in the foreground,
# which is how the wrong-package bug above stayed invisible.
if ! echo "$FG" | grep -qE "(^|[^A-Za-z0-9._])${PKG//./\\.}/"; then
  echo "ERROR: $PKG was not in the foreground at the end of the run."
  echo "       Captures may show the launcher. Foreground was: $FG"
  exit 1
fi

echo "Done. Screenshots in $OUT"
