---
id: cu-33
title: Complete backend interface carve
status: To Do
assignee: []
created_date: '2026-07-13'
labels:
  - R2
  - architecture
  - debt
milestone: m-2
dependencies:
  - cu-15
priority: high
ordinal: 53000
---

## Description

Route the direct `data.sources.plex.*` imports through the interface; login-flow redesign for
URL+token backends; per-source capability flags (`hasNarrator`, `hasSeries`, `hasServerProgress`) so
facets/'synced'/series shelves degrade gracefully (D11).

## Promoted to R2 (owner decision, 2026-09-01)

Moved from R4 to R2 on the evidence of the pre-R2 review. This was scheduled as *preparation for new
backends*, which is an R4 concern — but it is the single largest thing holding the test suite down
today, which is an R2 concern.

**`Injector.get()` appears 73 times across 30 files.** A class that fetches its own dependencies at
runtime cannot be constructed in a unit test with fakes, and the correlation is exact: nine of the
twelve ViewModels have no tests, and they are the nine that call the service locator.
`SettingsViewModel` has 13 calls and zero tests; `features/settings`, `features/login` and
`features/collections` sit at **0%**, together 9,624 missed instructions — a sixth of the codebase.

The worst single instance is not in a ViewModel: `AudiobookMediaSessionCallback` takes **16 injected
dependencies** and still makes a live HTTP call through `Injector.get().plexMediaService()`
(`:462`), and reads three token fields to write HTTP auth headers (`:419-429`). Credential plumbing
inside a MediaSession command callback.

The R4 backend work (cu-33.1–33.3) stays at R4. This task is the carve only.

Note the import count in CLAUDE.md said 27 and is now **28** — corrected in `fdc28ce`.

## Acceptance Criteria

- [ ] No direct plex imports in `features/` (28 files today)
- [ ] `Injector.get()` gone from ViewModels and repositories; constructor injection throughout
- [ ] `AudiobookMediaSessionCallback` no longer reaches the network through the service locator, and
      does not assemble auth headers itself
- [ ] UI renders correctly against a source lacking narrator/series/server progress
- [ ] At least one previously untestable ViewModel gains tests as proof the seam works — the point
      of doing this now is testability, so a carve that does not enable a test has not landed
- [ ] A guard test keeps `Injector.get()` out of the carved packages, in the style of
      `InternalApiUsageTest`
