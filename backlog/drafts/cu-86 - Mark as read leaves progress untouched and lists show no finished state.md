---
id: cu-86
title: Mark as read leaves progress untouched, and lists show no finished state
status: To Do
labels: [R1, trust, bug]
dependencies: []
priority: high
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

- Decide and write down what "mark as read" means: position at 100%, or position untouched with a
  finished flag? Server semantics matter — Plex's `/scrobble` marks watched and the app also has
  `markWatched`. The local and server notions must agree or the next sync undoes the local change.
- Whatever is chosen, "mark as unread" must be its exact inverse; the current pair is not
  symmetrical.
- `viewCount` is a Plex concept. Consider whether the local finished state should be derived from it
  at all, or stored explicitly.

## Acceptance Criteria

- [ ] "Mark as read" has one defined, tested effect on both `progress` and `viewCount`
- [ ] "Mark as unread" is its exact inverse, tested as a round trip
- [ ] The library list distinguishes not-started, in-progress and finished
- [ ] `isCompleted()` either returns something correct or is deleted; no caller gets the
      0%-means-finished answer
- [ ] A sync after marking read does not revert the state (server and local agree)
- [ ] Live check in [[cu-73]]
- [ ] Verify loop green
