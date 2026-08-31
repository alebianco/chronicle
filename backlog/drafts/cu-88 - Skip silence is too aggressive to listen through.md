---
id: cu-88
title: Skip silence is too aggressive to listen through
status: To Do
labels: [R2, playback, bug]
dependencies: []
priority: medium
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

## Options

1. **Remove the setting.** Honest, and matches D9's no-half-features instinct. Loses a feature some
   users of the upstream app may expect.
2. **Configure the processor.** Media3 exposes the thresholds
   (`SilenceSkippingAudioProcessor(minimumSilenceDurationUs, paddingSilenceUs, silenceThresholdLevel)`)
   but wiring a custom instance means building the `AudioSink`/`RenderersFactory` rather than
   flipping a flag. Needs real-audio iteration to pick values — a laptop cannot judge "listenable".
3. **Keep the flag, relabel and warn**, deferring 2. Cheapest, still ships something users report
   as broken.

Recommend **2** if there is appetite for tuning against real books, otherwise **1** — shipping a
setting that makes playback unusable is worse than not having it. Either way it needs a decision
before code; that is why this is a draft rather than a task.

## Notes

- Verify against the current Media3 version (1.11.0) before assuming the defaults are unchanged.
- If tuned, the values are a judgement call about *audio*, so they belong with the live-verification
  pass ([[cu-73]]) rather than being asserted in a unit test. A test can pin that the configured
  processor is actually installed.

## Acceptance Criteria

- [ ] A decision is recorded (remove, tune, or defer) with its reasoning
- [ ] If tuned: the custom processor is demonstrably installed, covered by a test
- [ ] If removed: the preference, its UI entry and its string are all gone
- [ ] Verify loop green
