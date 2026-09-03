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
