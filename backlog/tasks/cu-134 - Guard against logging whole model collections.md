---
id: cu-134
title: Guard against logging whole model collections
status: Done
assignee:
  - '@claude'
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - performance
  - agentic
milestone: m-2
dependencies:
  - cu-110
priority: medium
---

## Description

[[cu-110]] suggested this and the evidence now justifies it: interpolating a model collection into
a `Timber` call is a **recurrent** defect in this codebase, and hand-hunting keeps missing
instances.

Known history:
- cu-110 fixed three (`HomeViewModel`, `ChapterListAdapter`, `ChapterRepository`) and stated the
  class had been swept.
- The 2026-09-02 review then found **three more**, including
  `TrackRepository.getBookIdForTrack` doing `Timber.i("Track is $track")` — a full
  `MediaItemTrack.toString()` **on the 1 Hz progress path**. Fixed under cu-110.

The cost is real because `Audiobook.toString()` drags in the serialized `chapters` column: one
measured session produced **3.38 MB of logging across 2920 lines**, built and written on the main
thread.

`TokenLoggingTest` already proves the shape works — a static scan over `Timber` call sites that
fails the build. This is the same idea for *size* rather than secrecy.

## Acceptance Criteria

- [x] A test fails the build when a `Timber` call interpolates a bare collection of a model type
      (`List<Audiobook>`, `List<MediaItemTrack>`, `List<Chapter>`, `Collection<...>`)
- [x] `.map { it.id }`, `.size`, and `.count()` are permitted — the point is to force a projection,
      not to ban logging
- [x] Follows `TokenLoggingTest`'s structure, including its two self-guards: a file-count floor and
      a known-bad matcher case, so the scan cannot silently become vacuous
- [x] **Verified to fail** before being trusted (m-0 rule): reintroduce one of the six historical
      instances and confirm the build breaks
- [x] Existing violations are all fixed rather than baselined — there should be none left to
      grandfather

## Implementation Notes

`CollectionLoggingTest` (8 cases) scans `src/main/java` for a `Timber.x(...)` call that
interpolates a bare collection-shaped name, and fails the build naming file and identifier.

**The premise in the last criterion was wrong: there were eight live violations, not zero.** The
review that filed this task had found three; this scan found five more it had missed, including
`BookRepository:469` logging a whole `List<MediaItemTrack>` on the chapter-load path inside a
multi-line `Timber.i(` call. All eight are fixed as projections, none baselined:

| Site | Was | Now |
|---|---|---|
| `BookRepository:220,304` | `$mergedBooks` (`List<Audiobook>`) | `${mergedBooks.size}` |
| `BookRepository:469` | `$tracks` | `${tracks.map { it.id }}` |
| `BookRepository:487` | `$networkChapters` | `${networkChapters?.size ?: 0}` |
| `ChapterRepository:65` | `$networkChapters` | `${networkChapters?.size ?: 0}` |
| `ChooseLibraryViewModel:158` | `$tempLibraries` | `${tempLibraries.map { it.name }}` |
| `DownloadNotificationWorker:128` | `$bookDownloads` (a `Map`) | `${…mapValues { … .size }}` |
| `AudiobookDetailsViewModel:119` | `$activeDownloadIDs` | `${activeDownloadIDs?.size ?: 0}` |

### Three things worth keeping

**A `BuildConfig.DEBUG` guard does not prevent the `toString()`.** Two `networkChapters` sites
were wrapped in `if (BuildConfig.DEBUG)` with a comment claiming it "prevent[s] networkChapters
from toString()ing and being slow even if timber tree isn't attached". Kotlin builds the
interpolated string *before* calling `Timber`, so a debug build paid the full cost regardless —
the guard only helped release. Both are now bounded projections and the guard (plus two dead
`BuildConfig` imports) is gone, so the log is cheap *and* useful in both variants.

**The check keys on the name, not the type — deliberately.** Type-tracing was ruled out after
checking: the two worst offenders (`mergedBooks`, `networkChapters`) are declared with *inferred*
types, so nothing short of the compiler knows they are collections. The name heuristic catches the
entire known history and every plural in the tree.

**Plural units, not plurals, are the heuristic's real enemy.** A bare "ends in `s`" rule flagged
`currentTimeMillis`, `refreshRateMinutes`, `durationMillis`, `trueStartTimeOffsetMillis`,
`loadingStatus`, `diagnosis` and `copied.exists()` — because counted quantities all have plural
unit names, and they outnumber the collections. `SCALAR_SUFFIX` excludes them by *suffix*
(`Millis`, `Minutes`, `Bytes`, `status`, `diagnosis`, …) so `loadingStatus` is covered without
being listed; that is what stops the exclusion list growing a name per call site. `ACRONYM_PLURAL`
covers the opposite gap (`activeDownloadIDs` has no lowercase letter before its `s`), found by
noticing a real `Set<String>` slipping through.

The matcher also needed a lambda lookahead: `${bookDownloads.mapValues { … }}` parses as the name
`bookDownloads.mapValues`, whose leaf is plural, so **this task's own fix was flagged** by the
first cut. Both traps are pinned as test cases.

### Sabotage verification

Restored the cu-110 defect verbatim (`Timber.i("Loaded books: $mergedBooks")`) at one of the two
`BookRepository` sites; the suite failed with
`expected:<[]> but was:<[BookRepository.kt: mergedBooks]>`. Restored, green again. The two
self-guards from `TokenLoggingTest` are carried over (a >100 file floor, and a known-bad matcher
case), plus four more pinning the permitted projections and the scalar exclusions.

### Verification

`./verify.sh` green, all 6 stages. 762 unit tests, 0 failures (CLAUDE.md's "654" was stale and is
corrected). Coverage 31.73% → 31.74%, ratcheted up — from removing the two `BuildConfig.DEBUG`
branches, not from new production code.

### Follow-up, not filed as a task

`AudiobookMediaSessionCallback:155,163` log `"Track manager is $trackListStateManager"`.
`TrackListStateManager` has no `toString()` override, so this prints
`TrackListStateManager@1a2b3c` — cheap, and useless. Not a size defect, so out of this task's
scope and left alone rather than filed; noted here so the next reader knows it was seen and
judged, not missed.
