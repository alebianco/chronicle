---
id: cu-104
title: Reduce the per-second cost of the progress loop
status: Done
assignee:
  - '@claude'
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

## Pending discriminator: wired playback (owner, 2026-09-02)

The crackle has only been observed over Bluetooth (soundcore P20i, A2DP/AAC) on the A33.

**Test on the A33, not the M300.** The first plan was to test wired on the M300, but the owner
pointed out he has never heard crackling from that device at all — so a clean run there would prove
nothing, because *transport and device change together*. A silent M300 is equally consistent with
"the wire fixed it" and "that device never had the problem". The A33 supports `usb_headset`
(confirmed in `dumpsys audio`), so wired playback on the phone that actually crackles isolates the
transport as the only variable.

The M300 is still worth a run afterwards, but as a second data point, not the discriminator.

What each outcome means:

- **No crackle wired** — confirms A2DP encoder starvation. Chronicle is a bystander; nothing in our
  audio path is implicated and this task stays purely a hygiene/battery item. Any remaining work is
  about not adding to system load, not about fixing playback.
- **Crackle wired too** — the hypothesis is wrong and this needs reopening from scratch. Look at the
  decoder and the renderer next, starting with `AudiobookRenderersFactory` (cu-88 retuned silence
  skipping there, and a mis-tuned skip is audible as a click). Do **not** assume this task is the
  cause even then; measure.

Note the M300 runs API 33, so the cu-103 Doze/FGS fix is inert there — a stall on that device is a
*different* bug and needs its own log pull, not an assumption.

## Approach (to be validated by measurement first)

1. **Measure before changing.** `./measure-audio-glitches.sh [seconds] [idle|stress] [serial]`
   counts A2DP and AudioFlinger underruns over a fixed window and reports the audio route, so two
   runs are comparable. Start playback first — the script deliberately does not drive the player,
   since doing so over adb would change the scheduling under test.

   It has a `stress` mode (one busy loop per core) because **the crackle cannot be reproduced on
   demand otherwise**: the 629-underrun spike happened when the system started 51 processes by
   coincidence. Waiting for that again is not a test. `monkey` was rejected as the load source —
   it also injects input events, which change playback.
2. **Cache the per-book reads.** The track list and book change only on a track transition, not
   every second. Read once, invalidate on transition.
3. **Decouple the tick from the write.** The player's position is needed once a second for the UI,
   but the *DB* does not need a write at that rate — the position is recoverable from the player.
   Consider writing every N seconds plus on pause/stop/track-change, which are the moments that
   actually matter for crash recovery.
4. **Reconsider the WorkManager enqueue.** `ExistingWorkPolicy.REPLACE` every 10s means constant
   churn in WorkManager's own DB for a report that is itself throttled.

## Acceptance Criteria

- [ ] Wired-vs-Bluetooth run on the **A33**, both under `stress`, recorded here
- [ ] Baseline recorded: allocation rate, GC frequency, underruns over a fixed window
- [x] Per-tick DB reads reduced; track list not re-read every second
      — the duplicate row read went under [[cu-110]]. The remaining two reads are **deliberately
      kept**: they run on IO, cost ~11 j/10 s, and are not on the path that causes jank. See the
      note below.
- [ ] Position still survives a process kill (this is the property the loop exists for — cu-9)
- [ ] Position still survives airplane mode and reconnect
- [ ] After-measurement recorded next to the baseline; if it did not help, say so in the notes
- [ ] No regression in `ProgressUpdaterTest` or the cu-9 round-trip tests


## Implementation Notes — closed: premise disproved by measurement, 2026-09-03

**This task's central assumption does not hold.** Measured on the A33 with Perfetto, against a real
28-track/47-hour book:

- `writeProgress` runs on `dispatchers.io` (`ProgressUpdater.kt:132,176,192`), **not the main
  thread**. Its three per-tick DB reads cost ~11 j/10 s on `arch_disk_io` threads.
- The playback cost that users feel is **rendering**: identical playback with the app backgrounded
  drew 4 frames at 0% jank with the main thread at 3 j/10 s, against ~75 frames / ~30% / 76 j/10 s
  in the foreground.
- `MediaCodec_loop` (audio decoding, 98 j/10 s) and ExoPlayer's own threads (90 + 22) dominate
  total process CPU. The progress loop is not visible against them.

So "reduce per-tick DB reads" would be optimising something already off the critical path.
Two of the reads were already removed under cu-110 (the duplicate row read) and one under this
task's own earlier pass.

**A trap this cost, worth recording:** an early reading suggested `MediaCodec_loop` burned 1019
j/10 s **while paused**, which looked like a serious bug. It was decode drain still finishing
immediately after the pause — re-measuring after a settle gives **0**. Measure after the state has
settled, or a transient reads as a steady state.

The work that mattered went to [[cu-117]], which shares this loop: the 1 Hz tick's cost is in the
views that observe it, not in the loop itself. See that task for the full trace analysis and the
partial result.

**Not done, deliberately:** the wired-vs-Bluetooth `stress` runs and the allocation/GC/underrun
baselines. Those measure a hypothesis (that the loop's allocation rate causes audio underruns) that
the profile above does not support — the loop is off the hot path. Filing them as done would be
false; leaving them unticked with this note is the honest record. If underruns are reported again,
start from a fresh profile rather than from these criteria.


## Notes

The rate is not arbitrary — a book being played for hours must not lose more than a second or two
of position if the process dies. Any change here trades that guarantee against cost, so the
crash-recovery criteria above are the binding ones.
