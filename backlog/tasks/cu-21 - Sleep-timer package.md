---
id: cu-21
title: Sleep-timer package
status: Done
assignee:
  - '@claude'
created_date: '2026-07-13'
labels:
  - R2
  - comfort
milestone: m-2
dependencies: []
priority: medium
ordinal: 44000
---

## Description

End-of-chapter option, auto-restart on resume, keep shake-to-extend (#121/#101).

## Acceptance Criteria

- [x] Timer resumes without manual reset after pause

## Implementation Notes

All three items in the description were **defects**, not greenfield work, and the timer had **no
tests at all** — it was welded to a `Service`, a `MediaControllerCompat` and a real `Handler`. So
the first move was `SleepTimerLogic` + `SleepTimerState`: a pure decision layer with no Android
types, driven by `SleepTimerLogicTest` (24 cases). `SimpleSleepTimer` keeps the Android plumbing
and owns the state; every decision is made in the pure layer.

### 1. Auto-restart on resume — the acceptance criterion

The defect: on expiry, `start()` called `cancel()` and then `pause()`. `cancel()` **zeroes the
remaining time and stops the shake detector**, so a fired timer was indistinguishable from a
dismissed one and resuming left the user with nothing to resume.

Split into two verbs. **`expire()`** pauses playback and keeps the mode (`SleepTimerState.Expired`);
**`cancel()`** forgets everything. An expired timer keeps ticking — that is how it notices playback
resuming — and `onPlaybackResumed` re-arms it on the *transition* into playing, gated on
`prefsRepo.autoRestartSleepTimer` (default on, in `BACKUP_SETTING_KEYS`, with a settings entry).

**A test caught a design flaw here before it shipped.** The first version stored only the remaining
time, so a re-arm restored a timer of about one second — a timer always expires with nothing left.
`FixedDuration` now carries `originalMillis` beside `remainingMillis`, and an extension raises
both, so a shaken-awake night restores the timer the user ended up with rather than the one they
first picked.

### 2. End of chapter — a mode, not a duration

It was computed as a fixed countdown at pick time:
`((chapterDuration - chapterProgress) / playbackSpeed)`. Wrong twice — a **seek** does not change
the deadline, so it fired mid-chapter or long after; and the speed was baked in, so any later
change desynced it, now likelier since cu-20 made speed per book.

`SleepTimerMode.EndOfChapter(chapterId)` carries **no deadline**: each tick asks whether the
chapter still matches. Immune to seeks and speed changes by construction, because it derives no
deadline at all. An empty chapter id (a book with no chapter metadata) **holds** rather than
counting as a change — otherwise the timer would pause playback a second after being set. A
re-armed end-of-chapter timer adopts the chapter playing *now*, since the one it expired in is
already over.

### 3. Shake to extend — kept, with one fix

`extend()` used to add time whether or not a timer was running, leaving a positive remaining time
with nothing armed — the next tick then counted down a timer the user never set. It is a no-op
unless a **fixed** timer is running, and returns whether it did anything so the shake gesture only
plays its tone and toast when it actually extended. The tone/toast stayed on the *gesture*: a shake
needs confirmation because the user cannot see whether it registered, while the "+5 minutes" menu
item is its own confirmation.

### Two bugs I introduced and found on device

Both were invisible to the unit tests because they live in the Android plumbing, and both are
recorded because the shape recurs:

- **A broadcast feedback loop.** `ACTION_SLEEP_TIMER_CHANGE` carries commands *into* the timer and
  its ticks *out* of it, and the service listened to the action it broadcast on — so the timer's own
  `UPDATE(0)` came back as a command. Harmless while `update` only reassigned a Long to itself;
  once the state carried a **mode**, the loop rewrote an end-of-chapter timer as a zero-length
  countdown that expired one tick later. Observed as "set to end of chapter 4002" followed one
  second later by "expired". The service filters `UPDATE` now, and the enum says it is
  outbound-only. Found by adding a diagnostic log rather than guessing — the log showed
  `state=FixedDuration(0, 0)` where `EndOfChapter` belonged.
- **A guard that made `BEGIN` a no-op.** `BEGIN` is a two-step `update(duration)` then
  `start(true)`, and `update` leaves the state `Running` — so `start`'s "already active?" check
  (which I had rewritten to ask the *state*) returned every time without ever scheduling a tick.
  The timer sat at its full duration forever. It guards on a separate `isTicking` flag now: the
  state says what the timer *is*, `isTicking` says whether the loop is running, and conflating them
  is what broke it.

### One thing self-review added

An expired timer keeps ticking so it can notice a resume — but a first cut ticked **forever** if the
user neither resumed nor cancelled, leaving a 1 Hz handler post for the life of the service. That is
the per-second work cu-110 warned about. It now gives up after an hour of unused ticks and forgets
itself: an hour after falling asleep, "press play and get the same timer back" is no longer what a
resume means. The budget resets whenever the timer is armed or re-armed, so it only measures a
continuous unused stretch.

### Found but not fixed here

**The sleep-timer countdown is invisible in landscape** — added as a criterion and a worked note on
**cu-141**, which already predicted it ("check the other views constrained to `details_artwork`").
`sleep_timer_countdown` is a `0dp x 0dp` overlay constrained on all four sides to `details_artwork`,
which is GONE in `values-land`. The icon lights up correctly; the remaining time never appears.
Same root cause as cu-141's `progress`, so it belongs in that fix rather than a separate task.
Pre-existing.

### Verification

`./verify.sh --format` green — **VERIFY PASSED (7 stages)**. 845 -> **868 unit tests**. Four
behaviours sabotage-verified: the re-arm, the re-arm's duration source, the pause hold, and the
empty-chapter guard.

On the tablet in mock Plex mode (fixture books, no credentials):

- **End of chapter**: set on chapter 4002 at `19:58:45`, held 10 s, expired at `19:58:56` —
  immediately after `onChapterChange` to Chapter 3 at `19:58:55`. A boundary, not a countdown.
- **Auto-restart**: playback paused (`state=2`); pressing play logged "Playback resumed; re-arming
  the sleep timer" with **no manual reset**, and the re-armed timer then fired at the *next*
  boundary (Chapter 5, `19:59:52`) — proving it adopted the current chapter.
- **Fixed duration**: a 5-minute timer read `04:45` after ~15 s of playback (portrait, where the
  countdown is visible), so it counts at the right rate. This also proves the `isTicking` fix —
  before it the display sat at `05:00` forever.
- **Pause hold**: still `04:45` after a further 12 s paused. A sleep timer measures *listening*
  time, not wall time.

Not covered: surviving a process death. The service outlives a pause (only `onStop` tears it down),
so in-memory state is enough for the criterion as written; persisting a timer across a restart
would be separate work and is not claimed.
