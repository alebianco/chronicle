---
id: cu-94
title: Progress percentage updates before the timeline position
status: Done
labels: [R2, comfort, bug]
dependencies: []
priority: medium
assignee: [claude]
milestone: m-2
---

## Description

Observed on the owner's device during cu-73: while playing, the **percentage text updates
noticeably sooner than the timeline position**. Both describe the same thing, so one is visibly
stale.

They read different sources, with different write latencies:

| Readout | Source | Refreshed by |
|---|---|---|
| `progressPercentageString` | `tracks` (Room) | the progress loop, every second |
| `chapterProgress` / `trackProgress` | `currentlyPlaying.track` | `CurrentlyPlayingSingleton.update()`, from playback callbacks |

The DB write lands first, so the percentage moves while the timeline waits for a callback.

This is the same split cu-87 fixed for the *chapter list versus timeline* disagreement — the same
two sources, a different pair of readouts. Worth fixing as one thing rather than a third time:
either both read `currentlyPlaying`, or both read the DB.

## Acceptance Criteria

- [x] Both readouts derive from a single source, so they cannot disagree.
- [x] The chosen source updates promptly enough that neither readout lags visibly.
- [x] A test that pins the derivation.
- [x] Confirm on the device during playback.

## Implementation Notes

`progressPercentageString` now derives from `currentlyPlaying.track` — the same source the timeline
reads — instead of the `tracks` LiveData backed by Room. The track list still supplies the *total*
duration, which does not change during playback; only the position moved.

The position is the sum of the tracks before the playing one plus the progress into it, so it stays
correct on a multi-track book. `SeekGuardTest` covers that arithmetic (6 cases); dropping the
"tracks before" term fails three of them.

Fixed alongside [[cu-93]], because both were the same defect wearing different clothes: the DB write
lands a tick before the playback callback, so anything reading Room moved before anything reading
`currentlyPlaying`. The remaining ~1s label lag was a separate cause — `onSeekTo` published nothing
and left the labels waiting for the next scheduled tick — and is fixed there.

Third readout pair to hit this after cu-87 (chapter list vs timeline). The lesson worth keeping:
**two views of one fact must read one source**, not two sources that usually agree.
