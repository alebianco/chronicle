---
id: cu-88
title: Skip silence is too aggressive to listen through
status: Done
labels: [R2, playback, bug]
dependencies: []
priority: medium
milestone: m-2
assignee: [claude]
---

## Description

Owner-reported (2026-08-31): *"the skip silent audio setting makes most tracks un-listenable,
probably too aggressive?"*

The app does not implement silence skipping — it forwards the setting to ExoPlayer:

```kotlin
(currentPlayer as? ExoPlayer)?.skipSilenceEnabled = prefsRepo.skipSilence
```

`skipSilenceEnabled` is Media3's `SilenceSkippingAudioProcessor`, whose thresholds are **fixed**
and were tuned for video/podcast content. For audiobooks — deliberate pauses between sentences and
chapters, quieter narration — it clips speech and destroys pacing. So the symptom is real and not a
bug in this codebase's logic; the setting as offered is simply not usable for this content.

## Yes, it is tunable — and the defaults explain the symptom

Read from `media3-exoplayer-1.11.0.aar` (`javap -constants`), not from memory:

| Constant | Default | Why it hurts narration |
|---|---|---|
| `DEFAULT_MINIMUM_SILENCE_DURATION_US` | `100_000` (**100 ms**) | Ordinary gaps between words in speech exceed 100 ms, so this triggers *mid-sentence*, not just on dead air. This is the main culprit. |
| `DEFAULT_SILENCE_RETENTION_RATIO` | `0.2f` | Only 20% of a detected silence is kept — the pauses that carry sentence and paragraph structure are cut to a fifth. |
| `DEFAULT_PADDING_SILENCE_US` | `20_000` (**20 ms**) | Far too little breathing room around retained speech, so words run together. |
| `DEFAULT_SILENCE_THRESHOLD_LEVEL` | `1024` | Quiet narration and trailing consonants fall under this and read as silence. |
| `DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US` | `2_000_000` (2 s) | |
| `DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE` | `10` | |

Those values are tuned for stripping dead air from video/screencast audio. Applied to a narrated
book they clip speech and destroy pacing, which is exactly the reported symptom. So the setting is
not mis-wired — the defaults are simply wrong for this content.

**Two configurable constructors exist:**

```java
SilenceSkippingAudioProcessor(long minimumSilenceDurationUs, long paddingSilenceUs, short silenceThresholdLevel)
SilenceSkippingAudioProcessor(long minimumSilenceDurationUs, float silenceRetentionRatio,
                              long maxSilenceToKeepDurationUs, int minVolumeToKeepPercentageWhenSilence,
                              short silenceThresholdLevel)
```

**And there is an injection point:** `DefaultAudioSink.Builder.setAudioProcessorChain(...)` accepts a
chain, and `DefaultAudioSink.DefaultAudioProcessorChain` takes a custom processor. Wire it by
overriding `DefaultRenderersFactory.buildAudioSink` and passing the factory to `ExoPlayer.Builder`
in `ServiceModule.exoPlayer()`.

**There is direct precedent in this codebase.** `ServiceModule.exoPlayer()` already overrides
ExoPlayer's defaults for exactly this reason, with the comment *"increase buffer size across the
board as ExoPlayer defaults are set for video"*. Tuning silence skipping is the same argument
applied to the audio processor.

## Options

## Options

1. **Tune it (recommended).** Raise `minimumSilenceDurationUs` well above word-gap length,
   raise `paddingSilenceUs`, and keep more of each silence. Starting point to iterate from, not
   final values — these need real books:
   - `minimumSilenceDurationUs` ≈ `700_000`–`1_000_000` (0.7–1 s): only collapse pauses longer than
     a natural sentence break.
   - `paddingSilenceUs` ≈ `100_000`–`200_000`: leave audible breathing room.
   - `silenceRetentionRatio` ≈ `0.5`–`0.6`: shorten pauses rather than remove them.
   - `silenceThresholdLevel`: leave at `1024` initially; lower only if room noise is being kept.
