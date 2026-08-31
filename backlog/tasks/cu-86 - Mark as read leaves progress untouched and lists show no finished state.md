---
id: cu-86
title: Mark as read leaves progress untouched, and lists show no finished state
status: Done
labels: [R1, trust, bug]
dependencies: []
priority: high
assignee: [claude]
---

## Description

Owner-reported (2026-08-31): *"mark as read/unread also is not consistent in behaviour. Sometimes
it brings to 0% sometimes at a different position. In the list the book is not always marked with
the expected state."*

Three separate defects behind one symptom.

**1. `setWatched` never touches progress.**

```kotlin
@Query("UPDATE Audiobook SET viewCount = viewCount + 1 WHERE id = :bookId")
suspend fun setWatched(bookId: String)
```

It only increments `viewCount`. A book marked read keeps whatever position it had — hence
"sometimes at a different position". `resetBookProgress` (`SET progress = 0`) exists but is not
called from this path, which is why the behaviour looks random: whether progress moves depends on
which code path ran, not on the user's action.

**2. The library list has no finished state at all.** `AudiobookAdapter` renders exactly two
states:

```kotlin
binding.notPlayedDogEar.isVisible = audiobook.viewCount == 0L && audiobook.progress == 0L
binding.bookProgress.isVisible = audiobook.progress > 0L
```

There is no "finished" indicator, so a book marked read shows as *in progress* at its stale
position. This is the "not always marked with the expected state" half.

**3. `Audiobook.isCompleted()` is wrong, and dead.**

```kotlin
fun Audiobook.isCompleted(): Boolean =
  progress < 10.seconds.inWholeMilliseconds || progress > (duration - 2.minutes.inWholeMilliseconds)
```

The first clause makes a book at **0% report completed**. It currently has **no callers**, so it is
latent rather than live — but it is exactly the helper someone would reach for when fixing defect 2.
Fix or delete it; do not leave it as a trap.

Related: [[cu-9]] fixed a *different* half of this family — `setWatched` firing mid-playback and
resetting progress. This is the deliberate user-initiated path.

## Design notes

- **The framing is decided**: see
  [`decision-16`](../decisions/decision-16%20-%20Track%20viewOffset%20is%20the%20single%20source%20of%20truth%20for%20listening%20position.md).
  Completion is an **explicit fact, separate from position** — a book is finished because it was
  marked finished, not because its position is near the end. Verified in the fixtures: Plex keeps
  `viewOffset` and `viewCount` independent (track 2001 has offset 1500 with viewCount 0).
- What remains for this task is the *behaviour*: what "mark as read" does to position. Pick one and
  make "mark as unread" its exact inverse. The local and server notions must agree, or the next sync
  undoes the local change.
- Whatever is chosen, "mark as unread" must be its exact inverse; the current pair is not
  symmetrical.
- `viewCount` is a Plex concept. Consider whether the local finished state should be derived from it
  at all, or stored explicitly.

## Acceptance Criteria

- [x] "Mark as read" has one defined, tested effect on both `progress` and `viewCount`
- [x] "Mark as unread" is its exact inverse
- [x] The library list distinguishes not-started, in-progress and finished
- [x] `isCompleted()` returns something correct; no caller gets the 0%-means-finished answer
- [>] A sync after marking read does not revert the state (server and local agree) → **[[cu-73]]**
      — unit-level only here; the server round trip needs a real account
- [>] Live check in [[cu-73]]
- [x] Verify loop green

## Implementation Notes

Coverage 13.67% → 13.88%; 19 new tests.

### A correction to this task's own analysis

The draft claimed `setWatched` "never touches progress". **That was wrong** — I had read the DAO
query (`viewCount + 1`) without the repository method around it, which also calls
`resetBookProgress`. The real defects were different, and one of them I had just introduced myself.

### The regression cu-90 created, caught here

[[cu-90]] made `getActiveTrack` return the furthest *started* track, where "started" meant
`progress > 0 || lastViewedAt > 0`. But `markTracksInBookAsWatched` sets **every** track to
`progress = 0, lastViewedAt = now` — so every track counted as started, `getActiveTrack` returned
the **last** one, and a book just marked as read reported itself at 50% of the way through
(measured: three-track book, `progress = 3000` of 6000). Exactly the owner's *"sometimes it brings to
0%, sometimes at a different position"*, freshly re-created.

Fixed by narrowing the predicate to `progress > 0` alone. A timestamp is not a position, and neither
is a track-level `viewCount`, because marking a book played sets both on every track. A track played
through and reset is therefore not a position — correct, since the position is in whatever later
track has a real offset.

### `isCompleted()` was inverted

```kotlin
// before: an unstarted book reported as finished
progress < 10.seconds || progress > (duration - 2.minutes)
```

The first clause is the **0% case**, not the finished one. Now: an explicit `viewCount > 0` is
authoritative (decision-16 — completion is a fact, not an inference), otherwise a position within
`BOOK_FINISHED_END_WINDOW` of the end. Also guards `duration <= 0`, which would have made the window
check trivially true for an unloaded book.

### The watched/unwatched pair was asymmetrical

`setWatched` marked the book *and* its tracks; `setUnwatched` touched only the book. So after
un-marking, the tracks kept `viewCount` and their timestamps, and which state showed depended on
what had run last. Now:

- `markTracksInBookAsWatched` also sets `viewCount = 1` — the explicit completion fact.
- `markTracksInBookAsUnwatched` is added as the exact inverse, clearing `viewCount`, `progress` **and**
  `lastViewedAt`. Clearing the timestamp matters: "listened just now with no progress" would win
  every subsequent merge against the server and keep re-clearing a position set elsewhere.
- `setUnwatched` calls `resetBookProgress`, mirroring `setWatched`.
- `AudiobookDetailsViewModel` calls both halves on both paths.

### The library list now has three states

`bindProgressIndicators` replaces duplicated logic at two call sites (grid and details list):
not-started shows the dog-ear, finished shows a full bar, in-progress shows the real position. Also
guards `ProgressBar.max = 0`, which silently renders any progress as complete.

A finished book previously showed as *in progress* at its stale position — and after mark-as-read
reset that to 0, as **not played**, indistinguishable from a book never opened.

**A full bar is deliberately modest.** A distinct "finished" badge is a visual-design decision and
CLAUDE.md puts branding with the owner, so this keeps the fix to behaviour rather than inventing UI.
Worth revisiting if the owner wants a clearer indicator.

### Test coverage, after an audit prompted by the owner

`markTracksInBookAsUnwatched` — added by this task — had **no test**, and neither did `setUnwatched`.
`WatchedRoundTripTest` now covers the pair as a round trip: 6 tests, verified to bite.

The most valuable of them is cross-task: every track marked watched carries `lastViewedAt = now`, so
if that counted as "started", `getActiveTrack` would return the *last* track and the book would
report itself part way through — 3000ms of 6000 for the fixture. That is the regression [[cu-90]]
introduced and this task fixed, and it is only visible when the two are exercised **together**.
Sabotaging `hasProgress()` back to `progress > 0 || lastViewedAt > 0` fails that test, so the
interaction is now permanently guarded rather than relying on someone remembering it.

### Not done

- **No live verification.** Whether the server agrees after a mark-as-read round trip — Plex's
  `/scrobble` and `unscrobble` versus the local state — is untested against a real server → [[cu-73]].
- A dedicated finished badge, if wanted, is a design call.
