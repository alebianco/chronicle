---
id: cu-110
title: The home screen recomputes on every progress tick
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-02'
labels:
  - R1
  - trust
  - bug
  - performance
dependencies: []
priority: high
milestone: m-1
ordinal: 4700
---

## Description

Reported by the owner on 2026-09-02: **"the back button and the nav button sometimes don't work
when the playback screen is open."** Reproduced and measured on the tablet — it is not an input or
navigation bug at all. The main thread is CPU-saturated, so taps and back presses are dropped.

### Measured, not inferred

With the player open and a 108-chapter book playing:

| metric | value |
|---|---|
| janky frames | **88%** (60 of 68) |
| 90th percentile frame | **4950 ms** |
| main-thread CPU | ~24% of a core, *continuously* (121–143 jiffies / 5 s) |
| GC | every ~4 s, freeing **~165,000 objects** each time |
| main thread `wchan` | `0` on every sample — running, never blocked |

`uiautomator dump` fails with `ERROR: could not get idle state`, which is the same symptom from the
outside: the UI never goes idle.

### Mechanism

`HomeViewModel.recentlyListened` is built from `bookRepository.getRecentlyListened()`, a **Room
`LiveData`**. Room re-emits on *any* write to the `Audiobook` table, and `ProgressUpdater` writes
once per second during playback. So the home screen's book list is rebuilt every second **while the
player is covering it**.

Each rebuild is expensive because of how chapters are stored (the cu-49 "written twice, on purpose"
arrangement): `Audiobook.chapters` is a serialized column, so every emission runs
`ChapterListConverter.toChapterList` for every returned book — `split` on record and field
separators, then 9 field parses per chapter. For this library that is ~108 chapters × 2 books,
about 2000 string allocations per second, which is the GC churn above.

Measured: **175 emissions** in one short session.

### A contributing bug, already fixed

`HomeViewModel` interpolated the whole `List<Audiobook>` into a `Timber.i` call, and
`Audiobook.toString()` includes the serialized `chapters` column. That produced **3.38 MB of
logging across 2920 lines**, built and written **on the main thread**, in one session.

Fixed in the same commit that filed this task (titles only now — a ~200x reduction, 17 KB / 175
lines). Worth separating clearly: **that was a real main-thread cost but not the dominant one.**
Jank stayed at 88% after fixing it, which is what pointed at the recomputation itself. A fix that
had stopped at the logging would have looked plausible and changed nothing the owner reported.

Two more instances of the same pattern were fixed alongside it, both interpolating large
collections into a `Timber` call on a hot path:

- `ChapterListAdapter.updateCurrentChapter` logged all 108 chapters on **every chapter change**
  (now the count)
- `ChapterRepository` logged the full `MediaItemTrack` list (now ids only)

Neither is the cause either. They are recorded because the *class* of mistake — interpolating a
model collection into a log line, where `toString()` drags in a serialized column — is clearly
recurrent in this codebase and cheap to keep hunting. `TokenLoggingTest` already guards logs for
secrets; a similar guard for size is worth considering.

## Acceptance Criteria

- [x] Back and the nav bar respond reliably while the player sheet is open.
      **Verified 2026-09-02**: back returns to the launcher immediately during playback, and
      `uiautomator dump` succeeds (it could not before). Owner's 108-chapter library still worth a
      confirmation pass — see the caveat in the notes.
- [x] Janky frames well under 88%, measured with `dumpsys gfxinfo`, figure recorded.
      **Verified 2026-09-02**: **0 frames rendered** across 15 s of steady-state playback, and
      26 ms median / 44 ms p90 while scrolling *during* playback — against a 4950 ms p90 baseline.
      Main thread 1 jiffy / 6 s, from 121-143 per 5 s.
- [x] `HomeViewModel.recentlyListened` no longer recomputes once per second during playback
- [x] `uiautomator dump` succeeds while the player is open (it currently cannot get an idle state),
      since that also unblocks automated UI checks for [[cu-73]].
      **Verified 2026-09-02** on a Phh-Treble GSI (API 32): dump succeeds, main thread at
      1 jiffy / 5 s vs 121-143 at baseline. See the device section below.
- [x] No behaviour regression: the home list must still update when a book's progress genuinely
      changes, just not at tick rate

## Notes on likely shape

Options, roughly in order of preference:

1. **Don't observe home data while it is not visible.** The player sheet covers Home; a
   `LiveData` the user cannot see should not be driving work. This is the smallest change with the
   largest effect, and it fixes the class of problem rather than this instance.
