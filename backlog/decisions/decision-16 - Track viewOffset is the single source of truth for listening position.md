---
id: decision-16
title: Track viewOffset is the single source of truth for listening position
status: accepted
date: '2026-08-31'
type: technical
author: claude
---

## Context

The owner reports (2026-08-31) that *"same account on different devices reports WILDLY different
positions/states for the available books"*, that *"reloading a book info sometimes makes the current
position and book state change unpredictably"*, and separately that *"mark as read/unread is not
consistent in behaviour — sometimes it brings to 0%, sometimes at a different position"*. Named as
**the most annoying and confusing issue as a user**, needing a solid fix rather than a patch.

These are one problem: **nothing in the codebase states where listening position is owned**, so
three mechanisms answer the question differently and whichever ran last wins.

### What Plex actually gives us

Verified against the fixture pack, not assumed. Plex models an audiobook as an *album of tracks*:

| Level | Fields present | Position? |
|---|---|---|
| Album (book) | `lastViewedAt`, `viewCount`, `viewedLeafCount`, `leafCount` | **No `viewOffset`** |
| Track | `viewOffset`, `lastViewedAt`, `viewCount` | **Yes** |

So the server has **no authoritative book position at all** — only per-track offsets, plus a
completion count (`viewedLeafCount` of `leafCount`). `Audiobook.progress` is therefore a purely
*derived local value*, and any disagreement between it and the tracks is the app's own doing.

Note also that in the fixtures track 2001 has `viewOffset: 1500` with `viewCount: 0`: on the server,
**"has a position" and "is finished" are independent facts.** The app conflates them.

### The three conflicting mechanisms

1. **`Audiobook.merge` preserves `local.progress`** in both branches, deliberately and with a
   comment explaining why.
2. **`syncAudiobook` then discards that**:
   ```kotlin
   Audiobook.merge(network, local, forceNetwork).copy(progress = tracks.getProgress(), …)
   ```
   Book progress is always recomputed from tracks, whose own `MediaItemTrack.merge` may have
   resolved the opposite way. Result: book-level and track-level progress can disagree, and which
   one the user sees depends on fetch ordering. This is the "changes unpredictably on reload" report.
3. **`getActiveTrack()` picks by recency across devices**:
   ```kotlin
   fun List<MediaItemTrack>.getActiveTrack() = maxByOrNull { it.lastViewedAt } ?: get(0)
   fun List<MediaItemTrack>.getProgress() = getActiveTrack().progress + getTrackStartTime(getActiveTrack())
   ```
   Book position is *the most recently touched track's* offset plus preceding durations. If device A
   listened in track 3 and device B in track 7, the larger `lastViewedAt` decides — so the reported
   position jumps between two unrelated points instead of converging. This is the "wildly different
   positions" report.

Separately, completion state is incoherent: `setWatched` only does `viewCount + 1` and never touches
position; the library list renders only not-started vs in-progress, with **no finished state**; and
`Audiobook.isCompleted()` returns `true` at 0% progress (currently uncalled, so latent).

[[cu-14]] already fixed a real fourth cause — server `lastViewedAt` in seconds vs local millis, which
made a second device's position *always* lose. That fix is in place; these three survive it.

## Decision

**Per-track `viewOffset` (`MediaItemTrack.progress`) is the single source of truth for listening
position. Book position is a pure function of the tracks and is never stored as independent state.**

Concretely:

1. **`Audiobook.progress` becomes derived, not authoritative.** It may be cached for list display,
   but it is only ever *computed* from the tracks — never merged, never written from a second path.
   `Audiobook.merge` stops carrying `progress` as a preserved local field, removing the
   contradiction with `syncAudiobook`.

2. **Conflict resolution happens per track, on `lastViewedAt`, and nowhere else.** One rule, one
   place: for each track, the side with the newer `lastViewedAt` wins its `viewOffset`. Because both
   sides are now in millis (cu-14), this comparison is meaningful.

3. **"Current position" is the furthest coherent point, not the most recently touched track.**
   `getActiveTrack()` by `max(lastViewedAt)` is wrong for a *book*: it makes position non-monotonic
   across devices. Replace with the last track that has been started — i.e. the highest-index track
   with a non-zero offset or a completed predecessor — so two devices listening at different points
   converge forward rather than oscillating. A book is listened to front-to-back; position should not
   move backwards because another device opened an earlier chapter.

4. **Completion is an explicit fact, separate from position.** The server already treats them
   separately (`viewOffset` and `viewCount` are independent). A book is finished when it is *marked*
   finished, not when its position happens to be near the end. "Mark as read" sets completion; what
   it does to position is one defined behaviour, not two.

5. **`isCompleted()` is fixed or deleted.** Its 0%-means-finished clause must not survive to be
   reached by the list once a finished state is added.

## Consequences

**Good**

- One rule, in one place, testable: conflicting per-track `lastViewedAt` values have a defined
  outcome, so the multi-device case becomes a unit test rather than a field report.
- Position stops moving backwards, which is the specific behaviour that reads as broken.
- Book and track progress can no longer disagree, because there is only one stored value.
- Matches the server's own model instead of inventing a book-level position Plex does not have.

**Costs and risks**

- `getActiveTrack()` has other callers (playback start, `CurrentlyPlaying`). Changing its meaning
  touches the player, so it needs its own tests before the position work rides on it.
- "Furthest started" is not automatically right either: a user who deliberately re-listens to an
  earlier chapter should not be dragged forward. **The re-listen case must be handled explicitly** —
  most likely by letting an *explicit seek* set position while a *sync* only moves it forward. This
  is the sharpest edge of the decision and needs the live pass to confirm.
- Deriving book progress on read has a cost for large libraries ([[cu-51]]); a cached derived column
  is acceptable, provided it is only ever written by the derivation.
- Changing what "mark as read" does to position is user-visible. It should be the *defined* behaviour
  even if it differs from today's accidental one.

**Explicitly not decided here**

- Whether `viewedLeafCount`/`leafCount` or a local flag stores completion — an implementation choice
  for [[cu-86]], as long as it is one place.
- Whether to adopt Plex's on-deck or `/status/sessions` endpoints. [[cu-14]] already found
  `/status/sessions` detects only *live* sessions, so it does not solve asynchronous drift.

## Implementation

- [[cu-90]] — position: make book progress derived, one conflict rule, fix `getActiveTrack`.
- [[cu-86]] — completion: define mark-as-read/unread, add the finished state to the list, fix
  `isCompleted()`.
- [[cu-73]] — live verification. **None of this is provable without two real devices**: every
  mechanism here is inferred from source and fixtures.
