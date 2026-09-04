---
id: cu-140
title: Playback still draws four frames a second with no layout pass
status: To Do
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels: [R2, performance, bug]
dependencies: [cu-117]
priority: medium
milestone: m-2
---

## Description

What is left of [[cu-117]] after its guards landed, stated as its own question because the answer
needs a different investigation than "find the unguarded view write".

**The state, measured on the A33 with Perfetto against a real 28-track/47-hour book:**

| state | frames / 20 s | jank | main thread |
|---|---|---|---|
| paused, foreground | 1 | — | 1 j/10 s |
| playing, backgrounded | 4 | 0% | 3 j/10 s |
| playing, foreground | **~61** | ~29–44% | 42 j/10 s |

cu-117 removed the per-tick `measure`/`layout` work — a 6 s trace after the fix shows **no**
`measure`/`layout` slices from that path, down from 17+17. Main-thread CPU fell 76 → 42 j/10 s.

**But the frame count barely moved (75 → 61) and jank did not improve at all.** So roughly 3 frames
per second are still being drawn, on a 1 Hz data source, *without* a layout pass. Draw-only
invalidation.

The trace's remaining app-thread slices, per 6 s:

```
266  FillRectOp
114  TextureOp
 76  Clear / AtlasTextOp / NonAALatticeOp / ShadowCircularRRectOp
 19  prepareTree / syncFrameState / dequeueBuffer
```

`ShadowCircularRRectOp` is shadow-casting rounded rects — elevation on the mini player's controls
and the bottom sheet. Shadows are expensive to redraw and are a plausible amplifier, but **nothing
here identifies what triggers the invalidation**, which is the actual question.

### Candidates, none verified

1. **An `invalidate()` without a layout** — a drawable-level change (progress bar `setProgress`,
   an ImageView tint, a selector state) redraws without re-measuring. `book_progress` in the
   RecyclerView rows is one such: CLAUDE.md notes a playing book's row legitimately rebinds every
   second, so it must be *cheap*, and a `ProgressBar.setProgress` is exactly a draw-only invalidate.
2. **The bottom sheet's elevation/scrim** — a collapsed sheet still composites, and
   `ShadowCircularRRectOp` at 76 per 6 s is ~4 per frame.
3. **Something outside the app's own views** — the media notification, or a system overlay
   compositing over the window. 60 `present` ops per 6 s in the trace is 10 Hz, above the app's own
   ~3 Hz, so the app is not the only thing driving the display.

### Why this is Comfort, not Trust

Nothing is lost or wrong: playback is correct, controls work, position survives. The owner's
original *trust* symptom (unresponsive Back and nav buttons) was cu-110's, and is fixed. This is
frame smoothness only.

**A caution carried from [[cu-117]]:** `uiautomator dump` still fails while playing (0/5, against
5/5 paused). It leaves the previous dump file behind on failure, so a stale file reads as success —
assert the file exists, never just that the command was quiet.

## Measuring this: two traps found the hard way

**`framestats` column counts differ per Android version.** The A33 (API 36) emits a **24-column**
`PROFILEDATA` header; the Phh-Treble GSI (API 32) emits **22**. Parsing one device's rows with the
other's hardcoded indices yields negative durations and values like `9223372036854775807`, which
reads convincingly as a broken clock and is really a parsing bug. **Always read the `Flags,...`
header row and map fields by name.** Parsed correctly the GSI is fine: vsync→SwapBuffers p50
28.17 ms / p90 33.15 ms, traversal→draw p50 0.69 ms.

**`Long.MAX_VALUE` in `FrameCompleted` and `GpuCompleted` is normal**, not corruption — those are
populated asynchronously after the frame is handed off, so the newest rows in the buffer carry a
"not yet known" sentinel (8 of 120 rows in a sample here). Filter those two fields rather than
discarding the row.

**A p90 of 4950 ms means the window included app startup.** It is not a stuck or sentinel value: a
first frame legitimately costs seconds, and `dumpsys gfxinfo` averages over whatever is in its
buffer. `reset` **after** the app has settled, then measure — otherwise startup dominates and the
steady-state figure is unobtainable. This is what made the earlier tablet numbers hard to trust.

Both devices agree on the finding once measured this way, which is why it is worth trusting:

