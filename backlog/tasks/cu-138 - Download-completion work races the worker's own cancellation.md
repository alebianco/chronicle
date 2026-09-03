---
id: cu-138
title: Download-completion work races the worker's own cancellation
status: In Review
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

- [x] The completion work runs to completion before `doWork` returns — no work launched into a
      context that is about to be cancelled. `awaitDownloads()` suspends on the Fetch2 callback.
- [x] Exactly one site owns the `cached = true` write; the duplicate is removed and the choice is
      recorded in the code at the surviving site — **`CachedFileManager` owns it**, reasoning in a
      comment at that site. The worker's now-dead `Injector.get().bookRepo()` went with it.
- [x] A test proves the write happens: a worker run with all-complete downloads leaves the book
      marked cached, and it fails if the work is moved back after the return — sabotage-verified
      — **partly by different means than specified.** Driving a real Fetch2 worker is instrumented
      territory (cu-54), so `DownloadCompletionOwnershipTest` pins the two *structural* properties
      (no self-cancelling scope, single owner) following `CachedFileManagerScopeTest`'s precedent,
      and the write itself is verified **on a device against the real database** — stronger evidence
      than a mocked worker would have given. Both sabotage-verified.
- [x] The completion notification is not silently dropped either — it was in the same racing
      block, so the same fix covers it.
- [x] Verified on a device: download a book, confirm it reports as downloaded without needing a
      subsequent cache scan to repair the status

## Implementation Notes

**The ownership decision: `CachedFileManager` owns the write.** Its Fetch2 group listener is a
`@Singleton` on an injected `externalScope` whose lifetime is not tied to any unit of work, and it
is already the reconciliation authority for cache state — it owns the scan, the track-level writes
and the uncache path. `DownloadNotificationWorker` is a notification renderer whose reason to exist
ends when its notification does; it was the wrong place for a durable fact. Removing its copy also
removed a dead `Injector.get().bookRepo()` service-locator call.

**The mechanical fix.** `awaitDownloads()` wraps `Fetch.getDownloads` in
`suspendCancellableCoroutine`, so `doWork` completes its work *before* returning instead of
launching it into a context about to be cancelled. It resumes only while the continuation is
active, so stopping the worker cancels rather than leaking. The file's own header TODO asked for
exactly this ("write extension functions to turn fetch calls into suspend functions").

The old inner `withContext(Dispatchers.IO)` was redundant — `doWork` already wraps everything in
`withContext(Dispatchers.IO)` — so it went too.

**Measured on device** (Samsung SM-A336B, API 36, live server, real download of Ender's Game):

| time | event |
|---|---|
| `11:24:55.234` | `CachedFileManager$2$onFinished`: "Book download success for 151444" — the owning write |
| `11:24:56.024` | worker returns `SUCCESS`, **0.8 s later** |

The write now lands *before* the teardown, from a single owner. Confirmed against the real
database: `isCached = 1` for book 151444 — and note it took pulling `book_db-wal` alongside
`book_db` to see it, since the value sits in the write-ahead log; a copy of the main file alone
reports a stale `0`. Home then showed an **"AVAILABLE OFFLINE"** shelf containing the book, and the
details screen offered "DELETE CACHED FILES?", so the state propagated to the UI. Deleting returned
it to `isCached = 0`, so the round trip works and the device is back to its starting state.

**Sabotage-verified.** Reintroducing the original shape as live code —
`CoroutineScope(workerContext).launch { … updateCachedStatus … }` — fails both structural tests.
The tests strip comments before scanning, because the fix documents the bug by quoting the old
expression and a naive scan would otherwise match the explanation instead of the code; that
distinction is itself sabotage-verified. `DownloadCompletionOwnershipTest` also carries a
guards-the-guard test, which the `UnguardedMenuAccessTest` precedent was missing.

**Found while verifying, filed separately:** the book-details download control keeps
`contentDescription="@string/download"` even when the book is cached — a static XML string that
never follows the icon. Not a regression from this work (the cached state itself is correct); filed
as [[DRAFT-139]], and it probably belongs inside [[cu-47]] rather than done alone.


## Related

- [[cu-85]] — the same symptom, chased on the cache-scan side
- [[cu-76]] — download integrity/resume on Fetch2
- [[cu-72]] / [[draft-106]] — dispatcher injection for workers, still outstanding here
