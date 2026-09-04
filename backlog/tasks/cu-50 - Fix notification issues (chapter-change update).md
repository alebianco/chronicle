---
id: cu-50
title: Fix notification issues (chapter-change update)
status: In Review
assignee:
  - claude
created_date: '2026-07-13'
labels:
  - R2
  - playback
milestone: m-2
dependencies: []
priority: medium
ordinal: 61000
---

## Description

M6: notification not refreshed on chapter change; verify across Android versions + Android 13+ POST_NOTIFICATIONS. Fix update-on-chapter-change, test actions.

Analysis: [`M6-notification-issues-plan.md`](../docs/analysis/M6-notification-issues-plan.md).

## Acceptance Criteria

- [x] Notification updates on chapter change
      — the session metadata is now republished on a chapter boundary, which is what the
      notification actually renders. Five unit tests, sabotage-verified.
- [ ] Correct across Android versions incl. 13+ permission
      — **not verified.** Needs a device; the tablet left the network mid-session. See notes.
- [ ] Notification actions tested
      — the actions' *state machine* is covered by `NotificationStateMachineTest` (pre-existing)
      and untouched here. Actually pressing them is device work, deferred with the item above.

## Implementation Notes

**The diagnosis in the task title was right but the obvious cause was wrong.** `onChapterChange`
has always fired and has always called `notificationManager.notify` — the notification *was* being
rebuilt on every chapter boundary. And `NotificationBuilder` *did* set the chapter as the content
title. The bug was that neither mattered:

`NotificationBuilder` uses `MediaStyle.setMediaSession()`, and Android then renders the **session
metadata**, discarding `setContentTitle` entirely. A comment in that file states this
("title/subtitle will be pulled directly from the session, ignoring below") and was then not
accounted for. The session metadata was refreshed only from *track*-level player events —
`onMediaItemTransition`, `onPositionDiscontinuity`, `switchToPlayer` — and built from
`player.currentMediaItem`.

**A chapter is not a track.** Most of this library is single-track books with many chapters
(confirmed while surveying the real server for cu-150: `Ender's Game` is one of only four
multi-track books in 196), so crossing a chapter boundary fires no player event at all and the
notification kept showing one title for the whole book.

**The fix** is `publishChapterAsSessionMetadata`, called from `onChapterChange`. Three things about
its shape are deliberate:

- **The media id keeps naming the track.** `onMetadataChanged` resolves the playing book by looking
  that id up in the *track* repository and `onPositionDiscontinuity` reads it to attribute
  progress; a chapter id there would break progress writes silently. A test pins it.
- **It copies the existing bundle** (`MediaMetadataCompat.Builder(existing)`) and overrides only the
  display fields. `setMetadata` replaces the whole bundle, and the two paths that populate it
  contribute `albumArtUri`, `mediaUri`, `duration`, `trackNumber` and `displayIconUri` — none of
  which this class has the inputs to rebuild. A first cut *did* rebuild from scratch; self-review
  caught it. Losing the art keys shows up as a blank lockscreen image and `duration` as a dead
  scrubber, and neither throws.
- **An empty chapter title falls back to the book title**, since a blank session title renders as a
  blank notification. And nothing is published at all when no book is playing, rather than
  overwriting the player's metadata with placeholders.

**Dead code removed.** `NotificationBuilder` carried a `NotificationData` class plus
`currentNotificationMetadata` and `currentID` fields — an unfinished change-detection guard. None
was ever read, and `currentID` was a `val` computed once at construction, so it could never have
worked. Finishing it is **cu-157**, filed with the device evidence for why it is wanted: the
notification was rebuilt **five times in 400 ms** at playback start.

**Two testing notes worth keeping.**

- Robolectric's `MediaSessionCompat` **never publishes metadata to a controller** —
  `session.controller.metadata` reads null whatever was set (verified directly). The first version
  of these tests asserted on it and failed against a correct fix. They capture the argument handed
  to `setMetadata` instead. The artwork carry-over is therefore *not* unit-tested, and the comment
  in the test file says so rather than leaving a silent gap.
- Sabotage found a missing assertion: changing `displayTitle` alone did not fail the suite, because
  only `title` was asserted. `MediaStyle` prefers `METADATA_KEY_DISPLAY_TITLE` when present, so
  that omission was the difference between the fix working and appearing to. Both are asserted now,
  and the re-sabotage fails.

**The analysis doc was not usable.** `backlog/docs/analysis/M6-notification-issues-plan.md` has its
sections in reverse order, invents an owner ("Playback Team") and a date, and contains effort
estimates and an approval checklist but no technical content — no mention of `MediaStyle`, the
session metadata, or the track/chapter distinction that *is* the bug. Working from the code was
faster than reading it. Left in place for the owner to judge, but it should be archived.

## Still open

The two device criteria: behaviour across Android versions including the 13+ `POST_NOTIFICATIONS`
permission, and pressing the actions. The tablet dropped off the network (`Host is down`, no ping)
partway through this session, so neither could be checked. They are the whole remaining scope of
this task, hence **In Review** rather than Done.