| state | frames / 20 s | main thread |
|---|---|---|
| A33 (API 36) foreground | ~61 | 42 j/10 s |
| A33 backgrounded | 4 | 3 j/10 s |
| GSI (API 32) foreground | 63 | 127 j/10 s |
| GSI backgrounded | **0** | **11 j/10 s** |

## Acceptance Criteria

- [ ] The trigger for the ~3 draws/second identified **from a trace**, naming the view and the call
      that invalidates it — not from inspection
- [ ] Frames drawn during steady-state playback at or near the backgrounded figure (4 / 20 s), or a
      recorded explanation of why a higher floor is correct
- [ ] Janky frames measured after the change, on a multi-track book, figure recorded next to the
      ~29–44% baseline above
- [ ] `uiautomator dump` succeeds while playing — verified by asserting the dump **file exists**,
      5 consecutive attempts
- [ ] No regression: expanded player still updates its text, slider and artwork during playback,
      and expanding while paused shows current values (both verified by screenshot in cu-117)
- [ ] If a change does not help, the notes say so with the numbers — the standing rule for this
      cluster

## Related

- [[cu-117]] — the guards that removed the layout half; this is the draw half
- [[cu-110]] — the original fan-out, and the "profile, do not read" gotcha this task must obey
- [[cu-51]] — large-library performance, which shares the RecyclerView rebind path


## Measured baseline (2026-09-04, from cu-117)

Tablet, real ANTARES session, **Ender's Game (id 151444, 107 tracks)** — the worst realistic input
in the household's library:

| | playing | paused |
|---|---|---|
| main-thread CPU | **234 jiffies/10 s** | 1 jiffy/10 s |
| `uiautomator dump` | **0/5** | 3/3 |

The same measurement on the A33 phone with a 28-track book gives 42 j/10 s — so the cost **scales
with track count** and the phone understated it by 5.6×. Measure this task's fix on the tablet with
a 100+ track book, or the number will flatter it again; that mistake has now been made three times
in this area (cu-110, cu-115, cu-117).

**Targets:** main-thread CPU within a small multiple of the paused figure, and `uiautomator dump`
succeeding while playing — the two criteria cu-117 could not meet. Delete the dump target before
each attempt: a failed dump leaves the previous file in place and a stale read looks like success.

**Profiling still owed.** `am profile start --sampling` is the tool that named the cause in cu-110
after four rounds of inspection produced plausible wrong answers; it had not been run when the
tablet went offline. Do that before theorising.

**Incidental, possibly related:** `NotificationBuilder` logs *"Building notification!
state=STATE_PLAYING"* five times within 400 ms at playback start (see cu-50).


## Profile, 2026-09-04 — the cause, named by sampling

`am profile start --sampling 1000` for 15 s on the tablet, real ANTARES session, **Ender's Game
(id 151444, 107 tracks)** playing. 172,948 samples, 4,646 methods. This is the run cu-117 could not
complete.

**The main thread is 37.7% of all samples in the process** (65,286 of 172,948) — more than
ExoPlayer's own playback thread (12.5%) and all four disk-IO threads combined (27.9%).

### It is layout and draw, not data work

| samples | % of main | method |
|---|---|---|
| 1816 | 2.8% | `View.measure` |
| 1316 | 2.0% | `View.updateDisplayListIfDirty` |
| 948 | 1.5% | `ViewGroup.dispatchDraw` |
| 944 | 1.4% | `ViewGroup.drawChild` / `View.draw` |
| 916 | 1.4% | `View.layout` |
| 910 | 1.4% | `ViewGroup.measureChildWithMargins` |
| 898 | 1.4% | `ConstraintLayout …verticalSolvingPass` |
| 450 | 0.7% | `View.requestLayout` |

Every top entry is the measure/layout/draw pipeline. `verticalSolvingPass` says a
`ConstraintLayout` graph is being re-solved, and `requestLayout` at 450 samples says something is
explicitly invalidating it rather than the frames being merely redrawn.

### What triggers it — our own code is only ~1.5% of the main thread but causes the rest

