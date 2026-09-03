---
id: DRAFT-117
title: A second cause of main-thread saturation during playback
status: Draft
assignee: []
labels: [R1, performance, bug, trust]
dependencies: [cu-110]
priority: high
milestone: m-1
---

## Description

> **Frontmatter correction, 2026-09-03: this was marked `status: Done` while every acceptance
> criterion is unticked and no fix has shipped.** The mislabelling arrived with `54ca95e` and made
> the only remaining R1 item look finished. Corrected to `Draft`; the work below is still to do.


Found by finally *measuring* [[cu-110]] on a device (2026-09-02), once the audio fixture was long
enough for playback to sustain. cu-110's mechanism is fixed — zero Home-shelf recomputes in a 30 s
playback window, against 175 per session at baseline — **but jank got worse, not better.**

| metric | cu-110 baseline | after the cu-110 fix |
|---|---|---|
| Janky frames | 88% | **93.66%** (192 of 205) |
| Main-thread CPU | 121-143 jiffies / 5 s | **~615 jiffies / 5 s** |
| `uiautomator dump` while playing | fails | **still fails** |

### It is playback-driven, and it is not the shelves

- **Paused: 0 jiffies / 10 s**, and `uiautomator dump` succeeds.
- **Playing: ~615 jiffies / 5 s** on the main thread, dump fails with "could not get idle state".

The saturation switches entirely with playback state. Ruled out: Home shelves (0 recomputes),
network (2 requests in 10 s), software rendering (physical MediaTek device, PowerVR Rogue GE8320).

### Where to look first

`CurrentlyPlaying.update` republishes `book`, `track`, `chapter` **and** `bookPosition` on every
1 Hz `ProgressUpdater` tick. Everything observing those recomputes at tick rate:

- `CurrentlyPlayingSingleton.printDebug` — a `Timber.i` on the main thread, once per second.
- The mini player in `MainActivity` observes `currentChapterTitle` and `audiobook`; the latter
  re-runs `bindImageRounded`, which per the 2026-09-02 review does a **Dagger lookup and a `Uri`
  parse per bind**.
- `CurrentlyPlayingViewModel` has ~6 flows combining off those StateFlows; `chapterProgress` and
  `chapterProgressForSlider` both `combine(chapter, bookPosition)`, so each tick fans out twice.

This is the same shape as cu-110 one layer down: a 1 Hz write fanning out to unconditional
recomputation. The difference is that it is now measurable, so a fix can be verified rather than
argued.

## Acceptance Criteria

- [ ] The cause identified by measurement, not inspection — name the thread and the work
- [ ] Janky frames materially below 88% during playback with Home visible, figure recorded
- [ ] Main-thread CPU during playback within a small multiple of the paused figure (currently
      615 jiffies/5s vs **0**)
- [ ] `uiautomator dump` succeeds *while playing*, which is the criterion cu-110 claimed and this
      still blocks
- [ ] `printDebug`'s per-second main-thread log removed or moved behind a debug flag
- [ ] No behaviour regression: position still survives a process kill (cu-9), and the mini player
      still updates its chapter title and progress
- [ ] Re-measured on the owner's tablet with a 100+ chapter book, since the figures above come
      from a 3-chapter fixture and are not directly comparable to the original report


## RESOLVED in cu-110 (2026-09-02) — do not promote

Status is `Done` rather than a custom value so the Backlog.md board can read it; the
resolution happened under cu-110, not here.

Filed before the cause was known. Profiling then found it, and the fix landed under [[cu-110]]
rather than as separate work, so this draft is superseded. Kept for the record because its
framing — "a 1 Hz write fanning out to unconditional recomputation *and re-rendering*" — is the
correct generalisation and is worth remembering.

The cause was **`CurrentlyPlayingFragment.refreshSlider`**: four observers calling it per tick,
each write invalidating a Slider, **while the player sheet was collapsed and invisible**. Plus a
per-tick DB read in `setAudiobook` that re-bound cover art with a crossfade animation.

Result: main thread 121-143 jiffies/5 s -> **1 jiffy / 6 s**; zero frames rendered in steady-state
playback; scroll p90 4950 ms -> **44 ms**. Full numbers in cu-110's notes.

### The generalisable lesson, which does still need action

`DoubleLiveData` now suppresses no-op emissions for all 23 call sites, but the wider shape remains:
**102 observers, 23 hand-rolled combinators, and only 6 `distinctUntilChanged` in the whole app.**
The remaining exposure is any observer that writes to a view on every emission without checking
whether the value changed, and any work done for a view that is not on screen.

Two structural guards worth their weight, neither filed yet:

1. **A lint/test guard for "work while not visible"** — the `isShown` check that fixed this is a
   pattern, not a one-off. Anything driven by a 1 Hz source and writing to a view should have one.
2. **Retire the hand-rolled combinators** ([[cu-52]]'s StateFlow migration deletes the family, and
   `combine` + `distinctUntilChanged` is one line each). That converts "remember to dedupe" into
   the default.
