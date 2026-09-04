#!/usr/bin/env bash
# Switch a Chronicle debug install between the real Plex account and mock mode,
# without ever needing `pm clear` (which would destroy a login nobody is around
# to redo -- see CLAUDE.md, cu-73).
#
#   plex-session.sh backup    # capture the current real session to disk
#   plex-session.sh real      # restore the real session, mock off
#   plex-session.sh mock      # switch to fixture mode
#   plex-session.sh status    # what is on the device right now
#
# Why force-stop first: DebugHooks.onApplicationCreate reads `mock_plex` at
# Application start, and MockPlexMode.enable() then overwrites
# accountAuthToken/server/library. Any pref edit made while the process is
# alive can be flushed over by SharedPreferences' in-memory cache. So every
# mutation here stops the app, edits, and lets the next launch read clean state.
set -euo pipefail

DEVICE="${CHRONICLE_DEVICE:-192.168.1.95:5555}"
PKG="${CHRONICLE_PKG:-io.github.mattpvaughn.chronicle.debug}"
BACKUP_DIR="${CHRONICLE_BACKUP_DIR:-$HOME/.chronicle-session-backup}"
PREFS="/data/data/$PKG/shared_prefs"
AUTH_FILES=(Chronicle.xml ChronicleAuth.xml)

adb_() { adb -s "$DEVICE" "$@"; }
app_running() { [ "$(adb_ shell "ps -A | grep -c $PKG" 2>/dev/null | tr -d '\r')" -gt 0 ]; }
# force-stop returns before the process is reaped; wait for it to actually go.
wait_until_stopped() {
  local i
  adb_ shell am force-stop "$PKG"
  for i in 1 2 3 4 5 6 7 8 9 10; do
    app_running || return 0
    sleep 0.3
  done
  die "$PKG is still running after force-stop; refusing to edit prefs under it"
}
runas() { adb_ shell run-as "$PKG" "$@"; }
die() { echo "error: $*" >&2; exit 1; }

require_device() {
  adb_ get-state >/dev/null 2>&1 || die "device $DEVICE not reachable (try: adb connect $DEVICE)"
  adb_ shell pm list packages 2>/dev/null | grep -q "^package:$PKG$" \
    || die "$PKG is not installed on $DEVICE"
}

# A real session names a real server; the mock always calls itself "Mock Plex Server".
is_mock_session() { grep -q "Mock Plex Server" "$1" 2>/dev/null; }

write_flag() {
  local value="$1" tmp
  # Refuse to edit prefs under a live process. SharedPreferences caches in
  # memory and writes back on shutdown, so an edit made while the app runs is
  # silently reverted -- this cost a real session once (07:44 -> 07:52).
  if app_running; then
    die "internal: write_flag called while $PKG is running"
  fi
  tmp="$(mktemp)"
  printf '%s\n' \
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
    "<map>" \
    "    <boolean name=\"mock_plex\" value=\"$value\" />" \
    "</map>" > "$tmp"
  adb_ push "$tmp" /data/local/tmp/chronicle_debug.xml >/dev/null
  runas cp /data/local/tmp/chronicle_debug.xml "$PREFS/chronicle_debug.xml"
  adb_ shell rm -f /data/local/tmp/chronicle_debug.xml
  rm -f "$tmp"
}

read_flag() {
  runas cat "$PREFS/chronicle_debug.xml" 2>/dev/null | grep -o 'value="[a-z]*"' | cut -d'"' -f2
}

cmd_backup() {
  require_device
  mkdir -p "$BACKUP_DIR"; chmod 700 "$BACKUP_DIR"
  local staging; staging="$(mktemp -d)"
  for f in "${AUTH_FILES[@]}"; do
    runas cat "$PREFS/$f" > "$staging/$f" 2>/dev/null || die "cannot read $f"
    [ -s "$staging/$f" ] || die "$f came back empty; refusing to overwrite the backup"
  done
  # Never let a mock session overwrite a real backup -- that is the one
  # unrecoverable mistake this whole script exists to prevent.
  if is_mock_session "$staging/Chronicle.xml"; then
    rm -rf "$staging"
    die "device is in MOCK mode; refusing to back that up over a real session"
  fi
  cp "$staging/"*.xml "$BACKUP_DIR/"
  chmod 600 "$BACKUP_DIR"/*.xml
  rm -rf "$staging"
  echo "backed up real session -> $BACKUP_DIR"
  grep -o 'name="server_name">[^<]*' "$BACKUP_DIR/Chronicle.xml" | sed 's/.*>/  server: /'
  grep -o 'name="library_name">[^<]*' "$BACKUP_DIR/Chronicle.xml" | sed 's/.*>/  library: /'
}

cmd_real() {
  require_device
  for f in "${AUTH_FILES[@]}"; do
    [ -s "$BACKUP_DIR/$f" ] || die "no backup at $BACKUP_DIR/$f -- run 'backup' while logged in"
  done
  is_mock_session "$BACKUP_DIR/Chronicle.xml" \
    && die "the backup itself is a mock session; refusing to restore it as real"

  wait_until_stopped
  for f in "${AUTH_FILES[@]}"; do
    adb_ push "$BACKUP_DIR/$f" "/data/local/tmp/$f" >/dev/null
    runas cp "/data/local/tmp/$f" "$PREFS/$f"
    adb_ shell rm -f "/data/local/tmp/$f"
  done
  write_flag false
  echo "restored real session; mock_plex=false. App is stopped -- launch it normally."
}

cmd_mock() {
  require_device
  # Refuse unless a real session is safely on disk, so mock is always reversible.
  for f in "${AUTH_FILES[@]}"; do
    [ -s "$BACKUP_DIR/$f" ] || die "no backup at $BACKUP_DIR/$f -- run 'backup' first; mock would be a one-way door"
  done
  is_mock_session "$BACKUP_DIR/Chronicle.xml" \
    && die "the backup is a mock session, not a real one; refusing"

  wait_until_stopped
  write_flag true
  echo "mock_plex=true. App is stopped -- next launch seeds the fixture session."
  echo "Return with: $0 real"
}

cmd_status() {
  require_device
  local flag staging
  flag="$(read_flag || true)"
  staging="$(mktemp -d)"
  runas cat "$PREFS/Chronicle.xml" > "$staging/Chronicle.xml" 2>/dev/null || true
  echo "device:   $DEVICE ($PKG)"
  echo "mock_plex flag: ${flag:-<unset>}"
  if [ -s "$staging/Chronicle.xml" ]; then
    if is_mock_session "$staging/Chronicle.xml"; then
      echo "session:  MOCK (fixture server)"
    else
      echo "session:  REAL"
      grep -o 'name="server_name">[^<]*' "$staging/Chronicle.xml" | sed 's/.*>/  server: /'
      grep -o 'name="library_name">[^<]*' "$staging/Chronicle.xml" | sed 's/.*>/  library: /'
    fi
  else
    echo "session:  <no prefs on device>"
  fi
  if [ -s "$BACKUP_DIR/Chronicle.xml" ]; then
    echo "backup:   present at $BACKUP_DIR"
  else
    echo "backup:   MISSING -- mock mode would be irreversible"
  fi
  if app_running; then echo "running: yes"; else echo "running: no"; fi
  rm -rf "$staging"
}

case "${1:-status}" in
  backup) cmd_backup ;;
  real)   cmd_real ;;
  mock)   cmd_mock ;;
  status) cmd_status ;;
  *) die "usage: $0 {backup|real|mock|status}" ;;
esac
