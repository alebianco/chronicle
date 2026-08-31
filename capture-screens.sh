#!/usr/b#!/usr/bin/env bash
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
PKG=io.github.mattpvaughn.chronicle
ACT="$PKG/.application.MainActivity"
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

# Library tab, then settings tab (bottom nav on a 2560x1600 tablet).
tap 1280 1424
shot library 5
tap 1615 1424
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
if ! echo "$FG" | grep -q "$PKG"; then
  echo "ERROR: $PKG was not in the foreground at the end of the run."
  echo "       Captures may show the launcher. Foreground was: $FG"
  exit 1
fi

echo "Done. Screenshots in $OUT"
