---
id: cu-136
title: Make the offset frame part of the type
status: Done
assignee:
  - '@claude'
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - architecture
  - bug
milestone: m-2
dependencies:
  - cu-115
priority: medium
---

## Description

Filed from [[cu-115]]. Six bugs and counting have come from the same mistake: a value measured from
the start of a **track** used where one measured from the start of the **book** belongs, or the
reverse. Both are `Long`, so the compiler cannot help, and on a single-track book — which is the
owner's whole library — the two are the same number, so it works by accident.

The occurrences so far, by task: cu-13, cu-49, cu-93, cu-96, then four more found in the 2026-09-02
review and fixed in cu-115. `Chapter.bookStartTimeOffset`'s KDoc already documents the frame in
prose and renames the field to say so; it has not been enough.

## Proposal

Introduce two inline value classes:

```kotlin
@JvmInline value class BookOffset(val millis: Long)
@JvmInline value class TrackOffset(val millis: Long)
```

and give the conversion one home (`chapterSeekTarget` already is that home, it is just bypassed).
`Chapter.bookStartTimeOffset` becomes `BookOffset`; `MediaItemTrack.progress` becomes
`TrackOffset`; `Player.seekTo` takes a `TrackOffset`; `chapterAtBookProgress` takes a `BookOffset`.
A mix-up then fails to compile.

Inline value classes cost nothing at runtime, which matters because these are read on the 1 Hz
progress path.

## Also in scope

Two items deliberately left open by cu-115, both instances of the same class:

- **`CurrentlyPlayingViewModel:700`** writes `currentBookPosition` into a *track's* progress column
  in the service-is-dead branch of `seekRelative`. It needs a decision about what that branch
  should do, not just corrected arithmetic.
- **The duplicated conversion.** `chapterSeekTarget` is correct and bypassed; the same arithmetic is
  inlined at two `CurrentlyPlayingViewModel` sites. `MultiTrackSeekConversionTest` pins the rule so
  a consolidation has something to check itself against.

## Acceptance Criteria

- [x] `BookOffset` and `TrackOffset` exist as inline value classes, with the frame documented once
      on the types rather than repeated at call sites
- [x] Room `TypeConverter`s so the DB columns are unchanged (no migration)
- [x] Every conversion goes through one function; no inlined `- trackStart` arithmetic remains
- [x] `CurrentlyPlayingViewModel:700` resolved, with its intended behaviour stated in the task
- [x] The `MultiTrackBook` tests still pass unchanged — they encode the rule, so a refactor that
      breaks them broke the rule
- [x] A deliberate mix-up (passing a `TrackOffset` where a `BookOffset` belongs) **fails to
      compile** — demonstrated, per the m-0 rule that a new check must be verified to fail

## Two more instances, found auditing cu-73 (2026-09-02)

Both verified by reading the code; neither is on any checklist.

**`getTrackProgressInAudiobook` (`MediaItemTrack.kt:228`) still has the unsorted pattern.**
`this.subList(0, indexOf(track))` — exactly what cu-115 removed from `getTrackStartTime` for
corrupting book position. It is currently **dead code** (grep: zero callers in `main`), so it is a
trap rather than a live bug: the next caller inherits the fixed bug. Either delete it or fix it to
sort; leaving a broken twin of a corrected function next to it is the worst option.

**`TrackListStateManager.seekToActiveTrack` (`:60-62`) mixes sorted and unsorted indices.**
`getActiveTrack()` sorts internally, then `trackList.indexOf(activeTrack)` indexes the *unsorted*
`trackList`. `currentTrackIndex` then feeds `seekTo(trackIndex, position)` at `PlayerExt.kt:36`,
which is a **media-item index into the player's playlist**.

It is safe *today*, and only by the caller's grace: both callers assign a DAO-ordered list
(`AudiobookMediaSessionCallback.kt:374` uses the same `tracks` for `trackList` *and*
`buildPlaylist`, and `CurrentlyPlayingViewModel.kt:705` uses `_tracks`), and
`getTracksForAudiobookAsync` is `ORDER BY discNumber, index`. So the indices agree by convention,
not by construction — the same shape as the bug cu-115 fixed, one caller away from biting.

