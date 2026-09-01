---
id: cu-92
title: Cache button throws NoWhenBranchMatchedException before cache status resolves
status: Done
labels: [R1, trust, bug]
dependencies: []
priority: medium
assignee: [claude]
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

- [x] A press with a null `cacheStatus` does nothing rather than throwing.
- [x] Unit test covering the null case; the existing three statuses keep their current behaviour.
- [x] Check the same pattern in the other `when` over `CacheStatus` (there is one in the Fragment's
      binding code) and decide whether it needs the same treatment.
- [x] Consider disabling the button until the status resolves, so the press cannot be lost silently.

## Implementation Notes

Owner decision, 2026-09-01: **ignore the press *and* disable the button** — both halves, so the
press cannot be silently lost.

- `onCacheButtonClick`'s `else -> throw` became an explicit `null ->` branch that logs and returns.
  Making it `null` rather than `else` keeps the `when` exhaustive over `CacheStatus`, so adding a
  fourth status is still a compile error rather than a silent fall-through.
- `AudiobookDetailsFragment` now drives `isEnabled` from the same observer that already drives the
  spinner, with `android:enabled="false"` in `fragment_audiobook_details.xml` as the matching
  default. Without the XML default the button renders enabled for one frame before the observer
  first fires — the first-frame trap in CLAUDE.md's ViewBinding notes.

The guard stays even with the button disabled: the two protect different things. `isEnabled` is
view state that a future layout change or an accessibility path could bypass, while the `when` is
the last line before the crash.

The Fragment's other `when` over `CacheStatus` needed nothing — it compares against `CACHING` with
an `if`, so a null simply takes the else branch.

The spinner needed no XML default: it is a `ProgressBar` whose visibility is already status-driven
and starts hidden, so it cannot be pressed before the status resolves.

Test verified by restoring the `throw`: the new case fails immediately. It deliberately does **not**
observe `cacheStatus`, since an unobserved MediatorLiveData is exactly the null state being guarded.

### Not covered

`isEnabled` itself. It is Fragment code, which the unit suite cannot reach — one more item for the
instrumented suite ([[cu-54]]).
