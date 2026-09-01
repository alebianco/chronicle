---
id: cu-95
title: Player view shows no buffering indicator, only play/pause
status: Draft
labels: [R2, comfort, ui]
dependencies: []
priority: medium
assignee: []
milestone: m-2
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

- [ ] `CurrentlyPlayingViewModel` exposes a buffering/connecting state, mirroring the details
      screen's derivation rather than inventing a second rule.
- [ ] The player shows it distinctly from both "paused" and "loading tracks" — a spinner over the
      play/pause control is the obvious placement, but check it does not fight the existing
      `loading_tracks_spinner`.
- [ ] Confirm on the device with a streamed (not downloaded) book, where buffering is actually
      visible, and over a deliberately slow connection.
- [ ] Consider whether the mini player needs the same treatment; it has the same play/pause control.

## Notes

Worth doing alongside a look at whether `isPlaying` should distinguish "playing" from "playing but
starved" at all — Media3 reports both as STATE_PLAYING once started, so a stall mid-book may show as
normal playback. That is a separate question from the initial buffer and should not block this.
