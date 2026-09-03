---
id: cu-133
title: 'Validate setting values on import, not just keys'
status: In Review
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - bug
  - robustness
milestone: m-2
dependencies:
  - cu-17
priority: high
---

## Description

Found in the 2026-09-02 branch review. The cu-17 settings backup allowlist gates **keys, but never
values**, so an imported file can persist a value that crashes the app on every launch.

`SettingsBackupRepo.applyParsed` writes raw strings straight to the editor:

```kotlin
is ParsedSetting.StringSetting -> editor.putString(key, setting.value)
```

and `parseSettingOrNull` type-checks `STRING` by accepting anything. But three allowlisted keys
have setters that reject unknown values by throwing — `bookSortKey`, `libraryMediaType`,
`libraryBookViewStyle` (`SharedPreferencesPrefsRepo.kt:263,293,303`) — and import bypasses those
setters entirely.

Hand-edit an export to `"key_library_view_style": "x"`, import it, and `AudiobookAdapter` throws
`IllegalStateException("Unknown view style")` from a **property initializer**
(`AudiobookAdapter.kt:39,49`), i.e. at construction, as soon as the library renders. The bad value
is now in prefs, so it crashes on **every launch**, and the settings screen needed to undo it may
be unreachable.

Not a privilege boundary — it needs local file access. But it defeats the file-over-app promise
(D13, decision-8) that these exports are hand-editable, which is exactly the point of using an open
format. Note `SettingsViewModel` is at **0% coverage** across 1,231 instructions, which is why this
got through: the export/import *logic* is at 99–100%, the code wiring it to the user is untested.

## Implementation Notes

**The validation.** `BACKUP_SETTING_VALUES` maps each constrained STRING key to its permitted
values, and `parseSettingOrNull` refuses anything outside them. A STRING key absent from the map
stays unconstrained — this is a per-key allowlist, not a blanket distrust of strings — but any key
whose setter can throw must appear, which a guard test enforces.

The allowed sets are read from the **same constants the setters check**, never copied: a divergence
would reintroduce the bug in the opposite direction, refusing values the app itself sets.
`libraryMediaType`'s valid list was a `private val viewTypes` inside the implementation, invisible
to the importer, so it is now published as `PrefsRepo.LIBRARY_MEDIA_TYPES` and the setter reads
that. Same for `BOOK_COVER_STYLES`.

**The second half was much larger than the task assumed.** The task asked whether
`AudiobookAdapter` should degrade instead of throwing. It should — but that mapping existed
**seven times** across `LibraryFragment`, `CollectionsFragment`, `CollectionDetailsFragment`,
`AudiobookAdapter` and `CollectionsAdapter`, and *every* copy ended in
`throw IllegalStateException("Unknown view style")`. Two were property initializers. All seven now
route through one `ViewStyle.kt` helper that logs and falls back to the cover grid.

**This was found on a device, and only on a device.** After fixing `AudiobookAdapter` alone, every
unit test was green — and seeding `key_library_view_style = "x"` into preferences on the tablet
still killed the app on launch, at `LibraryFragment.kt:197`, a site I had not touched. The crash
buffer named it in one line. A per-site fix is precisely what failed, which is why there is now a
source scan asserting **no file throws on an unknown view style**, with a guards-the-guard
companion.

Falling back is right for a *cosmetic* preference: the user sees the default layout and a log line
rather than an app that cannot open. It would be the wrong call for anything affecting listening
position, where guessing loses data.

**Verified on device** (Phh-Treble GSI, API 32, live server), with the poisoned value still on disk:

| check | before | after |
|---|---|---|
| launch with `key_library_view_style = "x"` | **crash, every launch** | no crash |
| Library tab | `IllegalStateException` at `LibraryFragment:197` | renders, 6 tiles in grid |
| log | `FATAL EXCEPTION` | `Unknown view style 'x'; showing the cover grid` |

Device prefs were backed up and restored afterwards.

**Sabotage-verified** three ways: neutering the value check fails 6 tests including both end-to-end
ones; removing a key from `BACKUP_SETTING_VALUES` fails the guard test; restoring the throw in
`ViewStyle.kt` fails 3 tests including the source scan.

**Not verified through the SAF picker.** Taps on the settings rows would not reach them on this
tablet in landscape — a bottom sheet appears to intercept them — so the import path was exercised
through `SettingsBackupRepo` in tests and through seeded preferences on device instead, which is
the same code and the more precise check. The interception itself may be worth a look; it is not
this task's subject and I have not filed it, since I could not separate it from my own tap
coordinates on an 800dp landscape layout.


## Acceptance Criteria

- [x] `SettingType.STRING` carries an optional allowed-value set, or import routes through the
      typed setters — either way an unknown value is refused, not persisted
      — `BACKUP_SETTING_VALUES`, a per-key allowlist checked in `parseSettingOrNull`. Sourced from
      the same constants the setters check, never a second copy.
- [x] A refused value produces a distinct, user-visible import outcome (not a silent skip and not
      a crash), consistent with the existing `ImportResult` shapes
      — already existed: a refusal increments `Applied.skipped`, and the UI shows
      `settings_backup_import_response_skipped` ("Restored N settings, skipped M the app could not
      read") rather than the plain success string. This change routes value-refusals into it.
- [x] Tests: a crafted file with an invalid enum-ish value is rejected; a valid one still applies;
      the partial case (one bad value among several good ones) has one defined, tested behaviour
      — 11 unit cases plus 2 end-to-end through the repo (`Applied(applied = 1, skipped = 1)`).
- [x] A guard test that every allowlisted key whose setter can throw has a declared value set —
      so adding a fourth such key cannot silently reopen this
      — `every constrained string key declares its allowed values`; sabotage-verified by removing
      one key, which fails it explicitly.
- [x] Consider whether `AudiobookAdapter` should degrade to a default view style rather than throw
      from an initializer; a persisted bad value should not be unrecoverable
      — **yes, and it was far wider than `AudiobookAdapter`.** See the notes.
