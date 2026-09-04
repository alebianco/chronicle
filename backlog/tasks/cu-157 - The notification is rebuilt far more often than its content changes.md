---
id: cu-157
title: The notification is rebuilt far more often than its content changes
status: To Do
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

- [ ] Established which callers produce the burst, with a log or trace
- [ ] Redundant *callers* removed where they are genuinely redundant, before any dedup is added
- [ ] If a change-detection guard is added, a state-only change (play → pause) still re-posts
- [ ] Measured before/after, on the tablet with a 100+ track book (see cu-140's note on why the
      small input flatters this area)

## Related

- [[cu-50]] — fixed the chapter correctness half and removed the dead guard
- [[cu-140]] — the wider per-frame cost during playback; this is a small part of it
- [[cu-110]] — the rule this is an instance of: do not do per-second work whose result cannot change
