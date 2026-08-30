---
id: cu-60
title: Remove premium gating and billing plumbing
status: To Do
assignee: []
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

## Acceptance Criteria

- [ ] No `isPremium` guard remains; playback speed and offline downloads are unconditional
- [ ] `ChronicleBillingManager`, `PREMIUM_IAP_SKU` and the Google-IAP dependency are gone
- [ ] No user-facing string refers to premium, upgrading, or purchase
- [ ] `./verify.sh` and `./test_release_build.sh` green; billing keep rule removed
