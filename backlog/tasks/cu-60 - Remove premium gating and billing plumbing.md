---
id: cu-60
title: Remove premium gating and billing plumbing
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R0, governance]
dependencies: []
priority: medium
milestone: m-0
---

## Description

Consequence of [[decision-15]] (no monetization of any kind, ever). The app still contains a paid tier
it will never have.

### What is there today

- `prefsRepo.isPremium` gates two features:
  - `CurrentlyPlayingViewModel:725` — playback speed, refused with the literal user-facing string
    *"Error: variable playback speed is a premium feature"*.
  - `AudiobookDetailsViewModel:331` — offline downloads, refused via `premium_required_offline_playback`.
- `SettingsViewModel:164-181` renders an "upgrade to premium" / "premium unlocked" entry.
- `ChronicleBillingManager` + `PREMIUM_IAP_SKU` + the `iapwrapper` (Google-IAP) dependency.
- `KEY_IS_PREMIUM` / `KEY_PREMIUM_TOKEN` preferences and their strings.

### Why this is worth doing rather than leaving dormant

`defaultIsPremium = true` in `SharedPreferencesPrefsRepo:261`, so **every feature is already unlocked**
and removal is a pure simplification with no behavioural change. But the code still *claims* these are
paid features, and those claims are now false: the strings are user-visible the moment anything sets
`KEY_IS_PREMIUM` to false, and the Settings screen advertises a purchase that cannot happen.

Also drops the Google-IAP dependency — a proprietary SDK that [[decision-12]] rule 7 discourages
independently of monetization.

### Scope

1. Delete both `isPremium` guards; the guarded features become unconditional.
2. Remove the premium Settings entry and its strings.
3. Delete `ChronicleBillingManager`, `PREMIUM_IAP_SKU`, and its Dagger wiring (`AppComponent`,
   `ChronicleApplication`, `SettingsFragment`).
4. Drop the `iapwrapper` dependency from the version catalog and `app/build.gradle.kts`.
5. Remove `KEY_IS_PREMIUM` / `KEY_PREMIUM_TOKEN` and `premiumPurchaseToken`. **Migration note:** these
   are SharedPreferences keys, not Room columns — orphaned entries are harmless, no migration needed.
6. Remove the now-dead billing keep rule from `app/proguard-rules.pro` and re-run
   `./test_release_build.sh`.

Check for a `premium` reference in `strings.xml`, layouts, and the Play-adjacent metadata before
declaring done.

### Sign-off

CLAUDE.md lists billing/IAP code under "never touch without explicit owner sign-off". [[decision-15]]
provides that sign-off for the direction; this task is the reviewed execution of it. Do not widen scope
beyond removing monetization.

## Implementation Notes

### What was removed

- Both `prefsRepo.isPremium` gates — playback speed (`CurrentlyPlayingViewModel`) and offline downloads
  (`AudiobookDetailsViewModel`). Both features are now unconditional.
- The premium Settings entry, plus the orphaned `_upgradeToPremium` LiveData and
  `startUpgradeToPremiumFlow()`, and the `SettingsFragment` observer that launched the billing flow.
- `ChronicleBillingManager`, its `AppComponent` provision, and the `ChronicleApplication` field.
- `PREMIUM_IAP_SKU` / `IAP_SKU_LIST` from `Constants.kt`.
- `isPremium`, `premiumPurchaseToken` and the three `KEY_*_PREMIUM*` constants from the prefs repo.
- All five premium strings.
- The `iapwrapper` (Google-IAP) dependency and the billing ProGuard keep rule.
- **`<uses-configuration android:name="com.android.vending.BILLING" />` from AndroidManifest.xml** —
  not in the original task scope, found while sweeping for leftovers. The app was still declaring a
  Play Billing requirement to the system.

### The dependency removal broke the build — and exposed a latent fragility

Removing `iapwrapper` failed with an opaque DataBinding error:

```
ERROR: must be able to find a common parent for long and
kotlinx.coroutines.flow.Flow<error.NonExistentClass>
  fragment_currently_playing.xml Line:164
```

The cause was not the billing code at all. **The app declared no `androidx.lifecycle` dependency
whatsoever**, and was receiving `lifecycle-livedata-ktx` — the source of `asLiveData`, `viewModelScope`
and `ViewModel` — transitively through Google-IAP. Removing billing took the lifecycle library with it,
`asLiveData` stopped resolving, and DataBinding reported the resulting unresolvable `Flow` type rather
than the real missing dependency.

Fixed by declaring `lifecycle-livedata-ktx`, `lifecycle-runtime-ktx` and `lifecycle-viewmodel-ktx`
explicitly at 2.6.2 (the version that was resolving anyway). This is a genuine robustness improvement
independent of cu-60: relying on a billing SDK to supply the app's core lifecycle classes was one
unrelated dependency change away from breaking at any time.

### Diagnosis note for future agents

KAPT masks Kotlin compile errors behind DataBinding's `LoggedErrorException`, and the default output
shows only `A failure occurred while executing ... KaptExecutionWorkAction` with no message. Two things
that worked and are worth reusing:

- `./gradlew assembleDebug --stacktrace 2>&1 | grep "LoggedErrorException" -A12` surfaces the actual
  `ERROR:` line with the offending layout and line number.
- `./gradlew kaptGenerateStubsDebugKotlin` compiles Kotlin *without* running the DataBinding processor,
  which cleanly separates "my Kotlin is broken" from "DataBinding cannot resolve a type".

Also: **order matters when removing a Dagger-provided singleton.** Deleting `fun billingManager()` from
`AppComponent` while `SettingsFragment` still `@Inject`ed it produced
`[Dagger/IncompatiblyScopedBindings]` — Dagger tried to construct the `@Singleton` inside
`@ActivityScope`. Remove consumers first, then the provision.

### Verification

- `./verify.sh` green, all 5 stages. 29 tests, 0 failures.
- `./test_release_build.sh` green — release APK **8.18 MB** (from 8.26 MB), all reflection-dependent
  classes survived R8.
- Coverage 4.26% → **4.20%**, recorded deliberately via `--update`. The drop is correct: covered code
  (the billing manager and both gates) was deleted without deleting any test. The ratchet caught it and
  refused to pass until it was acknowledged, which is the behaviour cu-3 built it for.
- Swept `app/src/main`, ProGuard rules, version catalog, build file and manifest for
  `premium`/`billing`/`IAP` — the only remaining match is an explanatory comment in `build.gradle.kts`.

### Note on remaining Google telemetry

`com.google.android.datatransport` and `firebase-encoders` are **still** in the dependency graph, but
they arrive via `play-services-cast-framework` (Media3 Cast support), not billing. That is a legitimate
feature dependency and out of scope here — flagged for whoever revisits D12 rule 7 on proprietary SDKs.

## Acceptance Criteria

- [x] No `isPremium` guard remains; playback speed and offline downloads are unconditional
- [x] `ChronicleBillingManager`, `PREMIUM_IAP_SKU` and the Google-IAP dependency are gone
- [x] No user-facing string refers to premium, upgrading, or purchase
- [x] `./verify.sh` and `./test_release_build.sh` green; billing keep rule removed
