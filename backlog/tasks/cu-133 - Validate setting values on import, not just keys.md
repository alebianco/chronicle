---
id: cu-133
title: 'Validate setting values on import, not just keys'
status: To Do
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

## Acceptance Criteria

- [ ] `SettingType.STRING` carries an optional allowed-value set, or import routes through the
      typed setters — either way an unknown value is refused, not persisted
- [ ] A refused value produces a distinct, user-visible import outcome (not a silent skip and not
      a crash), consistent with the existing `ImportResult` shapes
- [ ] Tests: a crafted file with an invalid enum-ish value is rejected; a valid one still applies;
      the partial case (one bad value among several good ones) has one defined, tested behaviour
- [ ] A guard test that every allowlisted key whose setter can throw has a declared value set —
      so adding a fourth such key cannot silently reopen this
- [ ] Consider whether `AudiobookAdapter` should degrade to a default view style rather than throw
      from an initializer; a persisted bad value should not be unrecoverable
