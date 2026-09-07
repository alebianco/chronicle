---
id: cu-218
title: "Okio across the download and cache subsystem"
status: In Review
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

- [x] Okio adopted in the download and cache-reconciliation files; the count of `java.io.File`
      importers before and after recorded
- [x] **`SyncLocationMoveTest` passes untouched** — it pins the on-disk layout that seven files
      depend on, and it is the single most important guard here
- [x] At least one test that was previously impossible without real I/O is written using
      `FakeFileSystem` — otherwise this adoption bought nothing but churn
- [x] The unreadable-versus-empty directory distinction still holds, pinned as it is today
- [x] **Zero re-downloads**: no already-downloaded book is affected. Verified on the tablet against
      real downloaded audio
- [x] Whether `kotlinx-io` and Okio coexisting is acceptable is stated, with the `FakeFileSystem`
      reason
- [x] `./verify.sh` green; `./test_release_build.sh` passes

## Result (2026-09-07)

All five files moved: `CacheScanOutcome`, `CacheReconciliation`, `CachedFileManager`'s
reconciliation, `KtorDownloader` and `MoveSyncLocationWorker`. `FileSystem` is bound in `AppModule`
rather than defaulted at the call site — a Kotlin default does not satisfy Dagger, which still
demands a binding, and providing it means tests substitute `FakeFileSystem` through the same seam
production uses instead of a back door.

**Okio and `kotlinx-io` coexist deliberately.** Ktor brings `kotlinx-io` transitively and Okio 3.17.0
was *already* on the runtime classpath via Coil and Ktor, so declaring it adds no APK weight — it
makes an existing dependency explicit. `okio-fakefilesystem` is test-only, and is the thing actually
being bought.

### The migration found two tests that could not fail

Both were the real return, and neither was the portability argument:

1. **`an unreadable directory is unavailable, not empty`** existed but was guarded by two
   `assumeTrue` calls, because chmod silently does nothing as root or on a filesystem that ignores
   permission bits — so it skipped itself exactly where it mattered. Its own comment said *"a test
   that cannot fail is worse than no test"*. It now runs deterministically against a
   `ForwardingFileSystem` whose `list()` throws, and sabotage fails it.
2. **The truncate-on-restart had no test at all.** Removing `resize(startAt)` passed the entire
   suite. The existing "restart, not a splice" case cannot catch it: there the replacement body is
   *longer* than the partial and simply overwrites it. The truncate only shows when the new content
   is **shorter**, leaving a stale tail past the end — now covered, and sabotage-verified.

### Device-verified on the tablet

- **Zero re-downloads**: three downloaded tracks survived installing this build and relaunching,
  byte-identical, with no re-download, delete or prune.
- **Both volumes, both directions**: the move logged `Rename across volumes failed … Cross-device
  link` and fell back to copy-and-delete — Okio's `atomicMove` throwing where a rename cannot cross
  filesystems is precisely the tablet behaviour the old `Files.move`/`copyTo` pair hand-coded. All
  three files intact by hash each way, source cleaned each way.

`isCompleteDownload` gained an Okio overload rather than being converted, since `MediaItemTrack` is
outside this scope; the two spellings are pinned against each other by a table-driven test so they
cannot drift.

## Notes

Closing status **In Review**: it touches the highest-risk area in the app, and "no re-downloads" is a
claim only a device with real audio can settle.

Do **not** convert the other nine `java.io.File` importers in the same task. `SettingsViewModel`,
`AudiobookMediaConversions` and friends are unrelated to this argument, and widening the diff dilutes
the evidence that the download paths still behave.
