---
id: cu-118
title: A failed library refresh deleted books and crashed the error handler
status: Done
assignee: [claude]
created_date: '2026-09-02'
updated_date: '2026-09-02'
labels: [R1, bug, trust, data-loss]
dependencies: []
priority: high
milestone: m-1
---

## Description

Found in the 2026-09-02 adversarial review of `feature/agentic-dev`. Three defects on one path,
the first two of which destroy listening position — the property R1 exists to protect.

### 1. A failed refresh deleted the local library

`BookRepository.refreshDataPaginated` caught a page-fetch failure, logged it at `Timber.i`, and
**fell through to the prune**. Every book absent from an incomplete fetch was then deleted, taking
its `progress` with it. A network drop after page 3 of 12 removed the other nine pages' books; a
total failure removed the entire library.

The comment above the catch said "quit on network failure" — it described `refreshData`'s
behaviour, which genuinely does `?: return`. This method never did.

The missing-library branch had the same shape: `library?.id ?: return@withContext` returns from the
*inner lambda* only, so it also reached the deletion.

**The safety test existed and covered the wrong method.** `BookRepositoryRefreshTest` had nine
cases, all against `refreshData` — which nothing in the app calls — including one named
`a network failure leaves the local library untouched`. `LibrarySyncRepository.refreshLibrary` is
the only refresh entry point and calls `refreshDataPaginated`, which had **zero** tests.

### 2. The error handler crashed instead of reporting

`LibrarySyncRepository.refreshLibrary`'s catch called `Toast.makeText(...).show()` on
`dispatchers.io`. `Toast.show()` off the main thread throws
`Can't create handler inside thread ... that has not called Looper.prepare()`. So the handler for a
sync failure was itself a crash — on exactly the trigger that also pruned the library.

### 3. A book was scrobbled finished while barely started

`ProgressReporter.markFinishedIfNeeded` and `ProgressUpdater` compared
`bookDuration - progress < BOOK_FINISHED_END_OFFSET_MILLIS` with no `bookDuration > 0` guard. With
duration 0 the window check is trivially true. It is reachable: `lookupBookDuration` derives from
`getTracksForAudiobookAsync`, whose query filters `cached >= :offlineMode`, so an *uncached* book in
offline mode has no tracks and therefore no duration — and a book paused at 3% got marked complete
on the server, `viewCount` incremented and `viewOffset` cleared. That is the damage cu-73 observed
and cu-98 had to repair.

`Audiobook.isCompleted()` has carried this guard all along, with a comment explaining why. The rule
was learned once and not applied at the two sites that write to the server.

## Acceptance Criteria

- [x] An incomplete fetch aborts before the prune; partial pages are discarded, not merged
- [x] A missing configured library deletes nothing
- [x] `BookRepositoryRefreshTest` covers `refreshDataPaginated`, the method the app calls
- [x] The refresh-failure message reaches the user without crashing
- [x] `bookDuration > 0` guard at both scrobble sites, sabotage-verified
- [x] Verify loop green

## Implementation Notes

Each fix was reproduced by a failing test first:

- total failure removed `[1001, 1002]`; failure after page 1 removed `[1002, 1003]`; no configured
  library removed `[1001]` — six new cases against the live method
- the scrobble guard is **sabotage-verified**: deleting it fails exactly the two new
  `MarkFinishedBoundaryTest` cases

The `Toast` is replaced by an `Event<Int>` string resource the repository posts and Home, Library
and Collections each observe, reusing the pattern already in those fragments. That removes an
`Injector` service-locator call and a hardcoded user-facing string at the same time.

Two things landed alongside, both on the same path:

- the post-refresh re-derive was an N+1 write with an O(books x tracks) filter (~20M comparisons
  for a 2000-book library, one transaction per book). Now one `groupBy` and a single
  `@Transaction` batch via `BookDao.updateTrackDataForAll`.
- a failed refresh no longer runs the re-derive at all — nothing was fetched, so there is nothing
  to re-derive. Collections still refreshes, since it is an independent fetch that handles its own
  failure.

### Follow-up

`refreshData` is now dead code with a well-tested suite pointing at it. Either delete it or make it
the method in use; keeping both, with the tests on the unused one, is how this happened.
