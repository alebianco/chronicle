---
id: cu-179
title: Give the workers a WorkerFactory so they can be tested
status: To Do
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
dependencies: []
priority: medium
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

- [ ] A `WorkerFactory` supplies both workers' dependencies through their constructors
- [ ] `ChronicleApplication` implements `Configuration.Provider` and installs it
- [ ] `DownloadNotificationWorker` has tests via `TestListenableWorkerBuilder`, covering the
      notification-grouping and completion-summary paths
- [ ] `WorkerDispatcherTest`'s exemption list is updated, with a comment recording that cu-152's
      premise changed rather than was wrong
- [ ] Downloads still work on device — this touches the notification path, and cu-81/cu-85/cu-153
      are all about downloads failing quietly
