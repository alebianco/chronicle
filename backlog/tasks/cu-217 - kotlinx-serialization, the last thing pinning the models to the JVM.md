---
id: cu-217
title: 'kotlinx-serialization, the last thing pinning the models to the JVM'
status: In Review
assignee: []
created_date: '2026-09-07'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - architecture
  - debt
milestone: m-2
dependencies:
  - cu-210
  - cu-214
priority: medium
ordinal: 111000
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

- [x] kotlinx-serialization adopted; `MoshiContentConverter` **deleted** and Ktor's own converter
      used instead, via one shared `installPlexJson()` the DI graph and four tests now agree on
- [x] `ignoreUnknownKeys = true` where `SettingsBackup` needs it, with a test proving an older app
      reading a **newer** backup file still degrades rather than failing
- [x] `SeriesIndexRulesFile` still tolerates an unknown enum constant without discarding the file,
      pinned by a test
- [x] `Collection`'s Room `@TypeConverter` still works, given Room instantiates it reflectively —
      pinned by building it through `getDeclaredConstructor().newInstance()`, as Room does
- [x] The real-shape Plex fixture tests pass untouched — they are the evidence that parsing did not
      change
- [x] Moshi and its KSP processor removed from the build once nothing uses them; APK delta recorded
- [x] `RetiredDependencyTest` extended to keep Moshi out of `app/src/main`
- [x] **The portable share is re-measured** so cu-182 inherits a current number, per cu-210
- [x] `./verify.sh` green; `./test_release_build.sh` passes — serialization is R8-sensitive

## Notes

Closing status **In Review**: settings export is user data governed by decision-8, and a
forward-compatibility rule is the kind of thing worth a second pair of eyes even when tests pass.


## Closing notes

Done in one pass rather than the two the description allowed for — the on-disk formats turned out
to be the *easier* half, because both already had thorough degradation suites and neither needed a
custom serializer. What the split protected against did not materialise; what it warned about did.

### The two defaults that differ, and what each cost

**`ignoreUnknownKeys`** was the expected one and behaved as predicted. Sabotage-verified: turning it
off fails 11 tests — the two new forward-compatibility tests, the rules-file one, and eight
real-shape fixture tests, which is a fair measure of how much of this app's parsing depends on it.

**`encodeDefaults` was not anticipated by the ticket, and is the more interesting find.** kotlinx
omits any property equal to its default, so `SettingsBackup(version = 2)` serialized to a file with
**no `version` field at all** — the format's own self-description, silently absent. It was caught by
an existing assertion (`SettingsBackupRepoTest`, "should declare its schema"), not by design.
`importSettingsOrNull`'s newer-version refusal would have read 0 on every file this build wrote.

**`coerceInputValues` was deliberately left off.** It would have been the tempting way to match
Moshi's leniency, but `PlexFixtureContractTest` pins the opposite: an explicit `null` on a non-null
field must fail loudly. A server that starts sending nulls should be a known failure, not a library
of books titled `""`.

All four settings live in one `ChronicleJson`, with the reasoning beside each — the settings *are*
the file-format guarantees, so they should not be re-decided per call site.

### Measurements

| | before | after | |
|---|---:|---:|---|
| release APK | 7,251,456 B | 7,267,774 B | **+16.3 KB (+0.22%)** |
| portable share of `app/src/main` | 23.7% | **26.0%** | +2.3 pts |

The APK grew slightly rather than shrank: the kotlinx runtime costs a little more than the Moshi
one it replaced, and dropping the KSP-generated adapters did not offset it. Worth stating plainly —
the case for this change was portability and one less JVM-only dependency, not size.

The portable figure is re-measured in `maintainability-review-2026-09.md` beside the original, with
the honest caveat that +2.3 points does not change that review's conclusion: the 72% that is UI,
WorkManager and media is untouched.

### A guard that would have silently stopped working

`test_release_build.sh` scanned for `@JsonClass` to decide which models must survive R8. After the
migration that pattern matched **nothing**, so the check would have passed while asserting over an
empty set — the exact "guard whose list quietly emptied" failure this repo warns about. It now scans
`@Serializable` **and carries a count floor** (20 models found, floor 15), so a future serializer
change fails visibly instead of passing vacuously.

### Coverage

`data/model` was restored to its floor by two new tests. `data/sources/plex/model` was lowered
**deliberately** from 84.20% to 81.59%, measured: 2.47 of the 2.61 points lost are uncovered
`<clinit>` blocks the compiler plugin adds to model classes to cache element serializers. It is
generated plumbing, but unlike `$$serializer` it lives *inside* the model class and so cannot be
excluded by pattern. `MediaProvider` and `Feature`, which account for much of it, are unreferenced
by production code and were already at 0%.

### Not done

`./test_release_build.sh`'s **install step** fails at `INSTALL_PARSE_FAILED_NO_CERTIFICATES` — the
release APK is unsigned because signing is owner-only. Pre-existing and unrelated, but it means the
release variant has been **built and R8-verified, not run on a device**. The R8 assertions are the
part this task needed and they pass; a device smoke test of a signed build is still owed by whoever
holds the keys.
