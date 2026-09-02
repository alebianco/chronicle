---
id: cu-115
title: Add a multi-track test fixture
status: Done
assignee: []
created_date: '2026-09-02'
labels: [R1, testing, bug]
dependencies: []
priority: high
milestone: m-1
---

## Description

Found in the 2026-09-02 branch review, and it is the **highest-leverage single testing change
available**: every test fixture in the suite is a single-track book, and on a single-track book the
two chapter coordinate frames coincide. So an entire class of live bugs is structurally invisible.

`Chapter.bookStartTimeOffset`'s KDoc already lists **four** prior occurrences of the frame being
guessed wrong (cu-13, cu-49, cu-93, cu-96). The review found more still live, all only reachable on
a genuinely multi-track book:

| Site | Defect | Effect |
|---|---|---|
| `MainActivityViewModel.kt:130` | in-track offset compared against book-absolute chapter offsets, no fallback | mini player shows an empty chapter title on any track but the first |
| `CurrentlyPlayingViewModel.kt:241,256` | `track.progress - chapter.bookStartTimeOffset` → large negative | garbage chapter elapsed time and slider position |
| `CurrentlyPlayingViewModel.kt:594` | same, deciding previous-chapter vs restart | the cu-96 bug, unfixed in the ViewModel's mirror copy |
| `CurrentlyPlayingViewModel.kt:979` | book-absolute offset sent to `onSeekTo`, which treats it as in-track | dragging the chapter slider jumps to the end of the current track |
| `CurrentlyPlayingViewModel.kt:700` | `currentBookPosition` written into a *track's* progress column | position reported past the end of the book |
| `LibrarySyncRepository.kt:48` | `getTrackStartTime` uses `subList` on an **unsorted** receiver | book progress derived from arbitrary SQLite row order |

cu-93 and cu-96 both already carry "reproduce with a multi-track book" as an *open* criterion, so
this unblocks them as well.

The durable fix for the class is to make the frame part of the type (an inline
`value class BookOffset` / `TrackOffset`), so the compiler rejects the mix-up rather than a test
catching it after the fact. That is a bigger change; the fixture is the cheap first step that makes
the bugs visible at all, and should come first.

## Acceptance Criteria

- [x] `testing/MultiTrackBook` — 3 tracks x 10 min, 6 chapters x 5 min, so chapter boundaries fall
      both *inside* tracks (300k/900k/1500k) and *exactly on* track boundaries (600k/1200k)
- [x] Done for the three fixed rows; the remaining two are re-scoped rather than silently dropped
      — see Implementation Notes
- [x] **Both.** `getTrackStartTime` now sorts, and `getAllTracksAsync` carries an `ORDER BY`.
      Reproduced first: book position read **1,350,000 instead of 750,000** from row order alone
- [x] Chapter display (`MultiTrackChapterTest`, 9 cases) and chapter seek conversion
      (`MultiTrackSeekConversionTest`, 8 cases incl. a round-trip invariant over every chapter)
- [x] cu-93 and cu-96's open "multi-track" criteria — the *unit* half is covered here, and the device half is now **possible**: a real 3-track book with boundary-crossing chapters
      plays on a device. Itemised on [[cu-73]] for the owner's library; the fixture no longer blocks it.
- [x] Filed as [[DRAFT-116]]

## Implementation Notes

### The fixture

`app/src/test/.../testing/MultiTrackBook.kt`: 3 tracks x 10 min, 6 chapters x 5 min. The numbers
are chosen so a frame confusion cannot pass unnoticed — chapter starts at 300k/900k/1500k fall
*inside* a track, and 600k/1200k fall *exactly on* a track boundary. Equal track durations are
deliberate: a wrong `getTrackStartTime` then produces a *plausible* multiple of 600k rather than an
obviously broken number, which is the property that let these bugs survive.

