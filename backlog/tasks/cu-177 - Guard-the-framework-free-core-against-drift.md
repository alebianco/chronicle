---
id: cu-177
title: Guard the framework-free core against drift
status: Done
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
milestone: m-2
dependencies: []
priority: medium
ordinal: 97000
---

## Description

**86 of 209 files (41%), ~8,000 lines, are already framework-free** — no `android.*`/`androidx.*`
imports beyond Room annotations — and they cover the decision logic: `SleepTimerState`,
`ChapterSeekTarget`, `CacheReconciliation`, `IngestionPlan`, `BookSearch`, `SeriesIndexPatterns`,
all five repositories, `DurationFormat`.

Measured 2026-09-06:

| | coverage | missed |
|---|---:|---:|
| framework-free files | **80.8%** | 3,432 |
| everything else | 35.7% | 38,296 |

That purity is not an accident — it is what cu-19, cu-21, cu-101 and cu-136 each produced by
extracting a decision out of a framework class. **The domain layer already exists; it has no
boundary around it.**

Nothing stops someone adding `import android.os.Bundle` to `SleepTimerState` tomorrow. Today's
purity is convention, enforced only by review — and the 2026-09-05 review found that review misses
things.

## Why a guard test rather than a `:domain` Gradle module

A module was considered (see the analysis doc). It would enforce the boundary, speed the build, and
split one blended 45.7% into a far more useful pair of signals — but it would **not unlock a single
test that cannot be written today**, since the pure code is already at 80.8%. Its cost is permanent
multi-module build configuration in a project whose D12 rule 6 says plain git and a shell script
should be enough to move the whole thing to another forge.

A source-scanning guard buys the anti-drift half for an afternoon, in the style this repo already
uses in eleven places (`ModelsWithoutDiTest`, `ScopedQueryTest`, `RepositoryDispatcherTest`,
`WorkerDispatcherTest`, …). If the list proves stable, promoting it to a real module later is a
smaller step taken with evidence.

## Acceptance Criteria

- [x] A test holds the curated list of framework-free files and fails the build when one grows an
      `android.*` or non-Room `androidx.*` import
- [x] The list is generated once from measurement, then committed — not computed at test time, so a
      newly-impure file fails rather than silently leaving the list
- [x] Sabotage-verified: adding an `android.os.Bundle` import to a listed file fails the build
- [x] The KDoc explains that Room annotations are permitted and why
- [x] Adding a file to the list is cheap; removing one requires a comment saying why it became
      framework-bound

## Implementation Notes

`FrameworkFreeCoreTest` holds **87 committed paths** and fails the build when one grows an
`android.*`/non-Room `androidx.*` import. Room annotations are permitted, with the KDoc explaining
why: they are compile-time metadata a pure Kotlin module keeps, and excluding the entities would
drop the models the guard most wants to protect.

**Sabotage-verified twice**, because this guard has two ways to be useless:

- adding `import android.os.Bundle` to `SleepTimerState` → fails
- listing a path that no longer exists → fails (a moved file must not silently pass)

The list is committed rather than computed at test time. Deriving it would let a newly-impure file
drop out of the set and the test still pass, which is exactly the failure mode that makes a guard
worthless.

Recorded as convention rule 6 in CLAUDE.md, with the coverage figures that justify it (80.8% for
these files against 35.7% for everything else) and the instruction to **move the framework-facing
part out** rather than delist — cu-176 as the worked example.

Closed `Done` rather than `In Review`: this is a build gate proved by sabotage, with no screen
touched and no product choice made.
