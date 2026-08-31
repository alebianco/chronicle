---
id: cu-90
title: Position diverges across devices and shifts unpredictably on reload
status: To Do
labels: [R1, trust, bug]
dependencies: [cu-9, cu-14]
priority: critical
---

## Description

Owner-reported (2026-08-31): *"same account on different devices reports WILDLY different
positions/states for the available books. Also reloading a book info sometimes makes the current
position and book state change unpredictably."*

[[cu-14]] fixed one cause — server `lastViewedAt` is in **seconds** while the local DB stores
millis, so the server value was ~1000× smaller and could never win a `network > local` comparison,
meaning a second device's position was silently discarded on every refresh. Both `Audiobook` and
`MediaItemTrack` now convert via `plexTimestampToMillis`. That fix is in place, and the symptom
persisting means there is more than one cause.

Two further mechanisms, both in the sync path:

**1. `syncAudiobook` overwrites the progress that `merge` just carefully preserved.**

```kotlin
Audiobook.merge(network, local, forceNetwork)   // preserves local.progress in both branches
  .copy(
    progress = tracks.getProgress(),            // ...then discards it
    ...
  )
```

So book progress is always recomputed from the tracks, whose own `MediaItemTrack.merge` may have
resolved the other way. Book-level and track-level progress can therefore disagree, and which value
the user sees depends on fetch ordering. That is a direct fit for "reloading a book info sometimes
makes the position change unpredictably".

**2. "Active track" is whichever track was touched most recently, on any device.**

```kotlin
fun List<MediaItemTrack>.getActiveTrack() = maxByOrNull { it.lastViewedAt } ?: get(0)

fun List<MediaItemTrack>.getProgress(): Long =
  getActiveTrack().progress + getTrackStartTime(getActiveTrack())
```

Book progress is *the active track's* position plus the durations before it. If device A listened in
track 3 and device B in track 7, whichever `lastViewedAt` is larger decides — so the reported book
position jumps between two unrelated points rather than converging. With per-track progress also
merging independently, a book can show a position that no device ever actually reached.

## Design notes

- **Decide where the authoritative position lives**: the book, or the active track. Right now both
  exist and disagree. One must be derived from the other, in one direction only.
- Conflict resolution needs a rule stated in words before code. "Most recent `lastViewedAt` wins"
  is defensible per *track*, but summing across independently-merged tracks does not produce a
  coherent book position.
- Plex's own view (`viewOffset` on the book, and the on-deck/timeline endpoints) is worth comparing
  against — the app may be recomputing something the server already answers.
- Watch the interaction with [[cu-9]]: progress reports are now awaited and retried, so a
  second device's writes land more reliably than before, which can make this *more* visible.
- Worth an ADR in `backlog/decisions/` — this is a durable architectural choice about where
  listening position is owned, not a local fix.

## Acceptance Criteria

- [ ] A written rule for which position is authoritative and how conflicts resolve, recorded as an
      ADR
- [ ] Book progress and active-track progress cannot disagree — one is derived from the other
- [ ] A book whose tracks were last touched on different devices reports a coherent position, not a
      jump between two, covered by a test with per-track `lastViewedAt` set to conflicting values
- [ ] Reloading a book's info does not change its reported position when nothing changed server-side
- [ ] Live checks in [[cu-73]]: listen on device A, stop, open on device B, confirm the position is
      adopted; then reload book info repeatedly on one device and confirm the position is stable
- [ ] Verify loop green