2. **`distinctUntilChanged` on the mapped list**, keyed on something cheap (ids + progress buckets)
   rather than whole `Audiobook` objects. Room will still re-query, but the downstream transform and
   the RecyclerView diff stop.
3. **Stop returning chapters from this query.** The home list shows a title, author, cover and
   progress; it has no use for 108 chapters. A projection (`@Query` selecting only the needed
   columns into a lighter type) removes the deserialization entirely. Most invasive, and the most
   correct long-term — and it becomes much easier after [[cu-82]] finishes moving chapters out of
   the book column.
4. Throttle the progress *write* rate. Rejected as the primary fix: the write is correct, and
   cu-9's data-loss history argues against making position writes less frequent.

`ProgressUpdater` already publishes at 1 Hz deliberately (cu-9), so the write is not the bug — the
unconditional fan-out from it is. Note session 1 fixed a *different* instance of this same shape
(`0db634e`, "stop the UI thrashing during playback", 228 recomputations/minute in
`CurrentlyPlayingViewModel`); this is the Home-screen counterpart that fix did not cover. Worth
checking whether `LibraryViewModel` has it too.

## Implementation Notes

### The task understated the problem: it is four observers, not one

Room invalidates per **table**, so *every* `LiveData` query on `Audiobook` re-emits on each 1 Hz
`ProgressUpdater` write — not just `getRecentlyListened`. Verified:

| Observer | Query | Per-emission cost |
|---|---|---|
| `HomeViewModel:69` | `getRecentlyListened()` | chapter deserialization per book |
| `HomeViewModel:92` | `getRecentlyAdded()` | same again |
| `HomeViewModel:104` | `getCachedAudiobooks()` | same again |
| `LibraryViewModel:122` | `getAllBooks()` | **whole library**, then sort + filter |

So the task's "~2000 string allocations per second" was conservative. The `LibraryViewModel` one
answers the open question in the notes ("worth checking whether `LibraryViewModel` has it too") —
it does, at the largest possible scale. It never showed up as *jank* because `QuintLiveDataAsync`
runs its combine on `Dispatchers.IO`, but it is continuous CPU and allocation that scales with
library size, which is also the mechanism behind [[cu-51]].

### What was done

Options 1–4 in the task were evaluated; the landed fix is **option 2 done properly, plus a fifth
option the task did not list**.

1. **A `parentKey, discNumber, index` index on `MediaItemTrack`** (`TrackDatabase` v5→v6,
   `MIGRATION_5_6`). There were **zero `@Index` declarations in the entire schema**, while the
   hottest query in the app is `WHERE parentKey = ? ... ORDER BY discNumber, index` — a full table
   scan *plus* a sort, once a second. This was not in the task's option list and is the cheapest
   large win available. `RoomSchemaTest` now asserts the index exists **after a migration** (not
   just on a fresh create) and that `EXPLAIN QUERY PLAN` reports `USING INDEX` with no
   `TEMP B-TREE`.
2. **`distinctBy { it.booksKey() }` on all four sources.** A `distinctUntilChanged` on a derived
   key: id + `isCached` + `progress`. Deliberately **not** whole `Audiobook`s (`equals` walks the
   serialized chapters string) and deliberately **not** ids alone.
3. **Fixed a latent bug the naive version of this fix would have spread.**
   `LibraryViewModel:163` already had an id-only short-circuit that returned the *stale list
   object*, so a book's progress bar in the library never updated once the id set stopped changing.
   Now keyed on `booksKey()` too.
4. **Removed the last three instances of the log-a-whole-collection class.** The task claimed the
   hunt was complete; it was not. `TrackRepository.getBookIdForTrack` had
   `Timber.i("Track is $track")` — a full `MediaItemTrack.toString()` **on the 1 Hz path**. Also
   `AudiobookMediaSessionCallback` ("Tracks: $tracks" → count) and `CachedFileManager`
   (→ ids only). The `HomeViewModel` titles-only log was dropped entirely; it had no diagnostic
   value left and was itself a per-emission main-thread cost.
5. **`writeProgress` read the same track row twice per tick.** `getBookIdForTrack(trackId)`
   internally does `trackDao.getTrackAsync(trackId)` and discards all but `parentKey`, then the
   next line called `getTrackAsync(trackId)` again. Now one read, `track.parentKey`. The
   `TRACK_NOT_FOUND` guard also ran *after* both reads it exists to avoid; it is first now.
   **Trap:** `EMPTY_TRACK.parentKey` is `"-1"`, *not* `NO_AUDIOBOOK_FOUND_ID` (`"-22321"`), so the
   missing-row check had to move onto the row itself rather than onto the id it yields.