A `TrackIndex` value class alongside `BookOffset`/`TrackOffset` would close this properly: the
distinction that matters is *index into what*, and it is currently untyped.

## Implementation Notes

`BookOffset`, `TrackOffset` and `TrackIndex` are `@JvmInline` value classes in
`data/model/Offsets.kt`, with `OffsetConverters` for Room. **No migration**: the exported
`ChapterDatabase/3.json` is byte-identical before and after, `INTEGER` affinity and the same
`createSql`, verified by diffing it.

Retyping `Chapter.bookStartTimeOffset` alone produced **26 compile errors**, and reading them was
the whole value of the task — every one sat on a real frame boundary. The final surface:

| Retyped | Frame |
|---|---|
| `Chapter.bookStartTimeOffset` / `bookEndTimeOffset` | `BookOffset` |
| `List<MediaItemTrack>.getProgress()` | returns `BookOffset` (the canonical track → book sum) |
| `CurrentlyPlaying.bookPosition` | `StateFlow<BookOffset>` |
| `getChapterAt` / `chapterAtBookProgress` / `millisIntoChapter` | take `BookOffset` |
| `MediaItemTrack.asChapter` | takes `BookOffset` |
| `ChapterSeekTarget` | `TrackIndex` + `TrackOffset` |
| `TrackListStateManager.currentTrackIndex` / `currentTrackProgress` / `currentBookPosition` | `TrackIndex` / `TrackOffset` / `BookOffset` |
| `CurrentlyPlayingViewModel.awaitSeek` | takes `TrackOffset` (it waits on `track.progress`) |

`Audiobook.progress` and `ProgressUpdater` deliberately stay `Long`, converting at the boundary.
Checked rather than assumed: `ProgressUpdater` already keeps `trackProgress` and `bookProgress` as
separate named locals with a correct derivation, so names are doing the work there and a retype
would have been 48 more call sites of churn for no guard.

### Three live bugs the retype surfaced

**1. `CurrentlyPlayingSingleton:126` passed a track offset to a book-frame lookup.**
`getChapterAt(track.id, track.progress)` — and `getChapterAt` compares against
`bookStartTimeOffset`. On any multi-track book it matched nothing, so the cu-87 fallback
(`chapterAtBookProgress`) silently did all the work; on a single-track book the frames coincide so
it appeared to work. `MultiTrackChapterTest` had *already pinned both halves of this ambiguity* as
correct behaviour and nobody read it as a bug. Fixed, the first lookup becomes the fallback plus a
redundant track-id filter over the same position, so the two collapse into the one that was always
right.

**2. `CurrentlyPlayingViewModel:700` wrote a book position into a track column.** As predicted in
the plan: `updateTrackProgress(manager.currentBookPosition, …)` where the column is
`MediaItemTrack.progress`. Now writes `currentTrackProgress`. Note the follow-on —
`currentBookPosition` has **no production caller left**; kept, with that stated on it, because the
two frames side by side are what the new test pins.

**3. Three copies of the conversion, all with the same latent bug.**
`CurrentlyPlayingViewModel.jumpToChapter`, `CurrentlyPlayingViewModel.seekTo` and
`AudiobookDetailsViewModel.jumpToChapter` each inlined
`tracks.takeWhile { it.id != trackId }.sumOf { it.duration }`. `takeWhile` stops at the first
non-match, so an **absent** id sums *every* track and returns a large offset instead of admitting
it could not resolve — where `chapterSeekTarget` returns null. All three now call one
`inTrackOffsetOf`, which `chapterSeekTarget` also delegates to, so they cannot drift apart.

### Also in scope, done

