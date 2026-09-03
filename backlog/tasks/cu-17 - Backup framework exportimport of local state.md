---
id: cu-17
title: "Backup framework: export/import of local state"
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: []
priority: high
milestone: m-1
---

## Description

Per D8: versioned JSON/zip export-import of everything not re-derivable from Plex via SAF picker (cloud-folder friendly, zero cloud SDKs); Android Auto Backup rules (dataExtractionRules). Phase c (scheduled snapshots) is cu-17.1.

## Warning from the R0-close review (2026-08-31)

cu-60 removed the premium/IAP code but shipped **no prefs migration**, so `key_is_premium` and
`key_premium_token` remain in SharedPreferences on any install that predates it. Nothing reads them
today, so they are inert.

They stop being inert here. A natural implementation of this task enumerates `sharedPreferences.all`
to build the export, which would serialize a Play **purchase token** into a plaintext JSON backup.

Use an explicit allowlist of exported keys rather than a blanket dump — which is better practice
regardless — or ship a one-shot removal migration first. Decide before writing the exporter, not after.

## Implementation Notes

Two of three criteria are met. The **SAF picker UI** is split into [[cu-77]] — it is
device-verifiable work and there is no honest way to claim a wipe-and-restore round trip from
unit tests.

### The R0 warning was right, and understated

The warning said a blanket `sharedPreferences.all` dump would export a Play purchase token.
Checking further, it is worse than that: **both prefs repos inject the same `SharedPreferences`
instance** (the one provided for `APP_NAME`), so tokens and settings share one file. A blanket
dump would export the Plex account token, the server access token *and* the serialized user
record — not just the orphaned premium keys.

So `BACKUP_SETTING_KEYS` is an allowlist, applied on the way **in** as well as out: a
hand-edited or hostile file must not be able to write `auth_token` back, and a key invented by
a future version must not be applied blind. Verified by sabotage — replacing the filter with a
blanket dump fails two tests.

### Android Auto Backup was already leaking tokens

Separate from the export, and not mentioned in the task: `allowBackup="true"` was set with
**no** `dataExtractionRules`, and Auto Backup's default includes every `SharedPreferences`
file. So the token file was being uploaded to the user's Drive on every backup.

Mitigating detail worth stating rather than dramatising: Auto Backup is end-to-end encrypted
with the device screen lock on Android 9+, so this was not an open leak. It was still tokens
leaving the device for no reason, against D8.

Fixed with two rule files — `dataExtractionRules` is honoured only on API 31+ and minSdk is 27,
so `fullBackupContent` covers API 27–30. `BackupRulesTest` compares them, because a rule that
applies on some API levels and not others is invisible until it matters, and asserts
`device-transfer` is as strict as `cloud-backup`: a direct device-to-device copy still moves a
token onto hardware whose owner has not authenticated to Plex.

Verified in the built APK, not the sources: both manifest attributes resolve and both compiled
rule files carry the exclusions.

### Design choices worth recording

- **Values are strings, not a typed union.** JSON cannot reliably distinguish `Long` from `Int`
  on the way back, and the file should stay hand-editable (file-over-app). Each key is parsed
  against its known type at import instead.
- **Adding a key does not bump the schema version.** Unknown keys are ignored on import, so an
  older app reading a newer file degrades instead of failing. The version exists for when a
  key's *meaning* changes.
- **A newer schema version is refused outright**, not half-applied — guessing at an unknown
  format is how a restore corrupts settings.
- **`KEY_SYNC_DIR_PATH` is not exported.** A filesystem path from another device is meaningless
  and possibly unwritable; restoring it would point downloads at nothing.
- **The Room databases are excluded from Auto Backup** as well as from the export: they hold
  only what Plex re-derives plus progress that syncs server-side (cu-9/cu-14), so restoring a
  stale copy onto a fresh install would fight that sync.

### The prefs-file split that would improve this

Tokens and settings sharing one file is why `Chronicle.xml` had to be excluded *whole* from Auto
Backup — the platform cannot exclude individual keys. Moving tokens to their own prefs file
would allow finer rules and make the separation structural rather than enforced by an allowlist.
Worth doing; not needed for correctness here. Noted on [[cu-77]].

## Acceptance Criteria

- [x] Tokens never leave the device — allowlisted export (verified by sabotage) plus Auto Backup
      exclusion (verified in the APK). Both directions covered
- [x] Schema versioned and forward-compatible — older files accepted, newer refused, unknown
      keys ignored
- [>] Wipe app, restore file: identical state minus auth → [[cu-77]], which needs the SAF picker
      and a device
