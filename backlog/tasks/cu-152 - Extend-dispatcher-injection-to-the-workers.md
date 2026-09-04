---
id: CU-152
title: Extend dispatcher injection to the workers
status: To Do
assignee: []
created_date: '2026-09-02'
labels:
  - R2
  - architecture
  - debt
milestone: m-2
dependencies: []
priority: low
---

## Description

Split out of [[cu-72]], which converted the five repositories and the three player classes but
deliberately left the workers alone.

Three remain hardcoding `Dispatchers.*`:

- `PlexSyncScrobbleWorker`
- `DownloadNotificationWorker`
- `MoveSyncLocationWorker`

## Why this is not just more of the same

cu-72's own criterion said worker threading must be **reviewed against WorkManager's executor
contract before converting**, and that still holds. WorkManager runs a `CoroutineWorker` on its own
executor and cancels it through its own lifecycle; an injected dispatcher that disagrees can leave
work running after cancellation or, worse, complete work the framework believes failed.

That is a different risk from the player layer, where the danger was a leaked scope. Here the danger
is disagreeing with a framework that is also managing retries and backoff — and `PlexSyncScrobbleWorker`
is on the path that protects the listener's position (cu-9).

## Approach

1. Read WorkManager's contract first: `CoroutineWorker.doWork` already runs on
   `Dispatchers.Default` via `coroutineContext`, so a `withContext(dispatchers.io)` inside it may be
   redundant rather than wrong.
2. Use `TestListenableWorkerBuilder` — the framework's own harness — rather than constructing the
   workers directly.
3. Convert one worker, verify retry and cancellation still behave, then the rest.

## Acceptance Criteria

- [ ] WorkManager's executor contract reviewed and the finding recorded here
- [ ] `work-testing` used (it is already on the classpath and currently unused — 2,323 missed
      instructions across the two download workers)
- [ ] Cancellation and retry verified, not just the happy path
- [ ] `RepositoryDispatcherTest`'s scan widened to the workers
- [ ] No behaviour change in the cu-9 position round-trip

## Notes

`work-testing` being on the classpath and unused was flagged in the pre-R2 review as paid-for
coverage nobody had collected. This task is where that gets spent.
