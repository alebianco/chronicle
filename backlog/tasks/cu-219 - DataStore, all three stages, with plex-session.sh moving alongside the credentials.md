---
id: cu-219
title: "DataStore, all three stages, with plex-session.sh moving alongside the credentials"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - architecture
  - trust
milestone: m-3
dependencies: 
  - cu-210
  - cu-215
priority: medium
---

## Description

cu-194 §2 calls this *"the one nobody has ever asked"* — DataStore appears nowhere in the repo, while
**21 files** depend on `SharedPreferences`. The owner's answer: adopt it, all three stages.

**One clarification that lowers the urgency without changing the answer.** Official guidance
deprecated **`EncryptedSharedPreferences`**, not plain `SharedPreferences` — and this project uses
plain, verified: no `security-crypto`, no `MasterKey`, no `EncryptedSharedPreferences` anywhere. So
this is a **modernisation**, not a security fix. What does still apply is that `SharedPreferences`
does synchronous I/O on the calling thread and is no longer the recommended API, and that synchronous
main-thread storage access is expected to become a strict-mode violation.

**Tink is declined** (recorded in cu-210): a new crypto dependency for tokens already excluded from
Auto Backup means more moving parts around credentials, not fewer.

## Three stages, and the order is the safety

**Stage 1 — non-secret settings (`Chronicle.xml`).** No credentials, no tooling dependency, no
backup-rules test. The place to learn DataStore's failure shapes cheaply.

**Stage 2 — the export path.** `BACKUP_SETTING_KEYS` gates settings export by key, and **cu-189 is
about to extend it**. Do this after cu-189 lands or coordinate explicitly; doing both at once means a
backup-format bug and a storage bug are indistinguishable.

**Stage 3 — `ChronicleAuth.xml`, and this is the one with teeth.**

## The hard requirement

**`plex-session.sh` must migrate in the same change as `ChronicleAuth.xml`.** That script reads and
writes these prefs XML files **directly**, to swap the real Plex session for mock mode without
`pm clear`. It is the device-verification tooling. If the app moves its credential storage and the
script does not:

- mock mode stops working, so **every subsequent device check is blocked**;
- and the owner's real Plex login cannot easily be recreated — it needs a human at a browser, and the
  local memory notes the owner may not be available for days.

That is a worse outcome than not migrating at all. The two move together or stage 3 does not start.

Also load-bearing at stage 3:

- **The credential split** (cu-108): `ChronicleAuth.xml` holds the three secrets and is excluded from
  Auto Backup while `Chronicle.xml` is not, enforced by `BackupRulesTest` parsing two rules files.
  DataStore files live elsewhere on disk, so **both rules files and that test must be updated in
  step**.
- **The cache-clobber trap**: a prefs file edited while the app runs is reverted on process death.
  DataStore's async writes have a *different* failure shape, not an absent one — worth re-deriving
  rather than assuming it is solved.
- **`util/PreferenceFlow.kt`** already provides the reactive read that is DataStore's main selling
  point here, so it presumably retires — confirm rather than assume.

## Acceptance Criteria

- [ ] Stage 1: non-secret settings on DataStore, with a **migration from the existing prefs** so no
      user loses a setting
- [ ] Stage 2: the export path still round-trips — `SettingsBackupRepoTest`, `BackupSchemaTest` and
      `BookmarkBackupTest` green, and coordinated with cu-189
- [ ] Stage 3: credentials moved **and** `plex-session.sh` updated in the same change
- [ ] `CHRONICLE_DEVICE=<serial> plex-session.sh status|real|mock` verified working against the
      tablet **after** stage 3, before the task closes. This is the criterion that protects every
      later device check
- [ ] `BackupRulesTest` still proves the credentials are excluded from Auto Backup, against the new
      file locations
- [ ] No credential is ever written to a location Auto Backup includes, at any point during the
      migration
- [ ] `PreferenceFlow.kt` either retired or its continued purpose recorded
- [ ] Device-verified: log in works, a restart keeps the session, mock mode still swaps
- [ ] `./verify.sh` green

## Notes

Closing status **In Review**, and it is the highest-blast-radius task in cu-210's programme. It
touches credentials, the backup rules, the export format and the verification tooling — four things
whose failure modes are all quiet.

If stage 3 looks risky when it is reached, **stopping after stage 2 is a legitimate outcome**: the
modernisation argument is strongest for ordinary settings, and the credential file is the part where
`SharedPreferences` being "legacy" buys the least.
