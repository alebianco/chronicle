---
id: cu-94
title: Progress percentage updates before the timeline position
status: Draft
labels: [R2, comfort, bug]
dependencies: []
priority: medium
assignee: []
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

- [ ] Both readouts derive from a single source, so they cannot disagree.
- [ ] The chosen source updates promptly enough that neither readout lags visibly.
- [ ] A test that pins them to the same source, so a future change cannot re-split them.
- [ ] Confirm on the device during playback.