| samples | site |
|---|---|
| 246 | `LifecycleExt …onChanged` |
| 210 | `DoubleLiveData.publish` |
| 142 | **`MediaItemTrack.compareTo`** |
| 114 + 96 | `DoubleLiveData` lambdas |
| 84 + 82 | `Chapter.compareTo` / `Chapter.equals` |
| 74 | `…getDuration` |
| 52 | `CurrentlyPlayingViewModel.progressPercentageString$lambda$19` |
| 50 + 50 | `getProgress` / **`getActiveTrack`** |

Also present: `LiveData.setValue` (406), `dispatchingValue` (398), `considerNotify` (396).

The chain is `ProgressUpdater`'s once-a-second write → Room per-table invalidation →
`DoubleLiveData.publish` → observers → `requestLayout` → a full measure/solve/draw pass. cu-110
fixed four instances of this shape; this profile says the *publish* side is still firing and the
draw side is what it costs now.

### A concrete, separable finding: `getActiveTrack()` re-sorts on every call

```kotlin
fun List<MediaItemTrack>.getActiveTrack(): MediaItemTrack {
  val inPlaybackOrder = sorted()      // 107 tracks, once per call
  return inPlaybackOrder.lastOrNull { it.hasProgress() } ?: inPlaybackOrder.first()
}
```

`getProgress()` does the same (`MediaItemTrack.kt:226`). With 107 tracks and a publish every
second, that is the 142 samples in `compareTo` — and it is why the cost **scales with track count**,
which is what made the phone's 28-track measurement understate this by 5.6×
(42 → 234 jiffies/10 s, cu-117).

Note `TrackIndex` means "index into the **sorted** list" (cu-136), so the sort cannot simply be
dropped — the order is load-bearing. Sorting once per track-list change rather than per read is the
fix.

### Where to start

1. `getActiveTrack`/`getProgress` sorting per call — smallest, measurable on its own, and explains
   the track-count scaling.
2. Then the publish side: what still re-publishes at tick rate and reaches a `ConstraintLayout`.
   `progressPercentageString` appears in the profile and cu-94 already touched that area.
3. Re-measure **on the tablet with Ender's Game**, against the 234 j/10 s baseline above. A
   single-track fixture will report this fixed when it is not — that mistake has now been made
   three times here (cu-110, cu-115, cu-117).

Raw trace not committed (2.9 MB); reproduce with the command above.


## First change: `getActiveTrack` no longer sorts — measured, and it is *not* the fix

Replaced `sorted()` with a single pass (`MediaItemTrack.getActiveTrack`). Measured on the tablet
with Ender's Game, same conditions as the baseline:

| | main thread |
|---|---|
| before | **234** j/10 s |
| after | **220, 221, 223** j/10 s (steady state) |

**~5%.** Real, and worth keeping — it removes an O(n log n) per publish that scaled with track
count — but it is *not* the cause and it does not move either open criterion. The profile already
said so and the measurement agrees: `compareTo` was 142 of 65,286 main-thread samples, **0.2%**.
Recorded here because the temptation with a named finding is to assume fixing it fixes the problem.

A first attempt used `maxWithOrNull`, which was **wrong on ties**: `compareTo` is `(disc, index)`
and neither is unique, and where a stable `sorted().lastOrNull` answers the *last* tied track,
`maxWithOrNull` answers the *first* — it replaces its candidate only on a strictly greater
comparison. `ActiveTrackNoSortTest` caught it and now pins it explicitly, including 200 shuffles of
a 107-track book.

### So the remaining work is the draw side, and it is all of it

The 37.7% is `View.measure` / `updateDisplayListIfDirty` / `dispatchDraw` /
`ConstraintLayout.verticalSolvingPass`, driven by `requestLayout` (450 samples). Next steps in
order:

1. Find what calls `requestLayout` during playback. `LiveData.setValue` (406) → `dispatchingValue`
   (398) → `considerNotify` (396) is the path in; the question is which observer writes to a view
   property that invalidates a `ConstraintLayout` graph rather than just repainting.
2. `progressPercentageString$lambda$19` is in the profile (52 samples) and cu-94 already worked in
   that area — a text change on a `ConstraintLayout`-managed `TextView` forces a re-solve if the
   width is `wrap_content`, which is the classic version of this.
3. cu-110's rule applies: guard on *visibility* and on *value changed*. The sheet being collapsed
   should mean no work at all, and cu-19 showed the `isShown` guard has to probe a view that exists
   in every orientation.
