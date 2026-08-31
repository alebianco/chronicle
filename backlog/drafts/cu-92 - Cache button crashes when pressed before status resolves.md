---
id: cu-92
title: Cache button throws NoWhenBranchMatchedException before cache status resolves
status: To Do
labels: [R1, trust, bug]
dependencies: []
priority: medium
assignee: []
---

## Description

`AudiobookDetailsViewModel.onCacheButtonClick` exhausts `cacheStatus.value` over the three
`CacheStatus` values and throws on anything else:

```kotlin
else -> throw NoWhenBranchMatchedException("Unknown cache status. Don't know how to proceed")
```

`cacheStatus` is a `DoubleLiveData` (a `MediatorLiveData`) combining `activeBookDownloads` and
`audiobook`. A MediatorLiveData holds no value until it has an active observer *and* its sources
have emitted, so `cacheStatus.value` is null until then — and the `else` branch is an uncaught
crash, not a no-op.

In the app the Fragment observes the value, so the window is narrow: it needs the download button
pressed before both sources have emitted. It is still a crash on a main-screen control, and it is
reachable — this was hit immediately when writing the first ViewModel test (cu-57), which is how it
was found.

A null status means "not known yet", which should ignore the press (or disable the control), not
terminate the app.

## Acceptance Criteria

- [ ] A press with a null `cacheStatus` does nothing rather than throwing.
- [ ] Unit test covering the null case; the existing three statuses keep their current behaviour.
- [ ] Check the same pattern in the other `when` over `CacheStatus` (there is one in the Fragment's
      binding code) and decide whether it needs the same treatment.
- [ ] Consider disabling the button until the status resolves, so the press cannot be lost silently.
