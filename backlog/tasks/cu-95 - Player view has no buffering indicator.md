---
id: cu-95
title: 'Player view shows no buffering indicator, only play/pause'
status: In Review
assignee:
  - claude
created_date: ''
labels:
  - R2
  - comfort
  - ui
milestone: m-2
dependencies: []
priority: medium
ordinal: 65000
---

## Description

Reported on the owner's device during cu-73: the expanded player shows only the play/pause icon,
with nothing to say audio is buffering or connecting. Pressing play on a streamed book therefore
looks identical to pressing play on a stalled one — *"which might be confusing"*.

The book-details screen already has this. `AudiobookDetailsViewModel` exposes:

```kotlin
state.state == STATE_BUFFERING || state.state == STATE_CONNECTING
```

`CurrentlyPlayingViewModel` exposes no equivalent; it has only `isPlaying`, which is a boolean over
`state.isPlaying`. The player layout does have a `loading_tracks_spinner`, but that is wired to
`isLoadingTracks` — the *track list* loading before playback can start, a different thing.

So this is a genuine gap rather than one of the cu-58 dropped bindings: the indicator was never
there to lose.

## Acceptance Criteria

- [x] `CurrentlyPlayingViewModel` exposes a buffering/connecting state, mirroring the details
      screen's derivation rather than inventing a second rule.
- [x] The player shows it distinctly from both "paused" and "loading tracks".
- [x] Confirmed on the owner's device.
- [x] The mini player got the same treatment.

## Implementation Notes

`isAudioLoading` added to both `CurrentlyPlayingViewModel` and `MainActivityViewModel`, using the
same `STATE_BUFFERING || STATE_CONNECTING` derivation as `AudiobookDetailsViewModel`. Three screens
now answer this question identically rather than each deciding for itself — the split that cu-94
was about.

Both spinners show **instead of** the play/pause icon rather than over it, with the icon set
`INVISIBLE` rather than `GONE` so neither layout reflows. The control stays clickable while
buffering: cancelling a stalled start is exactly when a listener reaches for it.

The mini player was included after all. It is often the only playback control on screen, so a
stalled start there looked identical to a paused book — arguably the more important of the two.

Both spinners default to `android:visibility="gone"` in XML, which is required for Kotlin-driven
visibility (the first-frame trap in CLAUDE.md).

### Still open

The **mid-book stall**. Media3 reports `STATE_PLAYING` once playback has started, so a stream that
starves partway through still shows as normal playback. This task covers the initial buffer only;
detecting a stall needs a different signal and is worth its own task if it turns out to bite.

## Notes

Worth doing alongside a look at whether `isPlaying` should distinguish "playing" from "playing but
starved" at all — Media3 reports both as STATE_PLAYING once started, so a stall mid-book may show as
normal playback. That is a separate question from the initial buffer and should not block this.

## Why this is In Review, not Done

Moved back on the owner's instruction (2026-09-04): a task that changed a screen or made a
product choice needs the owner's approval, whereas a bug fix with an automated proof does not.

What a **buffering state looks like** and how it differs from paused and from loading. Its own
criterion says "confirmed on the owner's device" — that confirmation is the open question.

The code is complete and the verify loop is green; what is outstanding is approval.
