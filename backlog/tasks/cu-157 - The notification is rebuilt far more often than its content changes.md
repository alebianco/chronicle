---
id: cu-157
title: The notification is rebuilt far more often than its content changes
status: Done
assignee: []
created_date: '2026-09-04'
labels:
  - R2
  - performance
dependencies:
  - cu-50
milestone: m-2
priority: low
---

## Description

Observed on the tablet while verifying cu-117: starting playback logged

```
NotificationBuilder: Building notification! state=STATE_PLAYING, playing=true
```

**five times within 400 ms**. Each of those builds the whole `NotificationCompat` — five actions,
a `MediaStyle`, an icon lookup and a `cachedArtworkFor` call — and posts it.

`NotificationBuilder` used to carry the beginnings of a guard against exactly this: a
`NotificationData(bookId, trackId, chapterId, playbackState)` data class with a
`currentNotificationMetadata` field to compare against. Neither was ever read or updated — the
`currentID` twin was a `val` computed once at construction, so it could never have worked — and
cu-50 removed them as dead code rather than leave a half-built mechanism looking load-bearing.
This task is where finishing it belongs.

## Why it is filed separately from cu-50

cu-50 fixed *correctness* — the notification now shows the right chapter. This is *cost*, it needs
its own measurement, and the guard has a trap of its own: the notification must still be re-posted
when the **playback state** changes even if the titles have not, or a pause leaves a notification
showing a pause button that no longer matches. So the comparison key has to include the state, which
is presumably why the dead `NotificationData` had a `playbackState` field.

## What to work out

1. **Why five.** `updateNotification` has three callers (`onMetadataChanged`,
   `onPlaybackStateChanged`, `onChapterChange`) and `MediaPlayerService` posts from four more
   places. Find which ones fire in that window before adding a guard — a dedup that hides a
   redundant *caller* is worse than removing the caller.
2. Whether `setOnlyAlertOnce(true)` already makes the extra posts cheap at the system level, so the
   cost is only our own build. Measure rather than assume.
3. If a guard is added, key it on what the notification actually renders — the **session** metadata
   titles (cu-50), the playback state, and the artwork — not on the chapter id alone.

## Acceptance Criteria

- [x] Established which callers produce the burst, with a log or trace
- [x] Redundant *callers* removed where they are genuinely redundant, before any dedup is added
- [x] If a change-detection guard is added, a state-only change (play → pause) still re-posts
- [x] Measured before/after, on the tablet with a 100+ track book (see cu-140's note on why the
      small input flatters this area)

## Related

- [[cu-50]] — fixed the chapter correctness half and removed the dead guard
- [[cu-140]] — the wider per-frame cost during playback; this is a small part of it
- [[cu-110]] — the rule this is an instance of: do not do per-second work whose result cannot change

## Implementation Notes

**29 → 9 builds, measured on the tablet with *Ender's Game* (id 151444, 107 tracks), real server.**
The comparison is like-for-like: both runs saw identical caller counts — 5 `METADATA CHANGE`,
9 `Playback state changed`, 1 `onChapterChange`, the same three states three times each.

**The task's premise was half right, and the half it missed was the bigger one.** It framed this as
"five builds, add a dedup" over three callers. Measuring first found *two independent* causes and a
fourth set of call sites:

1. **Every update built the notification twice.** `buildNotification` is
   `withArtwork(buildNotificationWithoutArtwork(...))`, and `buildNotificationWithoutArtwork`
   *already* calls `setLargeIcon(cachedArtworkFor(...))`. So once a book's bitmap is cached, the
   follow-up re-post — the whole point of the cu-137 split — rebuilds five actions, a `MediaStyle`
   and an icon lookup to attach a bitmap that is already attached. `hasArtworkFor()` now short-
   circuits both re-post sites (`OnMediaChangedCallback.postArtwork`,
   `MediaPlayerService.postNotificationWithArtwork`). This was **half the burst**, and no dedup on
   the caller side would have touched it.
2. **`onPlaybackStateChanged` fires three times per real transition** — 6 of 9 callbacks were
   same-state repeats. That is the part a change-detection guard fixes, and
   `NotificationRebuildTracker` does.
3. **`MediaPlayerService` has four more build sites** the "three callers" framing did not cover.
   These are the `startForeground` deadline paths and are **deliberately left alone**: skipping one
   risks `ForegroundServiceDidNotStartInTimeException` (cu-137). They account for most of the
   remaining 9 (3 × `STATE_NONE`, 5 × `STATE_BUFFERING` during startup).

**The guard keys on the playback state, not just the titles**, which is the trap the task named:
a pause changes no text but must still re-post, or the notification keeps a pause button that no
longer matches. It is also *stateful* ("same as last time") rather than a set of seen keys —
playback returns to previous states constantly, and a set would swallow the resume. Both are
sabotage-verified: dropping `playbackState` from the key fails
`returning to a previous state still re-posts`.

**Verified on device after the change:** pause posts exactly once, resume posts exactly once,
notification content still correct (`Ender's Game - Chapter 14`), no crash, no ANR, no
foreground-service violation in the log.

**Closed to `Done`**: no screen changed and no product choice was made — the notification renders
exactly what it did before, just fewer times. The proof is a measurement a script reproduces plus
six unit tests.
