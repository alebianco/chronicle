---
id: DRAFT-108
title: Separate the auth prefs file and drop the orphaned premium keys
status: Draft
assignee: []
labels: [R1, trust, security]
dependencies: []
priority: medium
milestone: m-1
created_date: '2026-09-02'
---

## Description

The two items [[cu-77]] deliberately left open. Both concern **credentials at rest**, which
CLAUDE.md puts on the owner's side of the line, and neither is needed for correctness — cu-77's
allowlist and the whole-file Auto Backup exclusion are each verified by tests. They remove a
*class* of future mistake rather than fixing a present defect, which is why they were split out
instead of rushed in alongside the SAF work.

### 1. Move the tokens into their own prefs file

Auth tokens and settings share one `SharedPreferences` file (`Chronicle.xml`), because both prefs
repos inject the single instance provided for `APP_NAME`. Two consequences today:

- Auto Backup can only exclude the file **whole** (`data_extraction_rules.xml` +
  `backup_rules.xml`, one per API level, kept in agreement by `BackupRulesTest`), so settings
  cannot ride along in a platform backup even though they are harmless.
- Any export must apply the `BACKUP_SETTING_KEYS` allowlist, and the security property depends on
  that allowlist staying correct. It is enforced in one tested place, but it is still a rule a
  future edit could get wrong.

Moving `auth_token`, `server_token` and `user` into e.g. `ChronicleAuth.xml` would make the
separation **structural** rather than a matter of a list being right, and would let the backup
rules exclude only credentials.

Cost: a one-shot migration copying three keys across (and clearing the originals), a second
`SharedPreferences` provider in `AppModule`, and a `BackupRulesTest` update covering both files.

**The risk to weigh:** a migration that half-completes leaves a user signed out. `PlexPrefsRepo`
already uses `commit()` rather than `apply()` for credential writes, which helps, but the
copy-then-clear ordering needs care — clear only after a verified read-back, and make the
migration idempotent so a crash between the two steps recovers on next launch rather than losing
the account.

### 2. Remove the orphaned premium keys

`key_is_premium` and `key_premium_token` still exist on installs predating cu-60. They are inert,
and cu-77's allowlist keeps them out of exports, but a one-shot removal migration would delete a
stale **Play purchase token** from the device entirely — strictly better than merely not exporting
it.

This one is small and low-risk: remove both keys if present, once, and record it so it does not
run on every launch. Worth doing regardless of the decision on (1).

## Acceptance Criteria

- [ ] Owner decides whether the prefs-file split is worth its migration risk
- [ ] If yes: tokens live in a separate file, with an idempotent migration that cannot sign a user
      out if interrupted, and `BackupRulesTest` covers both files at both API levels
- [ ] If yes: the backup rules exclude only the credentials file, and settings are allowed into
      platform backups
- [ ] The premium-key removal migration runs once and is covered by a test asserting both keys are
      gone afterwards
- [ ] A test proves the migration is idempotent — running it twice must not clear a working token

## Notes

Note that (2) does **not** depend on (1) and could ship on its own. Splitting this draft into two
tasks on promotion is reasonable if the owner wants the cheap half now and the structural change
later.

Related: [[cu-17]] (the schema, allowlist and Auto Backup exclusions), [[cu-77]] (the SAF picker
that raised both questions), [[cu-60]] (which orphaned the premium keys).
