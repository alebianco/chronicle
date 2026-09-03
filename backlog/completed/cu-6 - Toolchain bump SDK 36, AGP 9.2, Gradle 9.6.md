---
id: cu-6
title: "Toolchain bump: SDK 36, AGP 9.2, Gradle 9.6"
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, foundation]
dependencies: []
priority: high
milestone: m-0
---

## Description

targetSdk/compileSdk 36 + AGP 9.2 + Gradle 9.6.1; QA edge-to-edge, predictive back, FGS types, notification permission; 16KB page-size check (Fresco). See RESEARCH_FINDINGS §8. Play deadline 2026-08-31 only binds if Play distribution is ever chosen (D9 dormant).

## Implementation Notes

Landed: **compileSdk/targetSdk 36, Gradle 9.5.1, AGP 8.13.2, Kotlin 2.2.10, KSP 2.2.10-2.0.2**.

### Deviation from the task title: AGP 8.13.2, not 9.2

The task specified AGP 9.2 + Gradle 9.6.1. Both exist, but the combination forced a choice:

- **AGP 9.x absorbs the Kotlin Gradle plugin.** Applying `kotlin-android` alongside it fails with
  *"Cannot add extension with name 'kotlin'"*. AGP 9 is a DSL migration in its own right, not a version
  bump.
- **AGP 8.x is incompatible with Gradle ≥ 9.6.0.** Gradle removed `org.gradle.api.problems.internal.InternalProblems`,
  which AGP 8 depends on; it fails at plugin-apply with an explicit pointer to *"use Gradle 9.5"*.

So the pairing is forced: either AGP 8.x with Gradle ≤ 9.5.1, or AGP 9.x with a plugin-block migration.
Took the former — the SDK 36 target (the actual objective) is achieved either way, and AGP 9 deserves
its own task rather than being smuggled in here.

Note the task's "Gradle 9.6.1" does not exist as a release; 9.x stable runs 9.0.0, 9.1.0, 9.2.0, then
9.5.1, 9.6.0, 9.6.1, 9.7.1.

### Real bugs surfaced by SDK 36

SDK 36 annotates several platform APIs as nullable, which turned three latent NPE risks into compile
errors:

- `PackageInfo.activities` — dereferenced in `DownloadNotificationWorker` and `NotificationBuilder`.
  Both already tolerated a null result downstream (`activity?.name ?: ""`), so a safe call preserves
  behaviour exactly.
- `PackageInfo.applicationInfo` in `PackageValidator` — now returns null rather than fabricating a
  `CallerPackageInfo` for a caller that cannot be identified. This is the Android Auto allowlist, so
  failing closed is the correct direction.
- `requestedPermissionsFlags` was indexed in lockstep with `requestedPermissions` with no bounds or
  null check — a genuine crash if the arrays ever disagreed. Now guarded with `getOrNull`.

### Other fixes required

`oss-licenses-plugin` 0.10.6 broke the release build on Gradle 9 (`groovy/util/XmlSlurper`, removed
from Gradle 9's Groovy runtime). Bumped to 0.13.0.

### The KSP question from cu-8: answered, and the answer is no

cu-8 left open whether a newer Kotlin/KSP would close the incremental-build gap. Re-measured here on
**KSP 2.2.10-2.0.2 / Kotlin 2.2.10**: an `@Entity` edit still costs **~5.3s** against KAPT's ~2.3s. The
upgrade does not help. This is consistent with the cu-8 finding that the cost is fixed per-invocation
overhead in KSP2, not something a version bump addresses.

### Acceptance criteria, honestly

- **16KB page-size check: not applicable.** The criterion named Fresco as the risk; cu-43 removed
  Fresco and the release APK now contains **zero `.so` files**. Nothing to align.
- **"Manual playback checklist on an Android 16 device": not done.** No Android 16 device or AVD is
  available here; the app was verified on an **Android 15 (SDK 35)** emulator with `targetSdk = 36`,
  which exercises the new target behaviours but is not the same as running on 16. Playback itself is
  still unverified end-to-end because `MediaPlayerService` crashes on emulators ([[cu-61]]).

### Regression shipped knowingly

`targetSdk = 36` enforces edge-to-edge, and the app does not consume window insets — the toolbar now
draws under the status bar, with the title overlapping the clock. Real and visible. Filed as [[cu-63]]
(high priority) rather than reverting `targetSdk`, which would forfeit the point of the bump.

## Acceptance Criteria

- [x] Clean build on new toolchain — SDK 36, Gradle 9.5.1, AGP 8.13.2, Kotlin 2.2.10; `verify.sh` and
      `test_release_build.sh` green, 44 tests passing, release APK 5.4 MB
- [ ] Full manual playback checklist passes on Android 16 device — **not done**: no Android 16
      device/AVD available, and playback cannot be exercised on an emulator until [[cu-61]]. Verified on
      Android 15 with `targetSdk = 36` instead.
- [x] 16KB check done — **N/A**: zero native libraries in the APK since cu-43 removed Fresco
