---
id: cu-218
title: "Okio across the download and cache subsystem"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - architecture
  - testing
milestone: m-3
dependencies: 
  - cu-210
  - cu-195
priority: medium
---

## Description

cu-194 raises Okio and is careful to say the framework-free guard is *not* the argument:
`FrameworkFreeCoreTest` bans `android.*`/`androidx.*` imports, **not** JVM ones, so `java.io.File`
does not by itself keep a file off that list.

The real argument is where those files are. **16 files in `app/src/main` import `java.io.File`**, and
the concentration is not incidental:

`MoveSyncLocationWorker`, `CacheScanOutcome`, `CacheReconciliation`, `CachedFileManager`,
`KtorDownloader`, `TrackRepository`, `StorageUtils` — **that is the entire download and
cache-reconciliation subsystem**, which is exactly where four prior tasks (cu-85, cu-81, cu-153,
cu-76) have failure modes that end in *deleted audio*.

## The Android justification, independent of KMP

**`FakeFileSystem`.** That subsystem's logic is currently tested against real temp directories —
`KtorDownloaderTest` uses `TemporaryFolder`, `prunePartialFiles` is separated from its decision
precisely so the filesystem part can be exercised at all. An in-memory filesystem makes the
following testable without touching disk:

- an unreadable directory versus an empty one — the distinction `CacheScanOutcome` exists for, after
  coalescing them un-cached whole libraries;
- a move that falls back to copy-and-delete across filesystems, which is `MoveSyncLocationWorker`'s
  real behaviour on the tablet's two volumes;
- a partial file at a specific length, for `Range` resume, without writing bytes.

The portability gain is a **tie-breaker**, exactly as cu-194 and decision-24 both frame it.

## Sequencing, which is not optional

**After cu-195 closes.** That task still has open device criteria over these same files — resume over
`Range` and the two-volume move. Changing the file API underneath an unverified download rewrite
would make any failure ambiguous between the two changes.

Note Ktor already brings **`kotlinx-io`** transitively. Okio is additive, and the task should say
plainly why both exist rather than leaving it to be rediscovered: Okio has `FakeFileSystem`, which is
the thing being bought.

## Acceptance Criteria

- [ ] Okio adopted in the download and cache-reconciliation files; the count of `java.io.File`
      importers before and after recorded
- [ ] **`SyncLocationMoveTest` passes untouched** — it pins the on-disk layout that seven files
      depend on, and it is the single most important guard here
- [ ] At least one test that was previously impossible without real I/O is written using
      `FakeFileSystem` — otherwise this adoption bought nothing but churn
- [ ] The unreadable-versus-empty directory distinction still holds, pinned as it is today
- [ ] **Zero re-downloads**: no already-downloaded book is affected. Verified on the tablet against
      real downloaded audio
- [ ] Whether `kotlinx-io` and Okio coexisting is acceptable is stated, with the `FakeFileSystem`
      reason
- [ ] `./verify.sh` green; `./test_release_build.sh` passes

## Notes

Closing status **In Review**: it touches the highest-risk area in the app, and "no re-downloads" is a
claim only a device with real audio can settle.

Do **not** convert the other nine `java.io.File` importers in the same task. `SettingsViewModel`,
`AudiobookMediaConversions` and friends are unrelated to this argument, and widening the diff dilutes
the evidence that the download paths still behave.
