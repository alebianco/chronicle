---
id: cu-110
title: The home screen recomputes on every progress tick
status: To Do
assignee: []
created_date: '2026-09-02'
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

- [ ] Back and the nav bar respond reliably while the player sheet is open, on the tablet, with a
      100+ chapter book playing
- [ ] Janky frames well under 88% in that state, measured with `dumpsys gfxinfo`, and the figure
      recorded — this is a performance claim and needs a number, not an impression
- [ ] `HomeViewModel.recentlyListened` no longer recomputes once per second during playback
- [ ] `uiautomator dump` succeeds while the player is open (it currently cannot get an idle state),
      since that also unblocks automated UI checks for [[cu-73]]
- [ ] No behaviour regression: the home list must still update when a book's progress genuinely
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
