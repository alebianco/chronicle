---
id: cu-153
title: Check whether moving the sync location leaves partials behind
status: Done
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - comfort
dependencies:
  - cu-81
milestone: m-2
priority: low
ordinal: 46900
---

## Description

cu-81's design notes asked whether `MoveSyncLocationWorker` can leave partial downloads behind in
the **source** directory when the user changes storage location. It can in principle — the worker
moves files between directories and cu-81's prune only ever looks at the *current*
`cachedMediaDir` — but confirming it needs two real storage volumes, which is a device question
rather than a code one.

**A live-device task**, and it needs hardware the fixture pack cannot simulate: an SD card or a
second external volume, with a download interrupted partway.

## Acceptance Criteria

- [x] Reproduce: start a download, interrupt it, change the sync location, and check whether the
      partial remains in the old directory — **it does not**; reproduced on the tablet across both
      real volumes, in both directions
- [x] If it does, decide where the fix belongs — **no fix needed**; the premise does not hold, and
      the reason is now pinned by a test
- [x] Whatever the answer, `cachedMediaDir` returns the **stored** path even when the volume is
      unmounted (cu-85) — not reached: nothing here scans a directory other than the active one, so
      the cu-85 hazard is not created

## Implementation Notes

Filed out of cu-81 rather than guessed at. The prune shipped there is deliberately scoped to the
active directory, which is the safe subset: it can leave bytes behind, but it cannot delete from a
volume it has not properly scanned.

## Implementation Notes

**The suspected bug does not exist.** Changing the sync location moves partial downloads along with
complete ones, so nothing is stranded on the old volume.

**Why**, and it is a naming fact rather than a lucky accident: Fetch2 downloads **in place** and
resumes over HTTP Range, so a partial is named `<trackId>.<ext>` exactly like a finished file —
there is no `.part`/`.tmp` suffix. `MoveSyncLocationWorker` selects with
`MediaItemTrack.cachedFilePattern`, which therefore cannot distinguish them and moves both. That is
the *correct* behaviour, but it is load-bearing and undocumented, so `SyncLocationMoveTest` pins it:
a future change that gave partials a distinguishing suffix would silently start orphaning them, and
cu-81's prune only ever looks at the active directory.

**Verified on hardware, not inferred.** The tablet has two real volumes — internal emulated storage
and a physical SD card (`public:179,129`, UUID `79AF-CD2E`, 59 GB). A 200 KB "complete" file and a
37 KB "partial" were planted in the source directory and the move run in **both** directions:

```
MoveSyncLocationWorker: Moving file .../emulated/0/.../900002.mp3 -> .../79AF-CD2E/.../900002.mp3
MoveSyncLocationWorker: Moving file .../emulated/0/.../900001.mp3 -> .../79AF-CD2E/.../900001.mp3
```

Source directory empty afterwards, both files present at their original sizes, no failure
notification. The reverse (SD → internal) behaves identically, which is worth having checked
separately: the two volumes are different filesystems and `Files.move` can fall back to copy+delete
across them.

**A `move_sync_location` debug hook was added** to make this reproducible by script. The settings
control is no more reachable from `adb shell input tap` than the bottom nav is (cu-54/cu-24), and
the hook runs exactly what `SettingsViewModel.setSyncLocation` runs — set `cachedMediaDir`, enqueue
`MoveSyncLocationWorker` as unique work. It **validates the path against the app's own
`externalDeviceDirs()`** rather than trusting it: `cachedMediaDir` accepts any string, so an
unmatched path would point downloads at an unwritable directory and fail much later as "downloads
don't work". The match is exact, not `startsWith` — sabotage-verified, a prefix match fails
`a parent of a real external dir is refused`.

**Closed to `Done`**: the question was empirical and the answer is reproducible — a unit test for
the selection rule, a hook that replays the scenario, and a device run in both directions. No screen
changed and no product choice was made.

Device left as found: test files removed, sync location back to internal, real ANTARES session
intact. Coverage: aggregate unchanged, `debug` package 1.20 → 2.17.
