---
id: cu-85
title: Cache status lost when the sync directory resolves differently
status: In Review
labels: [R1, trust, bug]
dependencies: [cu-76]
priority: high
assignee: [claude]
---

## Description

Owner-reported (2026-08-31): *"book reports no cache even if I'm sure I have downloaded it.
Sometimes right after I downloaded it, more often a long time after I downloaded it."*

`CachedFileManager.refreshTrackDownloadedStatus` scans `prefsRepo.cachedMediaDir` and **un-caches
every track it does not find there**. So anything that makes that directory resolve differently
wipes the cache status of a whole library, with the files still on disk.

`cachedMediaDir` resolves as:

```kotlin
val syncLoc = sharedPreferences.getString(KEY_SYNC_DIR_PATH, "")
if (syncLoc.isNullOrEmpty()) Injector.get().externalDeviceDirs().first()  // and persists it
else File(syncLoc)
```

Candidate causes, in rough order of suspicion:

1. **`externalDeviceDirs().first()` is not a stable choice.** `Context.getExternalFilesDirs()` can
   return entries in a different order, and can return `null` entries for unmounted volumes. If
   `first()` ever resolves to a different volume than the one downloads went to, the scan finds
   nothing.
2. **A removable volume is temporarily unavailable** — SD card unmounted, or not yet mounted when
   the scan runs at launch. The directory exists in prefs but `listFiles()` returns null/empty, and
   the code treats that as "nothing is downloaded" rather than "cannot tell".
3. **The scan cannot distinguish "no files" from "cannot read the directory".** `listFiles()`
   returning `null` (path missing or unreadable) is coalesced into `emptyList()`, and then every
   cached track is marked uncached. This is the bug regardless of which cause triggers it.

Note [[cu-76]] made the scan *stricter* (rejecting files shorter than `MediaItemTrack.size`), which
is correct but adds a second way to un-cache: a track whose stored `size` is wrong now fails the
check. Worth confirming `size` is populated for the affected books.

## Fix direction

**A scan that cannot read its directory must do nothing, not un-cache everything.** Distinguish:

- directory missing/unreadable (`listFiles() == null`) → log and return without touching the DB;
- directory readable and genuinely empty → the current behaviour is right.

Then separately make the default directory choice stable, and consider whether an unmounted volume
should surface as a user-visible state ("downloads are on a card that isn't inserted") rather than
silently reading as not-downloaded.

## Acceptance Criteria

- [x] An unreadable or missing sync directory leaves cached status **unchanged**
- [x] A readable, empty directory still un-caches (the legitimate case) — both directions tested
- [x] The default sync-directory choice does not depend on `externalDeviceDirs()` ordering
- [ ] `MoveSyncLocationWorker` interaction checked: moving storage must not leave the old path in
      prefs
- [ ] Live checks in [[cu-73]]: download a book, relaunch several times, confirm status sticks; and
      with downloads on an SD card, unmount it and confirm the app does not report them gone forever
- [x] Verify loop green

## Implementation Notes

Three distinct defects, all in the path from "which directory?" to "is this book downloaded?".

### 1. Unreadable was indistinguishable from empty

`listFiles(...) ?: emptyList()` — `listFiles` returns null for a missing or unreadable directory, so
both became "no files", and the scan then un-cached every track. Now
`scanCachedMediaDir` returns `CacheScanOutcome.Scanned` or `Unavailable`, and
`refreshTrackDownloadedStatus` returns early on `Unavailable` without touching the database. Also
reports a path that exists but is a *file* as unavailable, since that means the sync location is
wrong rather than the downloads being gone.

### 2. The stored sync path silently fell back to a different directory

The subtler half, and the one that made the guard in (1) insufficient on its own:

```kotlin
externalDeviceDirs().firstOrNull { it.absolutePath == syncLoc } ?: externalDeviceDirs().first()
```

With an SD card removed, the stored path is not in the current list, so this returned a
*different, perfectly readable* directory. The scan found none of the expected files **there** and
un-cached the library — `Unavailable` never fired, because the wrong directory was readable.

Now the stored path is returned as-is whenever one is set, mounted or not, so an absent volume
surfaces as `Unavailable` and changes nothing. Recoverable when the card goes back in. The first-run
default still picks the first available directory but **persists it immediately**, so ordering is
consulted exactly once in the app's lifetime.

### 3. `externalDeviceDirs()` could contain nulls

`getExternalFilesDirs` returns a `File[]` with **null entries** for unavailable volumes, and
`.toList()` kept them — so the declared `List<File>` really held nulls, and `first()` could return
null in defiance of its own type. Now `filterNotNull()`.

7 tests for the scan distinction, verified to bite by restoring `?: emptyList()` (3 fail).

### Not done

`MoveSyncLocationWorker` was not audited — moving storage may leave a stale path in prefs, which
after this change means the app keeps pointing at the old location instead of quietly relocating.
That is the *safer* failure, but it is not verified. Kept as an open criterion rather than claimed.