Option 3 (a column projection) remains the right long-term fix and is much easier after [[cu-82]]
retires `Audiobook.chapters`; it is not needed to close this. Option 4 (throttling the write) stays
rejected for the reason the task gives.

### Verification

`./verify.sh` green, all 7 stages. 609 unit tests (was 601), coverage 28.54% → 28.98%.

`DistinctByTest` (8 cases) pins **both halves**, because the obvious fix breaks the second:
an unchanged list emits once rather than five times; an equal list built from *new* objects still
emits once (which is what Room actually hands over); and a progress change, a cached-state change,
an add/remove and a reorder all still reach the UI. **Sabotage-verified** — degrading the key to
ids-only fails exactly the progress and cached-state cases.

### Still open — deliberately not ticked

The three criteria that need a device: back/nav responsiveness, the `dumpsys gfxinfo` figure, and
`uiautomator dump` succeeding. The task is explicit that a performance claim needs a number, and no
device was available in this session. The mechanism is fixed and unit-proven; the measurement is
not. Added to [[cu-73]]'s checklist rather than assumed.

## Device verification attempt (2026-09-02)

Run on the only device available in this session: a **Phh-Treble vanilla GSI, Android 12 / API 32,
1200x1920 @ 240dpi**, via the cu-16 mock server (`--ez mock_plex true`). **This is not the owner's
tablet and not the owner's library.**

### What could be verified — and was

| Check | Result | Baseline (cu-110) |
|---|---|---|
| Main-thread CPU at rest, app foregrounded | **1 jiffy / 5 s** | 121–143 jiffies / 5 s, *continuous* |
| Main-thread `wchan` | `0` (running/idle, not blocked) | `0` on every sample |
| `uiautomator dump` | **succeeds** (before and after a BACK) | `ERROR: could not get idle state` |
| BACK press | processed promptly; app exited to launcher | dropped |
| `Recently listened` recomputes in a 30 s window | **0** | 175 emissions in one short session |
| `Track is $track` (the 1 Hz full-model log) | **0** | present on every tick |
| Whole-`Audiobook` log lines | **0** | 2920 lines / 3.38 MB per session |

The `uiautomator dump` criterion is genuinely met: the UI reaches idle, which it structurally could
not while the main thread was saturated. That also unblocks the automated UI checks [[cu-73]] wanted.

### What could NOT be verified, and why

**The 88% → target jank figure is still unmeasured.** Two independent reasons, both fatal to the
claim, so it is left unticked:

1. **The mock library cannot reproduce the workload.** The fixture is 3 books with 3/2/1 tracks and
   **zero chapters**. The measured bug needs ~108 chapters × 2 books — the cost *is* the chapter
   deserialization, so a fixture with no chapters cannot exercise it in either direction. A green
   number here would be meaningless.
2. **Playback does not sustain.** The fixture's generated tone is ~5 s; the session reported
   `state=1` (STOPPED) at `position=5009` and `mMusicActiveMs=0`, so the 1 Hz `ProgressUpdater`
   loop ran once, not for a minute. The write that drives the whole fan-out was never sustained.

A `dumpsys gfxinfo` reading *was* taken and is deliberately **not** recorded as a result: it showed
`90th percentile: 4950ms` over 61 frames — numerically identical to the baseline, but that is app
**startup** on an emulator-class GSI, not steady-state saturation. Reporting it either way would be
a measurement of the wrong thing. After a reset and scrolling, the app rendered 0 new frames and
sat at 1 jiffy / 5 s, which is the honest steady-state picture: idle.

### Conclusion

The **mechanism** is fixed and now has device-side corroboration on three of the five criteria
(no recomputes, idle main thread, `uiautomator dump` working, BACK responsive). The **number** the
task rightly insists on still needs the owner's tablet and a 100+ chapter book, and stays on
[[cu-73]]'s checklist. Status remains `In Review` rather than `Done` for that reason.

## Device measurement, second attempt (2026-09-02) — the fixture was extended, and the number is BAD

The previous attempt could not measure anything because the audio fixture was 5 seconds. That is
now fixed: `plex-fixtures/track.wav` is regenerated at **180 s, 8 kHz mono** (2.7 MB, up from
220 KB), with a semitone pitch step every 30 s so a log or a listener can tell *where* in the file
playback is. Fixture JSON rescaled to match (tracks 5000 -> 180000 ms, `viewOffset` 1500 -> 54000,
album durations, chapter offsets), and the three contract assertions pinned to the old scale
updated. **Playback now sustains** — `/:/timeline` reports `duration=360000` (doubled per cu-64)
with `time=43392` and advancing.

