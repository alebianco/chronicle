#!/usr/bin/env bash
# Counts audio underruns over a fixed playback window, optionally under CPU load.
#
# Exists because the crackling reported in cu-104 was only ever seen by chance: it appeared when the
# system happened to start 51 processes at once, which is not something you can sit and wait for. To
# compare *anything* — wired vs Bluetooth, before vs after a change — the load has to be repeatable
# and the count has to come from the same place every time.
#
# The number it reports is the A2DP/audio-flinger underrun count, i.e. the thing that is audible as
# a crack. It is deliberately NOT a measure of app CPU or allocation: those are cu-104's separate
# hygiene concern and are measured with `dumpsys meminfo` / GC log lines.
#
# Usage:
#   ./measure-audio-glitches.sh                    # 120s, no induced load
#   ./measure-audio-glitches.sh 60 stress          # 60s with CPU load
#   ./measure-audio-glitches.sh 120 stress <serial>
#
# Start playback FIRST, then run this. It does not control the player: what is being measured is a
# real listening session, and driving playback from adb would change the scheduling being tested.

set -euo pipefail

DURATION="${1:-120}"
MODE="${2:-idle}"
SERIAL="${3:-}"

adb_cmd() {
  if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

DEVICE_LABEL=$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')
API=$(adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')

# Which output is actually in use decides how the result should be read: an A2DP underrun and a
# wired glitch have different causes, and conflating them is what this script exists to prevent.
ROUTE="unknown"
if adb_cmd shell dumpsys audio 2>/dev/null | grep -q "Devices: bt_a2dp"; then
  ROUTE="bluetooth-a2dp"
elif adb_cmd shell dumpsys audio 2>/dev/null | grep -qE "Devices: (usb_headset|headphone|headset)"; then
  ROUTE="wired"
elif adb_cmd shell dumpsys audio 2>/dev/null | grep -q "Devices: speaker"; then
  ROUTE="speaker"
fi

echo "device:   $DEVICE_LABEL (API $API)"
echo "route:    $ROUTE"
echo "duration: ${DURATION}s"
echo "load:     $MODE"
echo

if ! adb_cmd shell pidof io.github.mattpvaughn.chronicle.debug >/dev/null 2>&1; then
  echo "Chronicle (debug) is not running. Start playback, then re-run." >&2
  exit 1
fi

adb_cmd logcat -c
STRESS_PIDS=()

if [ "$MODE" = "stress" ]; then
  # Busy-loop one shell per core. Crude on purpose: it competes for CPU the way a process storm
  # does, without depending on which apps happen to be installed. `monkey` was rejected as the load
  # source because it also generates input events, which change playback.
  CORES=$(adb_cmd shell "grep -c processor /proc/cpuinfo" | tr -d '\r')
  echo "starting $CORES busy loops..."
  for _ in $(seq 1 "$CORES"); do
    adb_cmd shell "end=\$((\$(date +%s) + $DURATION)); while [ \$(date +%s) -lt \$end ]; do :; done" >/dev/null 2>&1 &
    STRESS_PIDS+=($!)
  done
fi

echo "measuring... (leave playback running)"
sleep "$DURATION"

for pid in "${STRESS_PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done

LOG=$(adb_cmd logcat -d 2>/dev/null)
A2DP=$(printf '%s' "$LOG" | grep -c "btif_a2dp_source_read_callback: UNDERFLOW" || true)
ENCODER=$(printf '%s' "$LOG" | grep -c "a2dp_aac_encode_frames: underflow" || true)
FLINGER=$(printf '%s' "$LOG" | grep -ciE "AudioFlinger.*(underrun|glitch)" || true)
GC=$(printf '%s' "$LOG" | grep -c "chronicle.debug.*GC freed" || true)
ERRORS=$(printf '%s' "$LOG" | grep -c "Exoplayer playback error" || true)

echo
echo "=============================================="
printf '  a2dp read underruns   %6s\n' "$A2DP"
printf '  a2dp encoder underrun %6s\n' "$ENCODER"
printf '  audioflinger glitches %6s\n' "$FLINGER"
printf '  chronicle GCs         %6s\n' "$GC"
printf '  exoplayer errors      %6s\n' "$ERRORS"
echo "=============================================="
echo
echo "Record alongside: device, route, duration, load. A count is only meaningful next to another"
echo "count taken the same way — see cu-104."
