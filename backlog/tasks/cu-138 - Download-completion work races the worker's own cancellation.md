---
id: cu-138
title: Download-completion work races the worker's own cancellation
status: To Do
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - download
  - bug
milestone: m-2
dependencies: []
priority: high
---

## Description

Found in the 2026-09-02 branch review, re-confirmed against the tree on 2026-09-03. The
download-completion block in `DownloadNotificationWorker` is launched into the worker's **own**
coroutine context and then immediately abandoned, so it races the cancellation that `doWork`'s
return triggers — and usually loses.

`DownloadNotificationWorker.kt:89-113`:

```kotlin
val workerContext = coroutineContext

fetch.getDownloads { downloads ->
  CoroutineScope(workerContext).launch {
    withContext(Dispatchers.IO) {
      ...
      successfulBookIds.forEach { bookId ->
        bookRepository.updateCachedStatus(bookId, true)
      }
      showDownloadsCompleteNotification(downloads)
    }
  }
}

return@withContext Result.success()
```

Two independent problems in that shape:

1. `fetch.getDownloads` is an **async callback** — it has not necessarily fired by the time
   `return@withContext Result.success()` executes on the line below.
2. `CoroutineWorker` cancels its context once `doWork` returns. So even when the callback does fire
   in time, the `launch` block is running in a context that is already being torn down. The
   `updateCachedStatus(bookId, true)` write and the completion notification are both at risk.

### Why it presents intermittently

`CachedFileManager.kt:389-395` performs the *same* write from its Fetch2 group listener, on a
long-lived `externalScope`:

```kotlin
if (downloadSuccess && bookId != null) {
  externalScope.launch { withContext(dispatchers.io) { bookRepository.updateCachedStatus(bookId, true) } }
}
```

That one survives, so it masks the worker's loss non-deterministically. The result is **two owners
of one fact, one of them broken** — which is the "downloaded book reports as not downloaded"
symptom [[cu-85]] chased from the cache-scan side.

### Direction

Two decisions, and the second is the more valuable one:

1. **Mechanical:** await the Fetch2 callback rather than fire-and-forget it (suspend over
   `getDownloads` via `suspendCancellableCoroutine`, or use the suspending API if one exists in the
   Fetch2 version in use), and do the completion work **before** returning `Result.success()`.
   Never `CoroutineScope(coroutineContext)` inside a worker — that is a cancellation-scoped context
   dressed up as a new scope.
2. **Ownership:** decide which of the two sites owns `updateCachedStatus(bookId, true)` and delete
   the other. Keeping both means every future change has to reason about interleaving. The
   `CachedFileManager` listener has the better lifetime; the worker has the better view of "all
   tracks complete". Pick one, record why.

Note [[draft-106]] territory: this worker also hardcodes `Dispatchers.IO` rather than
injecting `DispatcherProvider` (CLAUDE.md convention 4), which is part of why it has no test.

## Acceptance Criteria

- [ ] The completion work runs to completion before `doWork` returns — no work launched into a
      context that is about to be cancelled
- [ ] Exactly one site owns the `cached = true` write; the duplicate is removed and the choice is
      recorded in the code at the surviving site
- [ ] A test proves the write happens: a worker run with all-complete downloads leaves the book
      marked cached, and it fails if the work is moved back after the return — sabotage-verified
- [ ] The completion notification is not silently dropped either
- [ ] Verified on a device: download a book, confirm it reports as downloaded without needing a
      subsequent cache scan to repair the status

## Related

- [[cu-85]] — the same symptom, chased on the cache-scan side
- [[cu-76]] — download integrity/resume on Fetch2
- [[cu-72]] / [[draft-106]] — dispatcher injection for workers, still outstanding here