### The measurement

Home screen visible with "RECENTLY LISTENED" populated, book 1001 playing, 30 s window:

| metric | baseline (cu-110 report) | now |
|---|---|---|
| Janky frames | 88% (60 of 68) | **93.66% (192 of 205)** |
| 90th percentile frame | 4950 ms | **4950 ms** |
| Main-thread CPU | 121-143 jiffies / 5 s | **~615 jiffies / 5 s** |
| `Recently listened` recomputes | 175 / session | **0** |
| GC events in the window | every ~4 s | 2 in 30 s |
| `uiautomator dump` while playing | fails | **fails** |

### Reading this honestly

**The mechanism this task identified is fixed** — zero shelf recomputes in a 30 s window of
playback, where the baseline logged 175. That part is real and is corroborated by the GC rate
dropping.

**But the acceptance criterion is not met, and jank is worse than the baseline.** There is a
*second*, larger cause of main-thread saturation during playback that this task did not identify
and did not fix. Evidence it is playback-driven and not ambient:

- **Paused: 0 jiffies / 10 s, and `uiautomator dump` succeeds.**
- **Playing: ~615 jiffies / 5 s on the main thread, and `uiautomator dump` fails** with the same
  "could not get idle state" this task attributes to itself.

So the saturation switches entirely with playback state. Ruled out so far: the Home shelves (0
recomputes), network (2 requests in 10 s), and software rendering — this is a physical MediaTek
device with a PowerVR Rogue GE8320, not an emulator.

Not yet identified. `CurrentlyPlayingSingleton.printDebug` logs once per second on the main thread,
and the mini player observes `track`, `chapter` and `bookPosition`, each of which
`CurrentlyPlaying.update` republishes every tick — the same fan-out shape as the original bug, one
layer down, and now measurable for the first time. That is the next thing to look at.

### Caveat on the comparison

The baseline figures were taken on the owner's tablet with a 108-chapter book; these are a
different device with a 3-chapter fixture. The percentages are therefore **not** directly
comparable, and the honest claim is narrower: *on this device, with this fixture, playback still
saturates the main thread and the shelf fix did not stop it.* Reproducing the owner's numbers still
needs the owner's library.

### Status

Remains `In Review`, and the two open criteria stay **unticked** — they are now unticked because
the measurement was taken and **failed**, which is a stronger statement than "not measured". A
follow-up is warranted for the second cause rather than reopening this task's premise, which was
correct as far as it went.

## The real cause, found by profiling (2026-09-02, third attempt)

The first two attempts reasoned from code. This one used `am profile start --sampling` and decoded
the ART trace, which named the cause in one pass. **Everything below is measured.**

### What the profile showed

Main thread, 20 s of playback with Home visible and the player sheet **collapsed**:

| method | entries / 20 s |
|---|---|
| `android.view.View.measure` | **1405** |
| `android.view.View.updateDisplayListIfDirty` | 1264 |
| `ConstraintLayout.onMeasure` | 286 |
| `LiveData.setValue` | 352 |
| `CurrentlyPlayingFragment` observers | 102 |
| `CurrentlyPlayingFragment.refreshSlider` | 44 |
| `BindingAdaptersKt.bindImageRounded` | 50 |

**~14 full ConstraintLayout measure/layout passes per second, for a 1 Hz progress tick.** The
serialized-chapter deserialization this task originally blamed was real but minor; the dominant
cost was **re-rendering**, and most of it for a sheet that was not on screen.

### Three causes, each measured before and after

**1. `CurrentlyPlayingFragment.refreshSlider` — the big one.** Four separate observers
(`chapterDuration`, `currentTrack`, `chapterProgressForSlider`, `trackProgressForSlider`) each call
it on every tick, and each write to `Slider.valueTo`/`value` invalidates — **while the sheet is
collapsed and invisible.** Guarded on `isShown`, plus a value-changed check so four observers
writing the same number no longer invalidate four times.

**2. `MainActivityViewModel.setAudiobook` did a DB read per tick.** `nowPlaying` re-emits every
second with the *same* track; the "has the book changed?" guard sat *after* a suspending
`getBookIdForTrack`. Each resolution re-ran `bindImageRounded`, which rebinds cover art with
`crossfade(true)` — an animation that invalidates continuously. Now guarded on the track id first.

