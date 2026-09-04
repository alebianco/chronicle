---
id: CU-152
title: Extend dispatcher injection to the workers
status: Done
assignee:
  - '@claude'
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

## Implementation Notes

**The task's premise did not survive contact, and the answer is "don't".** Three findings, in order:

1. **`PlexSyncScrobbleWorker` names no dispatcher at all.** It was listed as one of the three
   offenders and as the riskiest — it is on the position-reporting path (cu-9) — but it has never
   referenced `Dispatchers`. Nothing to convert.
2. **Injection is not possible as things stand.** WorkManager constructs a worker reflectively
   through its default factory with a fixed `(Context, WorkerParameters)` signature. There is no
   `WorkerFactory` and no `Configuration.Provider` here, so a constructor simply cannot take a
   `DispatcherProvider` without adding both.
3. **The two remaining uses are correct.** `CoroutineWorker.doWork` runs on `Dispatchers.Default`;
   `DownloadNotificationWorker` and `MoveSyncLocationWorker` each wrap genuine blocking file I/O in
   `withContext(Dispatchers.IO)`, which is what that dispatcher is for. Converting them would have
   renamed a correct call, not fixed a bug.

So the question became whether to build the plumbing. `RepositoryDispatcherTest` states the
convention's purpose in its own failure message — *"these repositories still cannot have their
dispatchers controlled by a test"* — and that purpose is unmet either way here: **no worker is unit
tested**, and WorkManager's own harness (`TestListenableWorkerBuilder`) supplies its own executor
regardless. A `WorkerFactory`, a `Configuration.Provider` and three changed constructors would buy
nothing today.

**What shipped instead is a guard that makes the exemption explicit.** `WorkerDispatcherTest` pins
which files may name `Dispatchers` directly, asserts every exempt file really *is* a
`CoroutineWorker` (so the list cannot be quietly extended with a repository), and asserts the
scrobble worker still names none. Sabotage-verified: adding one to the scrobble worker fails two
tests.

Convention 4 in CLAUDE.md said "workers still hardcode dispatchers (cu-72) — don't add more",
implying a conversion was pending. It now says workers are exempt and why, so the next reader does
not re-derive this.

**Revisit if** a worker gains a unit test, or a `WorkerFactory` appears for another reason — at
that point injection becomes both possible and worth something.

**Verification**

- `./verify.sh --format` green, 7 stages. **1145 unit tests**, 0 failures.
- Sabotage-verified the guard.
- No production behaviour changed: this task adds a test and corrects two documents.
