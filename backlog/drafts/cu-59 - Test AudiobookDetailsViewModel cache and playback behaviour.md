---
id: cu-59
title: Test AudiobookDetailsViewModel cache and playback behaviour
status: Draft
assignee: []
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: [cu-44]
priority: medium
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

- [ ] All five behaviours above covered by tests that fail when the behaviour is broken
- [ ] `cacheStatus` driven through its real inputs, not stubbed directly
- [ ] Coverage baseline rises; ratchet locks the gain