The reference position, 750_000, is 2m30s into track 2 and inside chapter 3 — so the in-track
offset (150_000) and the book offset (750_000) differ by a full track. Any site passing one where
the other belongs is out by 600_000 ms.

### Fixed, each reproduced first

**1. `getTrackStartTime` summed the receiver's own order.** It used
`subList(0, indexOf(track))`, correct only if the caller passes an ordered list.
`LibrarySyncRepository` does not — it derives every book's position from `getAllTracksAsync()`,
whose query had **no `ORDER BY`**. Measured before the fix: **book position 1,350,000 instead of
750,000**, i.e. the listener reported 10 minutes ahead of where they were, from SQLite row order
alone. It now sorts, matching `getActiveTrack`, *and* the query is ordered (the new cu-110 index
makes the ordering free). Belt and braces because the function is called from nine places.

**2. The mini player showed no chapter title on any track but the first.**
`MainActivityViewModel.currentChapterTitle` passed `activeTrack.progress` — an in-track offset —
into a lookup comparing against book-absolute chapter offsets, having first filtered the chapters
to the active track. Below every one of that track's chapter starts, so nothing matched. Now
`chapters.chapterAtBookProgress(tracks.getProgress())`, the book-frame lookup that
`CurrentlyPlayingSingleton` already fell back to.

**3. Three sites in `CurrentlyPlayingViewModel` did `track.progress - chapter.bookStartTimeOffset`**
— in-track minus book-absolute, a large negative on any later track. Fixed by publishing
`CurrentlyPlaying.bookPosition` as a `StateFlow<Long>`, so the book-frame value has one owner
instead of each consumer re-deriving it from data it does not have. `chapterProgress`,
`chapterProgressForSlider` and the previous-chapter threshold now read it.

**4. The chapter slider sent a book offset to `transportControls.seekTo`,** which takes a position
*within the current media item*. Media3 clamps rather than throwing, so it presented as the thumb
jumping to the end of the current track. Now converted the same way the jump-to-chapter path (cu-96)
already did. `awaitSeek` correctly keeps taking the in-track value — it compares against
`track.progress`.

### Re-scoped, not dropped

Two rows from the review's table are **not** fixed here:

- **`CurrentlyPlayingViewModel:700`** writes `currentBookPosition` into a *track's* progress column
  in the service-is-dead branch of `seekRelative`. Fixing it properly means deciding what that
  branch should do at all, which is a behaviour question rather than an arithmetic one.
- The **duplicated conversion arithmetic**: `chapterSeekTarget` exists and is correct, but the same
  logic is now inlined at two `CurrentlyPlayingViewModel` sites. `MultiTrackSeekConversionTest`
  pins the *rule* so all three agree, and a consolidation has something to check itself against.

Both belong with [[DRAFT-116]], which proposes making the frame part of the type
(`value class BookOffset` / `TrackOffset`) so the compiler rejects the mix-up instead of a test
catching it afterwards. That is the durable fix for the class; this task made the class *visible*,
which was its point.

### Verification

`./verify.sh` green, 7 stages. 649 unit tests (was 625). New: `MultiTrackPositionTest` (7),
`MultiTrackChapterTest` (9), `MultiTrackSeekConversionTest` (8). The seek round-trip test asserts
the invariant over *every* chapter rather than sampling, so it holds a future refactor to the rule.

On device (Phh-Treble GSI, API 32, mock server): library renders, chapter title "An Unexpected
Party" displays, no crash. The mock fixture is single- and few-track, so it does not exercise the
multi-track paths — that remains a [[cu-73]] item.

### Addendum (cu-73 fourth sweep): the consumers are covered now too

The audit that followed this task found the fixture pinned the **helpers** while the *ViewModel
consumers* stayed untested — and that `chapterProgress`/`chapterProgressForSlider` had **no test at
any track count**, making them the weakest of the five seams. Worse, the `coerceAtLeast(0L)` added
with the fix means a frame regression now shows as a stuck `0:00` rather than a negative, so
neither the suite nor a human watching the screen would notice.