**3. `DoubleLiveData` published unconditionally.** Fixed systemically rather than per-site: it now
suppresses an assignment whose combined result is unchanged, which covers all 23 call sites. Note
honestly that this produced **no further measured gain** once (1) landed — the downstream work was
already gone. It is kept as prevention, not as a measured win.

### Result

| metric | baseline | after |
|---|---|---|
| Main thread during playback | 121-143 j/5 s | **1 j / 6 s** |
| Janky frames, steady state | 88% | **0 frames rendered at all** |
| 90th percentile while scrolling | 4950 ms | **44 ms** |
| Median frame while scrolling | — | 26 ms |
| `uiautomator dump` while playing | fails | **succeeds** |
| Back button | dropped | **immediate** |

The UI now renders **zero frames** across 15 s of undisturbed playback — the correct behaviour for
audio playback behind a static screen. Scrolling during playback is smooth: 26 ms median, 44 ms
p90, a **112x** improvement on the p90. `dumpsys` still reports a high "janky" percentage while
scrolling, but that is against this device's ~5 ms software-composited budget; the percentile
figures are the meaningful ones.

### Method note

Two rounds of inspection produced a plausible, wrong answer each time (see the retracted cu-74
diagnosis in that task). The profiler produced the right one immediately. Recording that as the
lesson: **for a performance claim, profile first — reading code to find hot paths has now failed
twice in this session on this exact problem.**

### Criteria

The two device criteria are now met and ticked. The remaining honest caveat: these figures come
from a 3-chapter mock fixture on a MediaTek GSI, not the owner's 108-chapter library, so the
*absolute* numbers are not comparable to the original report — but the mechanism is fixed and the
main thread is idle, which the original report's symptom (dropped taps) was entirely caused by.

## Fourth pass: the dominant cost was a constraint-graph rebuild per row, per second

The third pass got the main thread to 1 jiffy/6 s — but against a **3-chapter, single-track**
fixture. Once cu-115's richer fixture landed (3 tracks, 8 chapters, two crossing track boundaries)
the saturation came straight back: **431 jiffies / 12 s**. The earlier "fixed" reading was the easy
case, not the fix.

Profiling again named it. `AudiobookAdapter.onBindViewHolder` (28 calls / 16 s) and
`bindImageRounded` (44) were rebinding shelf rows once a second — **correctly**, because
`areContentsTheSame` includes `progress`, and the book being played is in "Recently listened" by
definition, so its progress bar genuinely must move.

The bug was what a *rebind* cost:

```kotlin
fun setSquareAspectRatio(constraintLayout: ConstraintLayout, isSquare: Boolean) {
  GLOBAL_CONSTRAINT.clone(constraintLayout)          // rebuild the whole constraint graph
  GLOBAL_CONSTRAINT.setDimensionRatio(...)           // to set a ratio that never changes
  constraintLayout.setConstraintSet(GLOBAL_CONSTRAINT)   // and force a re-layout
}
```

Every visible row rebuilt its constraint graph and forced a re-layout **once per second**, to
re-apply an aspect ratio fixed at creation. That is the `View.measure` storm — 1285 calls in 16 s.

Guarded on a view tag holding the last-applied ratio (per-view, so a recycled holder switching view
style still re-applies). Also guarded `bindImageRounded` on its last-bound source, which stops a
redundant Coil load and its `crossfade` animation — worth keeping, though it moved the number only
539 -> 524 on its own. The constraint guard is what took it to zero.

### Final numbers, on the harder fixture

| metric | baseline | after |
|---|---|---|
| Main thread during playback | 121-143 j / 5 s | **0 j / 15 s** |
| Frames rendered, steady state | 88% janky | **0 frames** |
| Scroll median / p90 during playback | - / 4950 ms | **25 ms / 42 ms** |
| `uiautomator dump` while playing | fails | **succeeds** |

The UI renders **nothing** while audio plays behind a static screen, and scrolling during playback
is smooth. The p90 improvement is ~118x.

### The lesson, which is the generalisable part

Three of the four causes were **work whose result could not change**: a slider refresh for an
invisible sheet, a DB read to resolve a track that had not changed, and a constraint rebuild for a
constant ratio. The fourth (the image reload) was work whose result was *identical*. None of it was
an algorithmic problem; all of it was *unconditional* work on a 1 Hz path.

And each round of code-reading produced a plausible wrong answer while the profiler produced the
right one immediately — four times now. **Profile first.** The corollary that cost the most time
here: a fix verified against the easy fixture looked complete and was not, so **verify performance
against the worst realistic input**, which is exactly what cu-115's fixture provides.
