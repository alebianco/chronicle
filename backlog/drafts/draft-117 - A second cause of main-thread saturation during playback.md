---
id: DRAFT-117
title: A second cause of main-thread saturation during playback
status: Draft
assignee: []
labels: [R2, performance, bug]
dependencies: [cu-110]
priority: high
milestone: m-2
---

## Description

> **Reclassified to R2, 2026-09-03, when R1 was frozen.** The *trust* half of this — the owner's
> reported symptom, "the back button and the nav button sometimes don't work when the playback
> screen is open" — **is fixed and verified**: `uiautomator dump` now succeeds 5/5 and 3/3 while
> playing, against every attempt failing before, and main-thread CPU fell 277→176 jiffies/10 s.
> The main thread reaches idle again, which is the mechanism behind unresponsive buttons.
>
> What remains is **frame rate** (~90% janky), which is Comfort rather than Trust under the
> R0–R4 ordering: nothing is lost or wrong, playback and controls work. It also cannot be
> progressed without a profiler trace, which could not be captured on this GSI — so holding R1
> open for it would block a freeze on a measurement the environment cannot currently produce.


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

- [x] The cause identified by measurement, not inspection — name the thread and the work
- [ ] Janky frames materially below 88% during playback with Home visible, figure recorded
- [ ] Main-thread CPU during playback within a small multiple of the paused figure (currently
      615 jiffies/5s vs **0**)
- [x] `uiautomator dump` succeeds *while playing*, which is the criterion cu-110 claimed and this
      still blocks
- [x] `printDebug`'s per-second main-thread log removed or moved behind a debug flag
- [x] No behaviour regression: position still survives a process kill (cu-9), and the mini player
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


## Implementation Notes — partial fix, measured

**Two guards added, both verified to work, and neither is the dominant cost.** Recording that
plainly because the number did not move the way a fix "should".

### What was measured, on the 107-track live fixture

| state | main-thread CPU |
|---|---|
| paused | **1** jiffy / 10 s |
| playing, app **backgrounded** | **15** |
| playing, foreground, before | **277–287** |
| playing, foreground, after | **176** |

Backgrounding was the decisive measurement: same playback, same 1 Hz ticks, no views — 15 jiffies.
**So the cost is rendering, not the data layer**, exactly the shape CLAUDE.md records for cu-110.

### The guards

1. `CurrentlyPlayingSingleton.update` no longer republishes `book`/`track` or rebuilds the chapter
   list on an unchanged tick, and no longer logs one line per second.
2. `MainActivity`'s mini-player observer no longer re-runs `bindImageRounded` (a Dagger lookup, a
   `Uri` parse and a Coil load) when title and artwork are unchanged.

**A trap worth recording:** the first attempt compared `tracks != tracks` and guarded *nothing*,
because `ProgressUpdater` writes the playing track's progress to Room every second — so the list
re-read a second later is genuinely different. The comparison has to ignore the field that is
*meant* to change; it now compares `(id, duration)` pairs. The logs proved the difference: 12
per-second lines before, 0 after.

### What improved, and what did not

**Improved, and this is the criterion that matters most:** `uiautomator dump` **succeeds while
playing** — 5/5 and then 3/3 consecutive attempts, against "could not get idle state" every time
before. The main thread now reaches idle, which is the mechanism behind the owner's original
report of unresponsive Back and nav buttons. Main-thread CPU also fell ~38% (277 → 176).

**Not achieved:** janky frames are still ~90%. And per-thread sampling shows why the remaining cost
is not obviously app-level — ExoPlayer 263 jiffies/10 s, RenderThread 214, MediaCodec 132, four
`arch_disk_io` threads ~460 combined. The main thread's 176 sits *among* genuine audio decoding on
an 8-core device (≈17% of one core).

### Honest next step

The remaining jank needs a real profile, and `am profile start` could not be made to write a trace
on this GSI (SELinux denies `/sdcard`, and both `/data/local/tmp` and the app's files dir produced
0-byte traces). Without one, further changes would be guesswork of exactly the kind cu-110's own
gotcha warns against — *"four rounds of inspection produced plausible wrong answers here"*. Better
to stop at a measured, verified improvement than to keep changing code on a hypothesis.
