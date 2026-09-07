---
name: chronicle-prefs-cache-clobber
description: Editing an Android app's SharedPreferences file while the app is running is silently reverted when the process dies
metadata:
  type: feedback
---

**Never edit a SharedPreferences XML file while the owning app is running.** The framework
keeps the map in memory and writes it back on process shutdown, so a file edit made under a
live app is silently reverted — the file reads correct immediately afterwards and wrong at
the next launch.

Observed on the Chronicle tablet 2026-09-04: `mock_plex` was set to `false` on disk at 07:44
under a running app, verified as `false`, then read back as `true` at the 07:52 cold start
because the app flushed its cached copy on force-stop at 07:47. That launch seeded mock mode
over a real Plex login that could not be re-created.

**Why:** the write appears to succeed, so the usual "verify by reading it back" check passes
and proves nothing.
**How to apply:** `am force-stop` the package and confirm the process is gone (`ps -A | grep`)
*before* touching its `shared_prefs/`. `force-stop` returns before the process is reaped, so
poll rather than assuming. `plex-session.sh` encodes this as `wait_until_stopped`.

Related: [[chronicle-tablet-session]], [[chronicle-sabotage-rerun-tasks]]
