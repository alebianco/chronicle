---
id: cu-77
title: Backup export/import via SAF picker
status: Done
assignee: []
created_date: '2026-08-31'
updated_date: '2026-09-02 09:12'
labels:
  - R1
  - trust
milestone: m-1
dependencies:
  - cu-17
priority: high
ordinal: 3000
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

- [x] Export writes a JSON file to a user-chosen location via SAF; no cloud SDK added
- [x] Import reads one back, applies allowlisted settings, and reports a refused file clearly
- [x] A malformed value is skipped rather than crashing the restore; covered by a test
- [x] **Wipe app, restore file: identical state minus auth** — verified on a device, with the
      before/after settings compared explicitly rather than eyeballed
- [x] Screenshot of both settings entry points (a UI claim needs one — cu-63's lesson)
- [x] Decide on the prefs-file split; if done, migration plus updated `BackupRulesTest`
      — **decided: split out to [[cu-108]].** Not needed for correctness (the allowlist and the
      whole-file exclusion are each verified), and it touches credentials at rest, which is the
      owner's call. The migration also carries a real sign-out risk if interrupted, which deserves
      its own task rather than riding along with the picker work.
- [x] Decide on the premium-key removal migration
      — **decided: split out to [[cu-108]]**, same reasoning. Cheap and low-risk on its own, and
      independent of the prefs-file split, so it can ship separately.

## Implementation Notes

Landed the SAF half on 2026-09-02, verified on the tablet (Phh-Treble GSI, Android 12 / SDK 32)
against a live Plex account.

### What was added

- **`SettingsBackupRepo`** (`data/local/`) — the file I/O, taking a `Uri` from the caller so it
  never learns where the file lives. Every outcome is a distinct sealed result
  (`Written`/`Failed`, `Applied`/`WrongVersion`/`Unreadable`) rather than a boolean, because a
  refused file and a successful restore of nothing must not look alike on screen.
- **Typed parsing** in `SettingsBackup.kt` — `BACKUP_SETTING_TYPES` declares each allowlisted
  key's stored type, and `parseSettingOrNull` parses one value against it. A test asserts the
  type map covers the allowlist **exactly**, so a key added to one without the other fails the
  build instead of importing as nothing.
- **Two settings entries** under a new `BACKUP` heading. Import warns first with the same yes/no
  bottom sheet as "Delete synced files"; export does not, since the picker's own overwrite prompt
  covers it.
- `ContentResolver` added to `AppModule`; `SettingsBackupRepo` exposed from `AppComponent`
  (`ActivityComponent` depends on it and can only see what it provisions).

### Decisions worth recording

- **`commit()`, not `apply()`, when writing a restore.** The caller reports success and the
  settings list re-reads the prefs immediately; `apply()`'s deferred write is the async-write race
  that cost three separate bugs in cu-73's first session.
- **`"wt"` mode on the output stream.** Without truncation, overwriting a longer file leaves the
  old tail behind — valid JSON followed by garbage. Covered by a test that writes 8 KB of junk
  first.
- **A boolean is parsed explicitly, never with `String.toBoolean()`**, which maps every
  unrecognised string to `false` and would quietly turn settings *off* on a corrupted file rather
  than leaving them alone.
- **A non-finite playback speed is refused.** `"NaN"` and `"Infinity"` both survive
  `toFloatOrNull` and either would make the player unusable with no obvious way back.
- **The open picker filters on a wildcard, deliberately.** `OpenDocument` shows a document
  matching *any* listed type, so a wildcard alongside `application/json` is the same as the
  wildcard alone. Providers disagree about the type of a hand-copied `.json`, and a narrow filter
  greys out exactly the file the user wants with no explanation. A wrong pick is cheap because the
  repo reports an unreadable file rather than applying anything.

### A pre-existing bug this exposed

**The settings list never rebound a row whose value changed.**
`PreferenceItemDiffCallback.areContentsTheSame` compared only title and explanation, so after an
import the switches kept their old state on screen while the preferences underneath were already
correct — confirmed by restarting the app, which showed the imported values.

It had survived because the two ordinary ways a value changes both repaint anyway: a tapped switch
is set by its own click handler, and a clickable row renders its value *into the title*
("Refresh frequency: 6 hours"), which was compared. Import is the first path that changes several
values at once without touching their views. Fixed by including `defaultValue` in the comparison
(it carries a switch's current value, read live from `prefsRepo` on each `makePreferences()`), with
`PreferenceItemDiffCallbackTest` covering it.

### Device verification

Round trip proven end to end, not inferred:

1. Set offline mode on, skip silence on, refresh frequency 6 hours.
2. Exported — file written to `/sdcard/Download` as indented JSON with a trailing newline:
   `{"version":1,"settings":{"key_offline_mode":"true","key_skip_silence":"true","key_refresh_rate":"360"}}`.
   `sharedPreferences.all` at that moment also held `auth_token`, `server_token`, `user` and
   `key_sync_location`; **none of them appear in the file.**
3. `pm clear` — a real wipe, signing the account out.
4. Re-logged in, confirmed settings were back at defaults (refresh "1 hours", both switches off).
5. Imported the file: all three settings returned, and after the diff fix the switches repaint
   **immediately** rather than only after a restart.

Refusal paths also driven on the device: a `version: 99` file logged
`Refusing a backup from schema version 99; this app understands 1` and changed nothing; an
unparseable file was reported with context and did not crash.

One trap worth knowing for future device testing: **SAF does not overwrite by default.** Saving
over an existing name produced `chronicle-backup-2026-09-02 (1).json` and `(2).json`, and reading
the original filename showed an empty `settings` map from the very first export — before any
setting had been written. That looked like an export bug for a while and was not one.

### Split out rather than left open

Both remaining criteria were **decisions, not code**, and both touch credentials at rest — the
owner's call per CLAUDE.md. They are now [[cu-108]] rather than an open checkbox on a closed
task, so the SAF work can land without either being forgotten:

- **The prefs-file split** (tokens into `ChronicleAuth.xml`). Would make the separation
  structural rather than dependent on the allowlist staying correct. Needs a one-shot migration of
  three keys plus a `BackupRulesTest` update — and, noted while writing it up, that migration can
  sign a user out if it is interrupted between the copy and the clear, so it wants an idempotent
  design and a test of its own.
- **The premium-key removal migration** (`key_is_premium`, `key_premium_token`, inert since
  cu-60). The allowlist already keeps them out of exports; a removal migration would delete a stale
  Play purchase token from the device entirely, which is strictly better than merely not exporting
  it. Independent of the split, and much cheaper — it could ship alone.
