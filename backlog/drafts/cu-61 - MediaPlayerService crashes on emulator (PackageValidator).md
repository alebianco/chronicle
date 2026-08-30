---
id: cu-61
title: MediaPlayerService crashes on emulator (PackageValidator platform signature)
status: Draft
assignee: []
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

## Acceptance Criteria

- [ ] `MediaPlayerService` starts on an emulator with no platform signature
- [ ] A missing platform signature is logged, not thrown
- [ ] Unknown Android Auto callers are still rejected
- [ ] Verified on a real device that Auto behaviour is unchanged
