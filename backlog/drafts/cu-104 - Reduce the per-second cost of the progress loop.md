---
id: cu-104
title: Reduce the per-second cost of the progress loop
status: Draft
assignee: []
created_date: '2026-09-01'
labels: [R2, performance, trust]
dependencies: []
priority: high
milestone: m-2
---

## Description

`ProgressUpdater` runs a handler tick **every second** while playing, and each tick does far more
work than the fact it records. From `writeProgress`:

- **4 DB reads** — `getBookIdForTrack`, `getTrackAsync`, `getTracksForAudiobookAsync`,
  `getAudiobookAsync`. None cached; every one goes to SQLite.
- **2 DB writes** — `bookRepository.updateProgress` and `trackRepository.updateTrackProgress`.
- **`currentlyPlaying.update(...)`** with the whole track list.
- **A WorkManager enqueue every 10th tick** (`beginUniqueWork(...).enqueue()`), which is another DB.

Measured on the owner's A33 while doing nothing but playing audio: the app allocates roughly
**23 MB between GC cycles**, GCs every few minutes, and each GC costs 100-195 ms of background work.
`getTracksForAudiobookAsync` allocating the full track list once a second is the obvious source.

This is the same loop that caused cu-93's 228 UI recomputations per minute.

## What this is NOT

Investigated during the audio-crackle report (2026-09-01) and **not shown to be the cause**. The
crackling is Bluetooth A2DP encoder starvation:

```
btif_a2dp_source_read_callback: UNDERFLOW: ONLY READ 0 BYTES OUT OF 4096
a2dp_aac_encode_frames: underflow 1
```

Underruns per minute: 4, 1, 1, then **629** at 23:34, 216, 102. The spike coincides with the
*system* starting **51 processes in ~35 seconds** (Samsung services, GMS learning, setupwizard) —
Chronicle was not among them and did not GC during the spike. The device was thrashing and the
encoder thread could not be scheduled.

ExoPlayer buffering is already tuned for audio and is **not** the problem: 10s min, 360s max, 120s
back-buffer (`ServiceModule`). Increasing it would not help, since the loss is downstream of the
decoder, between the mixer and the Bluetooth encoder.

So this task is worth doing for battery, GC pressure and general hygiene — **not** on a promise that
it fixes crackling. Any claim that it does must be measured.

## Approach (to be validated by measurement first)

1. **Measure before changing.** Fixed playback window with the mock server; record allocation rate,
   GC count and A2DP underruns. Without a baseline the change cannot be judged.
2. **Cache the per-book reads.** The track list and book change only on a track transition, not
   every second. Read once, invalidate on transition.
3. **Decouple the tick from the write.** The player's position is needed once a second for the UI,
   but the *DB* does not need a write at that rate — the position is recoverable from the player.
   Consider writing every N seconds plus on pause/stop/track-change, which are the moments that
   actually matter for crash recovery.
4. **Reconsider the WorkManager enqueue.** `ExistingWorkPolicy.REPLACE` every 10s means constant
   churn in WorkManager's own DB for a report that is itself throttled.

## Acceptance Criteria

- [ ] Baseline recorded: allocation rate, GC frequency, underruns over a fixed window
- [ ] Per-tick DB reads reduced; track list not re-read every second
- [ ] Position still survives a process kill (this is the property the loop exists for — cu-9)
- [ ] Position still survives airplane mode and reconnect
- [ ] After-measurement recorded next to the baseline; if it did not help, say so in the notes
- [ ] No regression in `ProgressUpdaterTest` or the cu-9 round-trip tests

## Notes

The rate is not arbitrary — a book being played for hours must not lose more than a second or two
of position if the process dies. Any change here trades that guarantee against cost, so the
crash-recovery criteria above are the binding ones.
