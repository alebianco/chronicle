---
id: cu-54
title: Rebuild instrumented tests on Gradle Managed Devices
status: Draft
assignee: []
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: [cu-16]
priority: medium
---

## Description

Split out of cu-3. The instrumented suite under `app/src/androidTest` does not compile and has not run
since `c5cfd46`. Its tests target an `OnboardingActivity` and string resources (`plex_login_title`,
`username`, `password`, `login`) that ceased to exist when onboarding was refactored into Fragments
(`LoginFragment`, `ChooseServerFragment`, `ChooseUserFragment`, `ChooseLibraryFragment`) in `9e89270`.
Removing the `enabled = false` guard in `app/build.gradle.kts` produces ~15 unresolved references.

cu-3 chose to delete the fake emulator CI job and quarantine these sources honestly rather than fake a
green check. This task is the actual remediation.

Scope:
- Rewrite `OnboardingActivityTest` / `EndToEndTest` against the current Fragment-based onboarding flow.
- Run headless via Gradle Managed Devices (`./gradlew pixel2api34DebugAndroidTest`) so CI needs no
  emulator-runner action and the same command works on a laptop — no forge lock-in (D12 rule 6).
- Back the login flow with the cu-16 fixture pack / `FakePlexServer` so the suite needs no live server
  or credentials.
- Re-enable the quarantined Gradle tasks and remove the comment block in `app/build.gradle.kts`.
- Add the instrumented stage to `verify.sh` behind a flag (it is far slower than the unit gate).

Depends on cu-16 because a hermetic instrumented suite needs the fake server first; otherwise these
tests would require real Plex credentials and would be flaky by construction.

## Acceptance Criteria

- [ ] `app/src/androidTest` compiles
- [ ] Onboarding flow covered against the current Fragment implementation
- [ ] Suite runs headless via Gradle Managed Devices, offline, no credentials
- [ ] Quarantine block removed from `app/build.gradle.kts`
- [ ] CLAUDE.md no longer states instrumented tests are dead
