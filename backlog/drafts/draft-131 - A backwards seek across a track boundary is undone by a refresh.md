---
id: DRAFT-131
title: A backwards seek across a track boundary is undone by a refresh
status: Draft
assignee: []
labels: [R1, sync, bug, trust]
dependencies: []
priority: high
milestone: m-1
---

## Description

Found running [[cu-73]]'s *"a deliberate seek backwards survives a sync"* item on the live server
(2026-09-03) — the edge [[decision-16]] itself flags as the sharpest.

**Measured, on Ender's Game (107 tracks):**

| step | book progress |
|---|---|
| listening | 2 296 261 ms |
| seek back three chapters, pause | **1 564 209 ms** |
| force a full library refresh | **1 910 473 ms** |

The position jumped **forward 346 seconds** — undoing the seek. Nothing was playing; the refresh
alone did it.

### Why

Track state immediately after the refresh:

```
151449 | index 5 | progress  18 075 | lastViewedAt 1788413872797   <- the seek landed here (newest)
151450 | index 6 | progress   3 092 | lastViewedAt 1788413849136   <- stale, from before the seek
```

`getActiveTrack` is:

```kotlin
val inPlaybackOrder = sorted()
return inPlaybackOrder.lastOrNull { it.hasProgress() } ?: inPlaybackOrder.first()
```

— the **furthest** started track, regardless of recency. Track 6 still holds a stale
`progress = 3092` from before the seek, so it wins over track 5 even though track 5 has the newer
`lastViewedAt`, and the book position is re-derived from track 6.

So seeking backwards *within* a track is safe; seeking backwards **across a track boundary** is
undone as soon as anything re-derives the book position.

### This is not a simple "use lastViewedAt instead"

`getActiveTrack`'s KDoc records why recency was rejected: `markTracksInBookAsWatched` stamps
`lastViewedAt = now` on *every* track, so a recency rule makes a book just marked as read report
itself part-way through — the owner's original *"sometimes it brings to 0%, sometimes at a
different position"*. Both rules are wrong in different situations, which is why this needs a
decision rather than a one-line change.

Candidate directions, none yet chosen:

1. **Clear the progress of tracks after the active one when the user seeks backwards.** Makes the
   stored state match what the user did, and keeps `getActiveTrack` untouched. The seek path knows
   it moved backwards across a boundary, so the information is available at the right moment.
2. **Prefer recency, but only among tracks with `progress > 0`.** Sidesteps the mark-as-read
   problem, since that sets `progress = 0`. Needs checking against the cu-90 convergence case,
   where the *furthest* rule is what makes two devices agree.
3. **Store the book's own active-track pointer** rather than deriving it. Contradicts decision-16's
   "position is owned by the tracks", so it would need that decision revisited.

Option 1 looks least disruptive and does not touch a rule other things depend on. Option 2 is
tempting but risks the two-device convergence that was just verified working.

## Acceptance Criteria

- [ ] A backwards seek across a track boundary survives a full refresh
- [ ] A book marked as read still reports 0%, not a mid-book position (the regression the current
      rule exists to prevent)
- [ ] Two-device convergence still works — B does not drag A's position backwards ([[cu-90]],
      verified working 2026-09-03, must not regress)
- [ ] Unit coverage for all three: backwards seek across a boundary, mark-as-read, and the
      furthest-track convergence case
- [ ] Verified on the live server with a real multi-track book, not a fixture

## Related

- [[cu-73]] — found here; this is the one item of the six that failed
- [[decision-16]] — track `viewOffset` is the source of truth; this is the edge it names
- [[cu-90]] — the convergence behaviour any fix must preserve
