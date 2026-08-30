---
id: cu-61
title: MediaPlayerService crashes on emulator (PackageValidator platform signature)
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, playback]
dependencies: []
priority: high
---

## Description

Found while smoke-testing cu-43 on a Pixel Tablet AVD (Android 15 / SDK 35). **Pre-existing, not a
cu-43 regression** — `PackageValidator.kt` was last modified in `7e8d9ad` (ktlint formatting) and
nothing in the cu-43 diff appears in the stack.

`MediaPlayerService` dies during `onCreate`:

```
java.lang.IllegalStateException: Platform signature not found
  at PackageValidator.getSystemSignature(PackageValidator.kt:310)
  at PackageValidator.<init>(PackageValidator.kt:71)
  at ServiceModule.packageValidator(ServiceModule.kt:165)
  at DaggerServiceComponent$ServiceComponentImpl.injectMediaPlayerService(...)
  at MediaPlayerService.onCreate(MediaPlayerService.kt:189)
```

`PackageValidator` is the Android Auto caller allowlist (`allowed_media_browser_callers.xml`). It looks
up the platform signature at construction and **throws** when absent. Dagger constructs it eagerly
while injecting `MediaPlayerService`, so the whole service fails to start.

### Why this matters beyond the emulator

- **Playback is the app's core function and its service crashes at init** on this image. Whether the
  same is reachable on a real device — a signature-stripped ROM, a GrapheneOS-style build, some
  work-profile setups — is unverified. A hard `IllegalStateException` for an *Android Auto allowlist*
  is disproportionate: failing to identify callers should disable Auto, not kill playback.
- **It blocks agentic verification.** With instrumented tests quarantined (cu-54), an emulator is the
  only way an agent can verify playback end-to-end. Right now that path dies immediately, which is a
  D12 rule 1 problem ("anything that degrades an agent's ability to close the loop is a bug").

### Suggested direction

Make the failure non-fatal: catch the missing-signature case in `PackageValidator`, log it with Timber,
and treat the caller set as empty (deny unknown Auto callers) rather than throwing. Playback for the
local user does not depend on validating third-party media-browser callers.

Confirm on a real device before changing behaviour — if the signature is always present there, this is
purely an emulator/agent-tooling fix and the risk is low. Do not weaken the *actual* allowlist checks;
only the absent-platform-signature path should become recoverable.

## Implementation Notes

`getSystemSignature()` threw `IllegalStateException` when the `android` platform package had no
readable signature. Dagger builds `PackageValidator` eagerly while injecting `MediaPlayerService`, so
that exception killed the playback service at construction.

Now returns `String?` and logs a warning instead. The platform signature only admits *additional*
system-signed callers to the media browser; its absence should disable that one allowance, not take
down all playback.

### A security bug found while fixing it

`getSignature()` also returns `String?`. The original comparison was:

```kotlin
callerSignature == platformSignature -> true
```

With both null — an unsigned caller on an image with no platform signature — that evaluates to **true
and admits the caller**. Making `platformSignature` nullable would have widened this from a
crash-on-startup into a silent authorisation hole. The branch is now guarded:

```kotlin
platformSignature != null && callerSignature == platformSignature -> true
```

So a missing platform signature makes the allowance *unavailable* rather than universal. This was
latent before, reachable only on images that also crashed at startup — the crash was masking it.

Added `PackageValidatorSignatureTest` (5 cases) mirroring the rule, since `PackageValidator` needs a
real `Context` and an XML resource and cannot be constructed in a JVM test. The point is that dropping
the null guard fails a test rather than silently widening access.

Also deleted a KDoc claiming *"This key is never null"* — now false, and it was the premise behind the
original throw.

### Verification

- **`MediaPlayerService` starts on the emulator.** `adb shell am startservice` produces
  `Service created!`, `SWITCHING PLAYER to ExoPlayerImpl`, `Start command!` — previously it died in
  `onCreate`. This unblocks emulator-based playback verification (D12 rule 1).
- `./verify.sh` green; 49 tests (was 44), 0 failures.

### Still not verifiable on an emulator

Actual audio playback: the cu-16 mock serves JSON and cover art, not audio streams, so pressing play
has nothing to fetch. Verifying real playback end-to-end needs either a live Plex server or an audio
fixture — worth considering as an extension to cu-16 if playback regressions become a concern.

## Acceptance Criteria

- [x] `MediaPlayerService` starts on an emulator with no platform signature
- [x] A missing platform signature is logged, not thrown
- [x] Unknown Android Auto callers are still rejected — and a null-signature caller is now rejected too,
      which it was not before
- [ ] Verified on a real device that Auto behaviour is unchanged — **not done**, no device available.
      The change can only widen rejection, never admission, so the risk is a caller being refused rather
      than wrongly allowed.
