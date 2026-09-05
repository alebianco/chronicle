---
id: cu-164
title: The library tab ANRs on a loaded device
status: In Review
assignee: []
labels:
  - R2
  - performance
dependencies: []
milestone: m-2
priority: medium
---

## Description

Found during [[cu-162]]'s device pass and **not caused by it** — the same sequence reproduces on the
previous build with `androidx.fragment` 1.5.4, everything else identical.

Tapping the **Library** tab against the 196-book household library produced *"Chronicle (debug)
isn't responding"*. The ANR trace has the main thread **Runnable**, not blocked:

```
"main" prio=5 tid=1 Runnable
  at androidx.appcompat.widget.AppCompatDrawableManager$1.arrayContains(AppCompatDrawableManager.java:358)
  at androidx.appcompat.widget.AppCompatDrawableManager$1.getTintListForDrawableRes(...)
  at androidx.appcompat.widget.AppCompatBackgroundHelper.loadFromAttributes(...)
  at androidx.appcompat.widget.AppCompatTextView.<init>(AppCompatTextView.java:132)
  at com.google.android.material.textview.MaterialTextView.<init>(...)
  at android.view.LayoutInflater.rInflate(...)
```

So it is **inflation cost**, not a deadlock and not a lifecycle bug: the grid is inflating rows, and
each `MaterialTextView` walks `AppCompatDrawableManager`'s tint tables. `Skipped 182 frames` in the
same log.

## What is unproven, and matters

The tablet was at **58.5 °C** after hours of builds, emulators and playback, and the *same tab
rendered fine several times earlier in that session on the same build*. So the trigger is load or
thermal throttling, not the code path alone — reproducing this on a **cool, idle device** is the
first step, and it may not reproduce at all.

Do not treat it as a confirmed defect until that is done.

## Where to look if it does reproduce

- **Row inflation.** `AudiobookAdapter`'s row is inflated per item; the trace points at
  `AppCompatTextView` construction rather than at binding. A `RecyclerView` should be recycling, so
  the question is how many rows the grid inflates before first paint at this span count.
- **[[cu-51]]'s measurements are the wrong half.** That task measured *scan* cost — search and facet
  grouping, all linear and all off the main thread. This is *view* cost on the main thread, which it
  explicitly did not cover.
- **Profile, do not read** — the cu-110 lesson. `am profile start --sampling` named that cause at
  once where four rounds of inspection produced plausible wrong answers.

## Findings (2026-09-05, cool idle device)

Device state measured before testing, not assumed: **35.6 °C** CPU/GPU and **96.7% idle** over a
2-second `/proc/stat` sample, against 58.5 °C when the ANR was first seen. (`uptime` reports a load
average of ~24 on this GSI and is simply wrong — `top` showed 734% of 800% idle.)

**It reproduced, so it is not a thermal artefact — but the task's description of it is wrong twice.**

1. **It is not the Library tab.** The ANR fires during *launch*, before any tab is touched. Tapping
   Library afterwards produces no ANR at all: 59 skipped frames, then the full 196-book grid renders
   correctly (screenshot taken).
2. **It is not row inflation.** The ANR reason is `Input dispatching timed out (Application does not
   have a focused window)` — the app had not presented a window yet. The main thread stack is
   entirely framework toolbar-menu inflation with **no Chronicle frame in it**, and a
   599k-record `am profile` sample attributes **zero self-time to our own code** on the main thread.
   The top costs are ConstraintLayout solving (`LinearSystem.addEquality`, `ArrayLinkedVariables.*`).

### The real number

Cold start to first frame, three runs each, `am start -W`:

| build state | TotalTime |
|---|---|
| debug, JIT (as installed) | **5.47 s** (5468 / 5465 / 5463) |
| debug, after `cmd package compile -m speed -f` | **3.33 s** (3329 / 3354 / 3334) |

So **~2.1 s is JIT warmup that a release build would not pay**, and the app is `DEBUGGABLE`, which
also forces `-Xcheck:jni`. The remaining ~3.3 s is real and is spread evenly rather than sitting in
one hotspot — timeline from logcat, AOT run:

```
+0.000  process start
+0.534  Fetch2 listener added   (DI graph built)
+1.093  first layout inflation
+1.860  Room opens
+2.595  "Skipped 135 frames"
+2.949  first network call
```

### What is still unknown

**The release figure.** There is no signing config in this repo (owner-only), so the release APK
cannot be installed and the user-facing number cannot be measured here. AOT compilation is the
closest available proxy and it is not the same thing.

### Recommended next step

Not a fix yet — a decision. 3.3 s is slow but there is no single cause to attack, so the work is
either (a) accept it as the cost of a cold start on this hardware, or (b) a startup task that
defers `backfillChapterTable`/`updateDownloadedFileState`/network setup behind the first frame. That
is a scope call, and the honest input to it is that **no Chronicle code appears in the profile**.

## Acceptance Criteria

- [x] Reproduced (or not) on a cool, idle device, with the result recorded either way
- [x] If it reproduces: the dominant cost named from a profile, not from reading
- [x] ~~If it does not: closed as a thermal artefact~~ — retired: it *does* reproduce cool and idle
- [ ] Owner decides whether 3.3 s cold start is worth a startup-deferral task, or accepted as-is

## Related

- [[cu-162]] — the pass that found it; it is not the cause
- [[cu-51]] — measured the scan paths and found them linear; this is the view path
- [[cu-110]] / [[cu-117]] — the two previous main-thread investigations, and the profiling method
