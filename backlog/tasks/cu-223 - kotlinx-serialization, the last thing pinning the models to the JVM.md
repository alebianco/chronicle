---
id: cu-223
title: "kotlinx-serialization, the last thing pinning the models to the JVM"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - architecture
  - debt
milestone: m-3
dependencies: 
  - cu-210
  - cu-216
priority: medium
---

## Description

decision-24 moved the HTTP layer to Ktor and Ktorfit, and deliberately **did not** move the
serializer: `MoshiContentConverter` (~40 lines, 38 of them code) was written so a parsing regression
and a transport regression could not be confused. That was the right call then, and this is the task
that finishes the job.

**Moshi is now the only thing keeping the data layer JVM-bound.** It is JVM-only and codegen-based,
so every Plex model stays Android-side no matter what happens to the transport. Ktor already pulls
`kotlinx-serialization-core` transitively, and Ktor ships a **first-party** kotlinx converter — so
this adoption also **deletes `MoshiContentConverter`**.

## The surface is wider than the Plex API

Four distinct roles, and only the first is mechanical:

| Where | Role | Character |
|---|---|---|
| `data/sources/plex/model/**` | Plex API DTOs | mechanical — same shape, different annotation |
| `SettingsBackup` | **the export format** (decision-8) | risky — see below |
| `SeriesIndexRulesFile` | user-editable rules file on disk | user-facing format |
| `Collection` | Room `@TypeConverter` | Room instantiates converters reflectively |

**14 files carry `@JsonClass`; 21 adapters are generated.**

## Two behaviours that are load-bearing, and default differently

**1. `SettingsBackup` depends on Moshi silently dropping unknown fields.** Its KDoc:

> Adding a *settings key* does **not** require a bump: unknown keys are ignored on import, so an
> older app reading a newer file degrades rather than failing.

kotlinx-serialization **throws** on an unknown key unless `ignoreUnknownKeys = true`. Get this wrong
and settings restore breaks across app versions — silently in the direction that matters, since it is
the *older* app that fails. Three test files pin this (`BackupSchemaTest`, `SettingsBackupRepoTest`,
`BookmarkBackupTest`), so it would be caught — but it must be deliberate, not discovered.

**2. `SeriesIndexRulesFile` deliberately tolerates an unknown enum constant** rather than rejecting
the whole file — its comment says Moshi *"would reject an unknown enum constant outright, and the
whole file"* would fail to parse. kotlinx-serialization needs an explicit default or a custom
serializer for the same tolerance.

## The thing to get right

**Do the API DTOs and the on-disk formats as separable steps**, even within this task. The DTOs are
covered by real-shape fixture tests; the two on-disk formats are user data with forward-compatibility
rules. If the second half proves hairy, stopping after the DTOs is a coherent place to be — the
converter can register kotlinx for the API and Moshi for the rest during the transition.

## Acceptance Criteria

- [ ] kotlinx-serialization adopted; `MoshiContentConverter` **deleted** and Ktor's own converter
      used instead
- [ ] `ignoreUnknownKeys = true` where `SettingsBackup` needs it, with a test proving an older app
      reading a **newer** backup file still degrades rather than failing
- [ ] `SeriesIndexRulesFile` still tolerates an unknown enum constant without discarding the file,
      pinned by a test
- [ ] `Collection`'s Room `@TypeConverter` still works, given Room instantiates it reflectively
- [ ] The real-shape Plex fixture tests pass untouched — they are the evidence that parsing did not
      change
- [ ] Moshi and its KSP processor removed from the build once nothing uses them; APK delta recorded
- [ ] `RetiredDependencyTest` extended to keep Moshi out of `app/src/main`
- [ ] **The portable share is re-measured** so cu-182 inherits a current number, per cu-210
- [ ] `./verify.sh` green; `./test_release_build.sh` passes — serialization is R8-sensitive

## Notes

Closing status **In Review**: settings export is user data governed by decision-8, and a
forward-compatibility rule is the kind of thing worth a second pair of eyes even when tests pass.