Three tests added to `CurrentlyPlayingViewModelTest` against `MultiTrackBook`: chapter progress is
measured in the book frame (750_000 in a chapter starting at 600_000 gives 150_000), the slider
agrees with the readout, and the value is never negative even when the frames disagree.
**Sabotage-verified** — restoring `track.progress - chapter.bookStartTimeOffset` fails 2 of the 6.

Two harness traps worth recording, because each fails *silently* as a `null` that reads exactly
like broken arithmetic:

- `asLiveData` does not collect its flow until something observes it, so `.value` is `null` before
  `observeForever`.
- `MainDispatcherRule` installs a `StandardTestDispatcher`, which **queues** rather than runs — so
  the `combine` produces nothing until the scheduler is advanced. The tests need
  `runTest(mainDispatcherRule.testDispatcher)` plus `advanceUntilIdle()`.

Both are now handled by an `observedValue` helper with the reasoning at its declaration, so the
next ViewModel flow test does not rediscover them.

### Device half: partly unblocked now (2026-09-02)

The 5-second audio fixture was the blocker, not the hardware. It is now **180 s**, so playback
sustains and book 1001 is a genuine **3-track** book (2001/2002/2003, 180 s each) that can be
played through a track boundary on a device.

What is still missing for the device checks: `track-with-chapters.json` serves chapters for
**track 2001 only**, so no chapter *crosses* a track boundary in the mock pack. The unit fixture
(`testing/MultiTrackBook`) has that shape deliberately; the mock does not. Until it does, the
device can verify "playback crosses a track boundary" but not "a chapter spans one", which is the
case the coordinate-frame bugs actually live in.

Extending it is small and bounded: give tracks 2002 and 2003 chapter arrays whose
`startTimeOffset`/`endTimeOffset` are **book-absolute** and straddle the 180000/360000 boundaries —
the same layout `MultiTrackBook` uses. `PlexFixtureContractTest` will need its chapter assertions
updated with it. Worth doing as part of [[DRAFT-117]], which needs sustained multi-track playback
on a device anyway.

## Closed (2026-09-02): the mock fixture now crosses track boundaries

The last gap was that the mock served chapters for track 2001 only, so nothing crossed a boundary
and the coordinate-frame bug class stayed unreachable on a device.

`track-with-chapters.json` now carries **all three tracks**, with chapters of **75 s** over a
3 x 180 s book. 75 deliberately does not divide 180, so:

| chapter | span | crosses |
|---|---|---|
| 3 | 150000-225000 | **boundary at 180000** |
| 5 | 300000-375000 | **boundary at 360000** |

Chapters 3 and 5 are reported on **both** tracks they overlap, with offsets absolute within the
book — which is what a real Plex `includeChapters` response looks like, and what
`assembleChapters` expects (it uses server-reported chapters as-is; `trackStartOffset` only feeds
the no-chapters fallback). The final chapter keeps an empty `tag` so the untitled-index fallback
stays covered.

`PlexFixtureContractTest` gained a `chapters cross track boundaries with book-absolute offsets`
case that asserts the crossing *and* that it is a real crossing ("or the fixture proves nothing").
**Sabotage-verified**: realigning the chapters to 60 s so they land exactly on track boundaries
fails 3 tests.

### Verified on a device

Phh-Treble GSI, API 32, mock server: the 3-track book plays, chapters are fetched per track
(`Network chapters: [PlexChapter(id=4001, ...)]`), and the chapter title renders
("An Unexpected Party"). `uiautomator dump` succeeds during playback.

### It immediately earned its keep

The richer fixture **exposed that cu-110 was not actually fixed**. Against the old 3-chapter
single-track fixture the main thread read 1 jiffy/6 s; against this one it went back to 431
jiffies/12 s, and profiling found the real dominant cause (a constraint-graph rebuild per row per
second). That is the argument for this task in one line: *a performance fix verified against the
easy input is not verified.*
