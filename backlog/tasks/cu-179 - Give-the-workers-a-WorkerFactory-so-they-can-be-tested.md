---
id: cu-179
title: Give the workers a WorkerFactory so they can be tested
status: In Review
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
milestone: m-2
dependencies: []
priority: medium
ordinal: 71000
---

## Description

`androidx.work:work-testing` is **already declared and already wired** into
`app/build.gradle.kts` (`testImplementation(libs.work.testing)`) — and entirely unused. The only
mention of `TestListenableWorkerBuilder` in the test tree is inside a *comment* in
`WorkerDispatcherTest`.

Workers are **2,212 missed instructions**, `DownloadNotificationWorker` alone accounting for 1,420
at 0%.

The builder does not help on its own, because both workers resolve `Injector.get()` in **field
initialisers**:

```kotlin
private val fetch: Fetch = Injector.get().fetch()          // DownloadNotificationWorker
private val prefsRepo = Injector.get().prefsRepo()         // MoveSyncLocationWorker
```

So construction needs the whole Dagger graph whatever the test builder does.

## This revisits cu-152 deliberately

cu-152 exempted workers from `DispatcherProvider` injection, reasoning that a `WorkerFactory` plus
`Configuration.Provider` "would buy nothing while no worker is unit-tested and
`TestListenableWorkerBuilder` supplies its own executor anyway."

**That premise has changed**: the tool is present, the workers are the third-largest untested body,
and the plumbing is now the only thing between them and coverage. The exemption was correct when
written and is worth re-deciding rather than silently overturning — `WorkerDispatcherTest` pins the
exemption list, so any change here must update it and say why.

## Acceptance Criteria

- [x] A `WorkerFactory` supplies both workers' dependencies through their constructors
- [x] `ChronicleApplication` implements `Configuration.Provider` and installs it
- [ ] `DownloadNotificationWorker` has tests via `TestListenableWorkerBuilder`, covering the
      notification-grouping and completion-summary paths
- [x] The exemption list is updated (in `ServiceLocatorUsageTest`, which is what pins it), with a
      comment recording that cu-152's premise changed rather than was wrong
- [ ] **Downloads still work on device** — not verified; see notes. cu-81/cu-85/cu-153
      are all about downloads failing quietly

## Implementation Notes

`ChronicleWorkerFactory` passes both workers their dependencies through constructors;
`ChronicleApplication` implements `Configuration.Provider` and installs it. The manifest removes
WorkManager's default App Startup initialiser, without which the provider is never read and the
factory silently never installs.

`MoveSyncLocationWorker` came **off** the service-locator exemption list entirely. Two entries
remain and neither is constructor injection: `DownloadNotificationWorker` keeps one call in its
**companion** `enqueue` helper (a static entry point that schedules the worker — injecting it would
thread a `WorkManager` through every caller), and `PlexSyncScrobbleWorker` has not been converted.

`ServiceLocatorUsageTest` **failed on the change**, correctly — it detects an exemption that is no
longer needed, which is exactly what it is for.

### cu-152 re-decided, not overturned

That task exempted workers because "a `WorkerFactory` would buy nothing while no worker is
unit-tested and `TestListenableWorkerBuilder` supplies its own executor anyway." Correct when
written. The premise changed: `work-testing` was already declared in the build and unused, and the
workers were among the largest untested bodies left. The comment in the exemption list records
this so the reasoning is not lost.

### Verified, and not

On the tablet: WorkManager initialises through the new provider (`WM-Schedulers: Created
SystemJobScheduler`), the app launches, tabs navigate, **zero `AndroidRuntime` errors**.

**A download was not exercised.** That is the risky half — cu-81, cu-85 and cu-153 are all about
downloads failing quietly — and it needs a real book fetched to a real volume. Left `In Review`
with the criterion unticked rather than claimed.