2. **Remove the setting.** Still defensible if there is no appetite for audio iteration — shipping a
   setting that makes playback unusable is worse than not having it.
3. **Keep the flag, relabel and warn.** Cheapest, still ships something users report as broken.
   Not recommended.

Recommend **1**, since tunability is now confirmed rather than assumed, the injection point exists,
and the codebase already sets this precedent for buffering. Fall back to **2** only if tuning turns
out not to produce a listenable result.

## Notes

- Values verified against **Media3 1.11.0**, the version in the catalog. Re-check on any Media3
  bump; these are library defaults, not API contracts.
- The chosen numbers are a judgement about *audio*, so they cannot be asserted in a unit test. What
  a test **can** pin: that the custom processor is actually installed in the chain and that the
  configured values reach it — otherwise a future refactor silently reverts to the defaults and the
  symptom returns with nothing failing.
- Judge the result on real books with real narration, including a quiet-voiced one, and check
  chapter boundaries as well as mid-sentence pauses → [[cu-73]].
- Setting `skipSilence = false` (the current default) remains the safe state; nobody is affected
  unless they enable it.

## Acceptance Criteria

- [x] A decision is recorded (tune) with its reasoning
- [x] A custom `SilenceSkippingAudioProcessor` is installed via `setAudioProcessorChain`, with a
      test proving it is in the chain and carries retuned values — a test that fails if the wiring
      is dropped and the defaults return
- [>] Speech is not clipped mid-sentence on a real book, and pauses are shortened rather than
      removed ([[cu-73]], with a quiet-voiced narrator among the samples) — **the values are
      starting points, not measured**
- [x] `skipSilence = false` still behaves exactly as today (no processor active)
- [x] Verify loop green

## Implementation Notes

**Decision: tune, not remove.** Tunability was confirmed by reading the artifact rather than assumed,
and the injection point exists.

`AudiobookRenderersFactory` overrides `DefaultRenderersFactory.buildAudioSink` and installs a
`DefaultAudioProcessorChain` with a configured `SilenceSkippingAudioProcessor`. Wired in
`ServiceModule.exoPlayer()` via `setRenderersFactory`.

### Values, and why they are not measured

| Parameter | Library default | Here | Reason |
|---|---|---|---|
| `minimumSilenceDurationUs` | 100 ms | **800 ms** | 100 ms is shorter than ordinary gaps *between words*, so the default fires mid-sentence. This is the core defect. |
| `silenceRetentionRatio` | 0.2 | **0.55** | Shorten pauses rather than strip them; sentence and paragraph pacing survives. |
| `maxSilenceToKeepDurationUs` | 2 s | 2 s | Already reasonable for speech. |
| `minVolumeToKeepPercentage` | 10 | 10 | Unchanged. |
| `silenceThresholdLevel` | 1024 | 1024 | Raising it would treat quiet narration as silence; lowering only helps if room noise is being *kept*, which is the opposite problem. |

These are **starting points chosen against the defaults' known failure modes**, not measured values.
Whether a book is listenable is a judgement about audio that no unit test can make, so the numbers
are expected to be revised after the live pass.

### What the tests actually pin

Not the values' correctness — that would be false confidence. They pin that the tuning is **not
inert**: the processor is in the chain, the thresholds are meaningfully above the library defaults
(reverting to them fails 2 tests), and — importantly — `SonicAudioProcessor` is still present.

That last one is the trap this could easily have introduced: `SonicAudioProcessor` implements
playback speed and pitch, so a chain built without it would leave the player unable to change speed,
the most-used feature in an audiobook app, with no error anywhere. A test asserts a 1.5× speed is
honoured by the chain.

The library defaults are duplicated in the test on purpose: a Media3 bump that changes them should
fail here and prompt a re-read, rather than silently comparing against a moved target.

### Not done

- **The values are unverified against real audio** → [[cu-73]], including a quiet-voiced narrator and
  chapter boundaries as well as mid-sentence pauses.
- `skipSilence` still defaults to `false`, so nobody is affected unless they enable it. Worth
  reconsidering the default only once the tuning is confirmed good.
