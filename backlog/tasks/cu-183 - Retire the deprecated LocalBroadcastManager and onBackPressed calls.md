---
id: cu-183
title: Retire the deprecated LocalBroadcastManager and onBackPressed calls
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - maintainability
milestone: m-2
dependencies: []
priority: medium
---

## Description

Two deprecated-API tails the compiler flags on **every build**, so they are pure noise until fixed —
and warning noise is how a real warning gets missed. Found by the 2026-09-06 migration audit; neither
was tracked.

### `LocalBroadcastManager` — 5 files

Deprecated by AndroidX; the recommendation is `LiveData`/flows for in-process events, and this
project already standardised on `StateFlow` (cu-52). Sites: `CurrentlyPlayingFragment`,
`CurrentlyPlayingViewModel`, `AudiobookMediaSessionCallback`, `MediaPlayerService`,
`ActivityComponent`.

**Read cu-21 before touching this.** `ACTION_SLEEP_TIMER_CHANGE` is bidirectional over this
transport — commands travel in and ticks travel out on the *same* action — and the service must not
answer its own broadcast. That constraint has to survive the migration; it is the kind of thing a
straight mechanical replacement breaks silently.

### `Activity.onBackPressed()` — 3 call sites

`CollectionDetailsFragment` (x2) and `AudiobookDetailsFragment` call the deprecated
`requireActivity().onBackPressed()`. **`MainActivity` already uses `onBackPressedDispatcher`**, so
these three are inconsistent with the rest of the app rather than merely dated.

## Acceptance Criteria

- [ ] `LocalBroadcastManager` gone from `app/src/main`, replaced with the project's flow convention
- [ ] The cu-21 bidirectional sleep-timer constraint preserved, with a test that pins it
- [ ] The three `onBackPressed()` calls routed through `onBackPressedDispatcher`
- [ ] Both deprecation warnings gone from a clean build
- [ ] Back navigation verified **on the device** from collection details and book details — this is
      a navigation change, so it needs a human look
- [ ] `./verify.sh` green

## Notes

Closing status is **In Review**, not Done: back navigation is user-visible behaviour and the last
criterion is an on-device check.
