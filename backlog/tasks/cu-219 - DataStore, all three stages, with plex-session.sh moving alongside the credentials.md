---
id: cu-219
title: "DataStore, all three stages, with plex-session.sh moving alongside the credentials"
status: In Review
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

- [x] Stage 1: non-secret settings on DataStore, with a **migration from the existing prefs** so no
      user loses a setting
- [x] Stage 2: the export path still round-trips — `SettingsBackupRepoTest`, `BackupSchemaTest` and
      `BookmarkBackupTest` green, and coordinated with cu-189
- [x] Stage 3: credentials moved **and** `plex-session.sh` updated in the same change
- [x] `CHRONICLE_DEVICE=<serial> plex-session.sh status|real|mock` verified working against the
      tablet **after** stage 3, before the task closes. This is the criterion that protects every
      later device check
- [x] `BackupRulesTest` still proves the credentials are excluded from Auto Backup, against the new
      file locations
- [x] No credential is ever written to a location Auto Backup includes, at any point during the
      migration
- [x] `PreferenceFlow.kt` either retired or its continued purpose recorded
- [x] Device-verified: log in works, a restart keeps the session, mock mode still swaps
- [x] `./verify.sh` green

## Result (2026-09-08)

All three stages landed. **The approach changed at stage 3**, with the owner's latitude: rather than
moving credentials to DataStore's default `files/datastore/` location and rewriting the Auto Backup
rules to match, they moved to **`Context.noBackupFilesDir`**.

That is the difference between a rule and a property. The old protection was two XML files excluding
`domain="sharedpref" path="ChronicleAuth.xml"` — correct, but fragile in one specific way: an
exclusion is scoped to a domain, so the moment the file moves the rules keep parsing, keep passing
their tests, and quietly stop matching anything. Android excludes `no_backup/` by construction.
There is no rule left that *could* lapse.

The legacy exclusions stay, and `BackupRulesTest` now asserts both halves: that `CredentialStore`
writes to `noBackupFilesDir`, and that `ChronicleAuth.xml` is still excluded — an install that has
not launched since the migration still has live tokens in that file.

### Three defects the work surfaced, each caught by a guard

1. **Empty is a value.** The first `credentialString` used `takeIf { it.isNotEmpty() }`, so an empty
   credential fell through to a lower layer — resurrecting the token the user had just signed out
   of. `AuthPrefsMigrationTest` caught it; the fallback now tests *presence* via `hasKey`.
2. **`plex-session.sh restore` left mock credentials behind.** A backup predating the migration has
   no credential store, so the restore returned early and left whatever was on the device — which,
   coming back from mock mode, is the *mock* store. It wins the fallback read, so a "real" session
   would run on `mock-account-token`. Found on the tablet, not in review. The restore now clears
   first, unconditionally.
3. **A regex that ate real documentation.** Collapsing doubled KDoc blocks removed the *existing*
   docs rather than the new ones. Reverted and redone by hand.

### Device evidence

- Migration: `auth_token`, `server_token` and `uuid` now in
  `no_backup/plex-credentials.preferences_pb`; `ChronicleAuth.xml` retains only the two migration
  markers, so no token exists in two places.
- The app stayed **signed in across the migration** — `LOGGED_IN_FULLY`, no crash.
- **The owner's real Plex session works**: restored from backup, the app authenticated to plex.tv
  and retrieved the ANTARES server record. (The connection then failed because that server is not
  reachable from this network, which is not a credential question.)
- Full round-trip `real → mock → real` verified, so device verification tooling is intact.

## Follow-up: the migration markers moved too (2026-09-08)

The owner asked why migration flags lived in a file called `ChronicleAuth.xml`. The answer was that
they were correct when written and my stage-3 change had made them wrong.

**Why they were there.** The credential split put `credentials_migrated` beside the tokens it
describes, in the same `commit()`, so a marker and its data land together or not at all. Split them
across files and a crash between two writes leaves either a marker claiming success with no data, or
data that gets re-migrated over something newer.

**What stage 3 broke.** Moving the tokens to `no_backup/` left the markers behind — so
`ChronicleAuth.xml` became a file named for contents it no longer held, and the atomicity argument
that justified the arrangement was quietly gone.

Both markers now live in `CredentialStore`, restoring the original property. Two consequences that
needed fixing rather than noting:

- **The legacy migration was writing to a dead store.** `migrateCredentialsToAuthPrefs` copies
  credentials out of the settings file into `ChronicleAuth.xml` — which nothing reads now, and which
  `SharedPreferencesMigration` has already consumed by the time it runs. A pre-split install would
  have had its tokens stranded there. It writes to `CredentialStore` directly.
- **`ChronicleAuth.xml` is now deleted outright.** With the markers gone it is finally empty, so
  DataStore removes it — which it could not do while they kept it alive. `plex-session.sh` therefore
  treats it as *optional*: `backup` failed with "cannot read ChronicleAuth.xml" on a migrated device
  until the file list distinguished required from optional.

Sabotage-verified: disabling the marker check makes the migration re-run and resurrect a signed-out
account, which is the failure the guard exists for.

**Device-verified again, because the migration path changed.** Both markers and all three
credentials are in `no_backup/plex-credentials.preferences_pb`; `shared_prefs/` holds only
`Chronicle.xml` and the debug flags. The owner's real session — restored from a backup that predates
all of this — still authenticates to plex.tv and retrieves the ANTARES server. Round-trip
`real → mock → real` works. No relogin was needed.

## Notes

Closing status **In Review**, and it is the highest-blast-radius task in cu-210's programme. It
touches credentials, the backup rules, the export format and the verification tooling — four things
whose failure modes are all quiet.

If stage 3 looks risky when it is reached, **stopping after stage 2 is a legitimate outcome**: the
modernisation argument is strongest for ordinary settings, and the credential file is the part where
`SharedPreferences` being "legacy" buys the least.
