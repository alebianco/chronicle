---
name: chronicle-profile-before-optimising
description: Chronicle's performance tasks were written from TODOs, not measurements — cu-51's premise did not survive profiling, and an index made things marginally worse
metadata:
  type: project
---

cu-51 ("large libraries load slowly; add indexes, paged loading, sub-n² queries") came from an
upstream TODO. Profiling first showed paged loading was **already in production**, every scan was
**already linear**, and candidate indexes on the columns eight queries filter and sort by made **no
measurable difference** — `getAllBooks` was marginally *worse* with one, because it reads the whole
table and there is no lookup to accelerate.

**How to apply:** for any Chronicle task whose description is a symptom rather than a measurement,
run Phase 1 (profile) before writing code, and be willing to *retire* an acceptance criterion with
the table that disproves it. That is explicitly allowed by the workflow rule — "a criterion that
turned out to be wrong rather than unmet is retired with its reasoning".

The method is cheap to re-run: a JVM test generating libraries at 1000/5000/10000 books, asserting
the **growth factor** between two sizes rather than a wall-clock budget (a duration pins the CI
runner, not the code). `LargeLibraryScaleTest` is the template. For SQLite, `Room.inMemoryDatabaseBuilder`
under Robolectric plus `execSQL("CREATE INDEX ...")` measures an index A/B directly.

Related: [[chronicle-playback-mainthread-cost]], [[chronicle-device-check-catches-wiring]].
