---
id: cu-164
title: The library tab ANRs on a loaded device
status: To Do
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

## Acceptance Criteria

- [ ] Reproduced (or not) on a cool, idle device, with the result recorded either way
- [ ] If it reproduces: the dominant cost named from a profile, not from reading
- [ ] If it does not: closed as a thermal artefact, with the trace kept here so the next sighting
      starts from evidence rather than from scratch

## Related

- [[cu-162]] — the pass that found it; it is not the cause
- [[cu-51]] — measured the scan paths and found them linear; this is the view path
- [[cu-110]] / [[cu-117]] — the two previous main-thread investigations, and the profiling method
