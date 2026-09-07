---
name: chronicle-playback-mainthread-cost
description: Chronicle's playback main-thread cost is layout/draw, not data work — profiled 2026-09-04, and the named data findings are ~5% of it
metadata:
  type: project
---

Profiled 2026-09-04 (`am profile start --sampling 1000`, 15 s, tablet, real server, **Ender's Game
id 151444, 107 tracks**): the main thread is **37.7% of all samples in the process** — more than
ExoPlayer's playback thread (12.5%) and all four disk-IO threads combined.

**Every hot method is measure/layout/draw**: `View.measure`, `updateDisplayListIfDirty`,
`dispatchDraw`, `ConstraintLayout.verticalSolvingPass`, `View.requestLayout`. Our own code is only
~1.5% of main-thread samples but triggers it, via `DoubleLiveData.publish` → `LiveData.setValue` →
observers → `requestLayout`.

Removing the per-publish `sorted()` from `getActiveTrack` (a real O(n log n) that scaled with track
count) moved it **234 → 220 jiffies/10 s, ~5%**. Keep expectations calibrated: a named data-layer
finding here is not the cause. The remaining work in cu-140 is the draw side.

Baselines to measure against, on the **tablet** with a 100+ track book: 234 j/10 s playing vs 1
paused, `uiautomator dump` 0/5 playing vs 3/3 paused. A single-track or 28-track book understates
this by ~5.6× and has produced a false "fixed" three times (cu-110, cu-115, cu-117).

**Why:** four rounds of inspection produced plausible wrong answers in this area before profiling
named it.
**How to apply:** profile before theorising, and measure against the worst realistic input.

Related: [[chronicle-tablet-session]]
