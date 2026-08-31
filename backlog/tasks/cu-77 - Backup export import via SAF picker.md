---
id: cu-77
title: Backup export/import via SAF picker
status: To Do
assignee: []
created_date: '2026-08-31'
labels: [R1, trust]
dependencies: [cu-17]
priority: high
milestone: m-1
---

## Description

Split from [[cu-17]], which landed the versioned schema, the export/import allowlist and the
Auto Backup exclusions. What remains is the part that needs a device: the Storage Access
Framework picker that writes and reads the file, and the wipe-and-restore round trip that
proves it.

### Scope

1. **Export**: `ACTION_CREATE_DOCUMENT` with `application/json`, a default filename carrying a
   date (`chronicle-backup-2026-08-31.json`), writing `exportSettings(...)` serialized by Moshi.
2. **Import**: `ACTION_OPEN_DOCUMENT`, parse, run through `importSettingsOrNull`, apply, and
   report clearly when the file is refused (wrong schema version, unparseable) rather than
   failing silently.
3. **Settings entry points**: two items in the settings screen. Import must warn that it
   overwrites current settings, matching the existing force-sync prompt's shape.
4. **No cloud SDKs** (D12 rule 7). SAF means the user picks a Dropbox/Drive/Nextcloud folder
   themselves and the app never knows which — that is the point.

### Notes

- `exportSettings` takes a plain `Map<String, Any?>`, so the ViewModel passes
  `sharedPreferences.all`. The allowlist filters it; **do not pre-filter at the call site**, or
  the security property moves out of the tested function.
- Values are strings by design (JSON cannot distinguish `Long` from `Int` reliably, and the file
  should stay hand-editable). Applying them means parsing each against its known type — a
  `when` over `BACKUP_SETTING_KEYS` with the same types `PrefsRepo` uses. A malformed value must
  be skipped, not crash the restore.
- Consider writing the file with a trailing newline and 2-space indent. It is a
  file-over-app artifact someone may open in a text editor.

### Worth doing while here: split tokens into their own prefs file

Tokens and settings currently share one `SharedPreferences` file, which is why [[cu-17]] had to
exclude `Chronicle.xml` *whole* from Auto Backup — the platform cannot exclude individual keys.
Moving `auth_token`, `server_token` and `user` into a separate file (e.g. `ChronicleAuth.xml`)
would:

- allow the backup rules to exclude only credentials and let settings ride along;
- make the separation structural rather than dependent on an allowlist being correct;
- need a one-shot migration copying the three keys across, and a matching update to
  `BackupRulesTest`.

Not required for correctness — the allowlist and the whole-file exclusion are both verified —
but it removes a class of future mistake.

### Also pending: remove the orphaned premium keys

`key_is_premium` and `key_premium_token` still exist on installs predating cu-60. They are inert
and the allowlist keeps them out of exports, but a one-shot removal migration would delete a
stale Play purchase token from the device entirely, which is better than merely not exporting it.

## Acceptance Criteria

- [ ] Export writes a JSON file to a user-chosen location via SAF; no cloud SDK added
- [ ] Import reads one back, applies allowlisted settings, and reports a refused file clearly
- [ ] A malformed value is skipped rather than crashing the restore; covered by a test
- [ ] **Wipe app, restore file: identical state minus auth** — verified on a device, with the
      before/after settings compared explicitly rather than eyeballed
- [ ] Screenshot of both settings entry points (a UI claim needs one — cu-63's lesson)
- [ ] Decide on the prefs-file split; if done, migration plus updated `BackupRulesTest`
- [ ] Decide on the premium-key removal migration
