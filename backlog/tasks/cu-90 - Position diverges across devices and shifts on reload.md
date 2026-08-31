---
id: cu-90
title: Position diverges across devices and shifts unpredictably on reload
status: Done
labels: [R1, trust, bug]
dependencies: [cu-9, cu-14]
priority: critical
assignee: [claude]
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
- **The rule is now decided**: see
  [`decision-16`](../decisions/decision-16%20-%20Track%20viewOffset%20is%20the%20single%20source%20of%20truth%20for%20listening%20position.md).
  Per-track `viewOffset` is authoritative; book progress is derived and never stored as independent
  state; conflicts resolve per track on `lastViewedAt`; "current position" is the furthest started
  track, not the most recently touched one.
- **Verified against the fixtures:** the Plex *album* carries no `viewOffset` at all — only
  `viewedLeafCount`/`leafCount`. Position exists only on tracks, so `Audiobook.progress` is purely
  a local derivation and any disagreement is the app's own.
- **The sharp edge** the ADR flags: a user deliberately re-listening to an earlier chapter must not
  be dragged forward. Likely split — an explicit seek sets position, a sync only moves it forward.
  Get this wrong and the fix is worse than the bug.
- `getActiveTrack()` has other callers (playback start, `CurrentlyPlaying`), so changing its meaning
  touches the player. Give it tests before the position work rides on it.

## Acceptance Criteria

- [x] A written rule for which position is authoritative and how conflicts resolve, recorded as an
      ADR (decision-16)
- [x] `Audiobook.merge` no longer treats `progress` as a value either side can win
- [x] An explicit seek still sets position; a sync only advances it — the re-listen case, tested
- [x] Book progress and active-track progress cannot disagree — one is derived from the other
- [x] A book whose tracks were last touched on different devices reports a coherent position, not a
      jump between two
- [>] Reloading a book's info does not change its reported position when nothing changed
      server-side → **[[cu-73]]** — unit-level only here; needs a real server
- [>] Live checks in [[cu-73]]
- [x] Verify loop green

## Implementation Notes

Implements decision-16. Coverage 13.17% → 13.67%; 22 new tests.

### The rule change: furthest started, not most recently touched

```kotlin
// before
fun List<MediaItemTrack>.getActiveTrack() = maxByOrNull { it.lastViewedAt } ?: get(0)
// after
fun List<MediaItemTrack>.getActiveTrack(): MediaItemTrack {
  val inPlaybackOrder = sorted()
  return inPlaybackOrder.lastOrNull { it.hasBeenStarted() } ?: inPlaybackOrder.first()
}
```

Book progress is the active track's offset plus the durations before it, so choosing by *recency*
made it non-monotonic: device A in track 3 and device B in track 7 meant the reported position
jumped between two unrelated points, and a second device opening an earlier chapter dragged the
position backwards. Choosing the furthest started track makes two devices converge forward.

`hasBeenStarted()` is `progress > 0 || lastViewedAt > 0` — either signal counts, because a track
played to the end can have its offset reset while keeping a timestamp, so neither alone is
sufficient. And the list is `sorted()` first: it arrives from the database and the network in no
guaranteed order, and the old implementation happened not to care.

Note the old KDoc said *"the next song which has not been completed"* — it described this behaviour
already. The code never matched its own documentation.

### A mistake worth recording, because the audit is the load-bearing part

My first attempt zeroed `progress` in `Audiobook.merge`, reasoning that a derived value should have
"no opinion" there. **That would have been a worse bug than the one being fixed.** Auditing the three
`merge` call sites showed `refreshData` merges every book **without loading tracks** and writes the
result straight to `bookDao.insertAll` — so zeroing would have blanked every book's progress in the
library list on every refresh.

`merge` now carries `local.progress` through and never adopts `network.progress`. That is the real
invariant: Plex stores **no album-level position** (verified in the fixtures — the album has only
`viewedLeafCount`/`leafCount`), so a network value carries no information and adopting it invents a
position. The local value is the last derivation, and keeping it is what makes a track-free refresh
safe. Both failure directions are tested, including the zeroing mistake.

### The per-track rule was already right

`MediaItemTrack.merge` — newer `lastViewedAt` wins its offset — already implemented decision-16's
conflict rule *and* its re-listen guard: a deliberate backwards seek writes a newer local timestamp,
so the local offset survives the next sync. It was simply untested and undocumented, so nothing
stopped a future change from removing it. Now 6 tests, verified to bite (forcing the network branch
fails 3, including the seek-backwards case).

### Not done

- **No live verification.** Every mechanism here is inferred from source and fixtures and tested
  against synthetic data. Two real devices are the only way to confirm the lived behaviour → [[cu-73]].
- `getActiveTrack()` has 7 call sites across playback and UI (`MediaPlayerService`,
  `TrackListStateManager`, `AudiobookMediaSessionCallback`, `ProgressUpdater`,
  `MainActivityViewModel`, and `getProgress` itself). The full suite passes and no test depended on
  the old rule, but the *playback* consequences of the changed meaning are untested — starting a book
  now resumes at the furthest started track rather than the most recently touched one, which is the
  intended behaviour but is unproven on a device.
