---
id: cu-59
title: Test AudiobookDetailsViewModel cache and playback behaviour
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: [cu-44]
priority: medium
milestone: m-1
---

## Description

Split out of cu-56, which deleted `AudiobookDetailsViewModelTest` — five `@Test` methods whose bodies
had been fully commented out since `6ef9787` (2019). They were deleted rather than restored because the
API they targeted no longer exists (see cu-56 notes). This task preserves **what they intended to
verify**, so the intent is not lost with the file.

`AudiobookDetailsViewModel` is a high-value target: 9 constructor collaborators, a derived cache state
machine, and the download/playback entry points for a book. It remains untested.

### Behaviours the deleted tests meant to cover, mapped to the current API

1. **Play starts playback for the right book** — was `viewModel.play()`, now
   `pausePlayButtonClicked()`. Assert the transport controls receive the book's id.
2. **Jump-to-chapter starts playback at the right position** — `jumpToChapter(offset, trackId,
   hasUserConfirmation)`. Note it now shows a confirmation menu unless `hasUserConfirmation = true`;
   both branches are worth covering.
3. **Cache button, uncached book** → `cacheStatus` becomes `CACHING` and
   `cachedFileManager.downloadTracks(...)` is called.
4. **Cache button, already-cached book** → no download is started, and the bottom chooser is shown
   (`showBottomSheet` is now `bottomChooserState`).
5. **Cache button while caching** → cancels: `cacheStatus` returns to `NOT_CACHED` and
   `cancelCaching()` is called exactly once.

### Why this is not trivial

`cacheStatus` is a `DoubleLiveData` derived from `cachedFileManager.activeBookDownloads` and the
`audiobook` LiveData, so tests must drive the *inputs* rather than set the status directly.
The constructor needs 9 collaborators (`ICachedFileManager`, `PlexConfig`, `PrefsRepo`,
`PlexMediaService`, …) — a factory helper is worth writing once, and would serve the other ViewModel
tests cu-44 adds.

Depends on cu-44 because the ViewModel test infrastructure (fakes/fixtures, `PlexMediaService` stub)
belongs there; this task is one concrete consumer of it.

## Acceptance Criteria

- [x] All five behaviours above covered by tests that fail when the behaviour is broken
- [x] `cacheStatus` driven through its real inputs, not stubbed directly
- [x] Coverage baseline rises; ratchet locks the gain

## Implementation Notes

Done as part of cu-57's coverage work. `AudiobookDetailsViewModel` **0% → 56.2%**; suite 409 → 417
tests; overall coverage 20.51% → 20.96%. Every test was verified by sabotaging the source and
watching it fail.

All five behaviours are covered, across two classes:

| # | Behaviour | Where |
|---|---|---|
| 1 | Play starts playback for the right book | `AudiobookDetailsPlaybackTest` |
| 2 | Jump-to-chapter, both branches (prompt / confirmed, with offset and track id) | both classes |
| 3 | Cache button on an uncached book starts the download | `AudiobookDetailsViewModelTest` |
| 4 | Cache button on a cached book prompts instead of downloading | `AudiobookDetailsViewModelTest` |
| 5 | Cache button while caching cancels, exactly once | `AudiobookDetailsViewModelTest` |

Plus offline behaviour the deleted tests never covered: caching and playing while disconnected, and
that a *cached* book still plays with no server.

### The split into two test classes

`pausePlay` builds a real `Bundle` for the transport controls, and `Bundle` is unimplemented in the
unit-test android.jar. Mocking it would be faking the thing under test, so the two playback cases
run on **Robolectric** (`AudiobookDetailsPlaybackTest`) while the rest stay on plain JVM mocks.

This matters beyond tidiness: Robolectric classes are excluded from the PIT run because they report
false SURVIVED, so keeping them separate means the other twelve cases still get a mutation score.
The new class is registered in `pitest { excludedTestClasses }` — the config comment there warns
that forgetting produces a silent lie rather than an error.

### What the task got right, and what it missed

Right: `cacheStatus` does have to be driven through `activeBookDownloads` rather than stubbed, and
a construction helper was worth writing once.

Missed: the task assumed the 9 collaborators were the obstacle. They were not — `MediaServiceConnection`
and `PlexConfig` are final classes and MockK handles those. The actual blocker was `Dispatchers.Main`,
which `asLiveData()` touches during *construction*, so the ViewModel could not be instantiated at
all. `MainDispatcherRule` (`app/src/test/.../util/`) fixes that for all twelve ViewModels.

The dependency on cu-44 was satisfied in the sense that mattered: MockK and `TestDispatcherProvider`
already existed. No shared ViewModel fixture was needed beyond the rule.

### Follow-ups found while writing these

- **cu-92** — `onCacheButtonClick` throws `NoWhenBranchMatchedException` when `cacheStatus` is null,
  which is its state until the LiveData has an observer *and* both sources have emitted. Hit
  immediately by the first test written against it.
- **cu-91** — `updateProgressIfChangingBook` tests the opposite of its name.
