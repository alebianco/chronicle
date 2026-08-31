---
id: m-0
title: "R0 — Foundation"
---

## Description

"Don't build on sand." Toolchain truth + the agentic verification loop: stable Room, honest CI + verify.sh, KAPT→KSP, SDK/Media3 bumps, quick-win cherry-picks, donation links, CLAUDE.md.

## Closed 2026-08-31 — 15/15 tasks Done

| | Before | After |
|---|---|---|
| Verification gate | CI ran a full emulator matrix executing **zero** tests | `verify.sh` as the gate of record, coverage ratchet, R8 dex assertions |
| Tests | 2 files; 5 of 19 tests hollow (empty bodies) | **55**, none hollow, sabotage-verified |
| Room | 2.7.0-**alpha12** in production, untested migrations | 2.8.1 stable, all four DBs export schemas, migration chains tested |
| Codegen | KAPT + DataBinding | KSP + ViewBinding (29 layouts, 218 expressions moved to Kotlin) |
| SDK / Media3 | 34 / 1.3.0 | 36 / 1.11.0 |
| Release APK | 10.2 MB | 6.6 MB |
| Monetization | premium gates + dormant IAP | none, permanently ([[decision-15]]) |
| Verifiability | no way to run the app without a Plex account | mock server + audio fixture + screenshot script |

### What changed versus the original plan

- **cu-8 did not deliver its stated benefit.** KAPT is gone, but incremental builds are *slower*
  (+13% ordinary edit, +97% annotated type) because of fixed per-invocation overhead in KSP2. Accepted
  deliberately; the criterion is marked not-met rather than quietly ticked.
- **cu-6 could not use AGP 9.2** as specified — AGP 9 absorbs the Kotlin plugin, and AGP 8 cannot run
  on Gradle ≥ 9.6.0. Landed AGP 8.13.2 + Gradle 9.5.1 instead.
- **Three tasks were added mid-release** from findings: cu-60 (remove premium gating), cu-63
  (edge-to-edge, a regression cu-6 shipped), cu-65 (dependency refresh).
- **cu-5's donation criterion was struck, not met** — [[decision-15]] made "add none" the correct
  implementation.

### Carried into R1

- **cu-64 is In Review**: audio is served correctly and the player reports `STATE_PLAYING`, but no
  request reaches the mock, so bytes-flowing is unproven. Belongs with cu-9.
- Landscape/cutout insets unchecked (cu-63) — for cu-28.
- No Android 16 device was ever available; all device verification is Android 15 with `targetSdk 36`.

### The recurring lesson

Three times this release a green check proved nothing: the cu-45 R8 guard grepped a file that omits
unrenamed classes, the cu-58 screenshot script captured the launcher after BACK exited the app, and the
cu-63 inset targeted a nested toolbar instead of the AppBarLayout. Each was caught by inspecting an
artefact, not by the gate. **A new check must be verified to fail before it is trusted.**