- **`getTrackProgressInAudiobook` deleted.** Dead (zero callers anywhere, tests included) and
  carrying the unsorted `subList(0, indexOf(track))` pattern cu-115 removed from its sibling. The
  task called leaving a broken twin the worst option; `getProgress()` already does this correctly.
- **`TrackListStateManager.seekToActiveTrack` index mix-up closed.** `getActiveTrack()` sorts
  internally and the result was looked up in the *unsorted* `trackList`. `TrackIndex` names the
  distinction and the manager now sorts once on assignment. `PlayerExt.skipToPrevious` had the
  same unsorted `take(currentMediaItemIndex)` derivation; also sorted now.

### The compile-fail demonstration

A throwaway file passing the wrong frame at five sites — one per historical bug shape — produced
five errors and no successful build:

```
MixUp.kt:11 chapterAtBookProgress(trackOffset)   TrackOffset where BookOffset expected  (cu-13)
MixUp.kt:13 millisIntoChapter(ch, trackOffset)   TrackOffset where BookOffset expected  (cu-115)
MixUp.kt:15 inTrackOffsetOf(trackOffset, …)      TrackOffset where BookOffset expected
MixUp.kt:21 bookOffset - TrackOffset(…)          cross-frame subtraction                (cu-96)
MixUp.kt:26 chapterAtBookProgress(750_000L)      a bare Long, the state before this task
```

### Two problems found by self-review

- **A sort on the 1 Hz path.** `sortedTracks` started as a getter calling `trackList.sorted()`,
  read by `currentTrackPosition`/`currentTrack` — precisely the per-tick work whose result cannot
  change that cu-110 was about. Now sorted once in `trackList`'s setter.
- **A double sort per seek.** `chapterSeekTarget` sorted, then called `inTrackOffsetOf` which
  sorted again. Split into a public entry point that sorts and a private one that takes an ordered
  list.

### An unrelated release-gate failure, fixed

`./test_release_build.sh` failed with four "Moshi model missing from dex" errors for
`BooleanSetting`/`LongSetting`/`FloatSetting`/`StringSetting`. **Not a cu-136 regression** —
reproduced on the base branch, introduced by cu-133. The check treated *every* `data class` in any
file containing `@JsonClass` as a Moshi model, and cu-133 added four `internal` sealed-interface
members to `SettingsBackup.kt` that never touch JSON, so R8 rightly inlined them. Adding keep rules
would have exempted correctly-optimised code from R8, the opposite of cu-45's narrow-keeps rule.
The check now pairs `@JsonClass` with the `data class` on the following line. Still covers 16 real
models, and sabotage-verified: injecting a class name absent from the dex fails it.

### Verification

- `./verify.sh` green, 7 stages (ktlintFormat + the 6 gates). **773 unit tests, 0 failures.**
- 28 test failures during the migration, **every one an assertion comparing a raw `Long` to a
  wrapped value with the numbers already correct** — the `MultiTrackBook` expectations are
  unchanged, which is criterion 5 met rather than worked around.
- Coverage 31.74% → 31.96%; `features/player` 33.69% → 34.30% (cu-135's new per-package gate
  reporting the rise).
- `./test_release_build.sh`: R8 verification passes, all reflection-dependent classes survive.
  Install fails only because the APK is unsigned (release signing is owner-only).
- **On device** (tablet, mock Plex mode, the 3-track/7-chapter fixture whose chapters *cross track
  boundaries*): the book played from 0 through **both** track boundaries to completion, chapter
  tracking correct at each crossing — track 2002 at 23 455 ms in-track = book 203 455, reported as
  Chapter 3 (150 000–225 000); track 2003 at 12 905 ms = book 372 905, in Chapter 5. No crash. This
  is exactly the scenario bug 1 above broke, so it is the case worth having driven rather than the
  single-track fixture that would have passed either way.

### Not done, deliberately

The task's closing note suggests `TrackIndex` "alongside" the offsets, which is done. It does not
ask for `Audiobook.progress` to be retyped and this did not do so — see the reasoning above; if a
future frame bug appears there, that is the moment to reconsider, not now.
