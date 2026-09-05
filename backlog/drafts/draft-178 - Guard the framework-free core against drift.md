---
id: DRAFT-178
title: Guard the framework-free core against drift
status: Draft
labels:
  - R2
  - maintainability
priority: medium
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

- [ ] A test holds the curated list of framework-free files and fails the build when one grows an
      `android.*` or non-Room `androidx.*` import
- [ ] The list is generated once from measurement, then committed — not computed at test time, so a
      newly-impure file fails rather than silently leaving the list
- [ ] Sabotage-verified: adding an `android.os.Bundle` import to a listed file fails the build
- [ ] The KDoc explains that Room annotations are permitted and why
- [ ] Adding a file to the list is cheap; removing one requires a comment saying why it became
      framework-bound
