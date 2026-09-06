---
id: analysis-README
title: Debt analysis (reference)
type: analysis
created_date: '2026-09-01'
---

# Debt analysis (reference)

*Optional* deep-reference for debt items whose understanding is too large to inline in a task —
problem statement, current-state audit, risk. **This is background reference, not the tracker.** A
task's own plan and notes live *inside the task file*; the tracker of record is
[`../../tasks/`](../../tasks/).

Read the analysis file for a task only when one is linked. Most tasks need none.

## What is here now

| File | What it is |
|---|---|
| [`maintainability-review-2026-09.md`](maintainability-review-2026-09.md) | **Live document.** The 2026-09-05 structural pass over `app/src/main`, appended to since. It spawned cu-173–cu-180 and the second wave cu-181–cu-188, and still carries open items nothing else records. Cited by path from cu-173, cu-174 and cu-175. |

Everything else is in [`archive/`](archive/).

## Archive policy

A file moves to `archive/` once its task is Done **and** the content no longer matches the code.
In practice most of these were not merely stale but *wrong* by the time their task ran — the
originals are 2025-11 pre-consolidation plans written before anyone measured anything. **Read any
archived file as a starting hypothesis, not a spec**, and prefer the task's own implementation
notes, which record what was actually true.

Worked examples of plans their own task refuted:

- **C2** called the version catalog's fake KSP entry "good news".
- **H2** assumed the ProGuard rules were minimal; cu-45 found the opposite — far too broad.
- **H4** proposed Codecov, which D12 rule 7 forbids.
- **M2** (archived 2026-09-06) sat at *"Awaiting Architecture Decision"* offering a "1 day or 5
  weeks" estimate, and missed the actual blocker cu-52 hit — that Room returns `LiveData` from nine
  DAO methods. LiveData is now gone from `app/src/main` entirely.
- **M6** (archived 2026-09-06) proposed a `setContentTitle` fix that **cannot work**: cu-50
  established that `MediaStyle.setMediaSession()` makes Android render session metadata and discard
  `setContentTitle`. Its sections are also stored in reverse order, so the file is barely readable.
- **M7** (archived 2026-09-06) asserted the repository scaled "sub-n²"; cu-51 measured **linear**
  growth on every path and *rejected* the plan's index recommendation as measurably useless
  (`getAllBooks` got marginally worse). cu-164 then found the real cost was view inflation, not the
  scan.
- **H8** (archived 2026-09-06) asserted ImageViews were "likely missing contentDescription"; cu-47
  measured **zero** unlabelled images. The real defect was six 32dp touch targets, now 48dp and
  guarded.

## Debt code → task

| Debt | Tracked as |
|---|---|
| C1 cleartext traffic | cu-42 |
| C2 KAPT→KSP | cu-8 |
| C3 Fresco→Coil | cu-43 |
| C4 GlobalScope · C5 InternalCoroutinesApi · H5 dispatchers · C6 LocalMediaSource · H6 delicate API | cu-15 (ride-along refactor folds all five) |
| H1 test coverage | cu-44 (+ gate in cu-3, fixtures in cu-16) |
| H2 ProGuard | cu-45 |
| H3 SDK/doc mismatch | cu-2 + cu-6 |
| H4 CI test execution | cu-3 |
| H7 TODO audit | cu-46 |
| H8 accessibility | cu-47 |
| M1 kotlin-result | cu-48 |
| M2 StateFlow | cu-52 |
| M3 billing | cu-53 (Won't Do — [[decision-15]]) |
| M4 chapter DB | cu-49 |
| M5 Android Auto | cu-23 |
| M6 notifications | cu-50 (+ cu-157, cu-137) |
| M7 large-library perf | cu-51 (+ cu-161, cu-164) |
