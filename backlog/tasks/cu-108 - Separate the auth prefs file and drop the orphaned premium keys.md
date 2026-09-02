---
id: cu-108
title: Separate the auth prefs file and drop the orphaned premium keys
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-02 09:55'
labels:
  - R1
  - trust
  - security
milestone: m-1
dependencies: []
priority: medium
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

- [x] Owner decides whether the prefs-file split is worth its migration risk
      — **decided 2026-09-02: yes, do both halves.**
- [x] Tokens live in a separate file, with an idempotent migration that cannot sign a user
      out if interrupted, and `BackupRulesTest` covers both files at both API levels
- [x] The backup rules exclude only the credentials file, and settings are allowed into
      platform backups
- [x] The premium-key removal migration runs once and is covered by a test asserting both keys are
      gone afterwards
- [x] A test proves the migration is idempotent — running it twice must not clear a working token

## Implementation Notes

Both halves shipped 2026-09-02, verified on the tablet **against live credentials** — the riskiest
possible test, since a wrong migration signs the owner out.

### What moved, and what did not

Only the three genuine credentials went to `ChronicleAuth.xml`:

| Key | Moved? | Why |
|---|---|---|
| `auth_token` | yes | the Plex account token |
| `server_token` | yes | the server access token |
| `user` | yes | serialized `PlexUser`, which carries a nested `authToken` |
| `uuid`, `id` | no | a client identifier and an OAuth temp id — not credentials, and `uuid` is a stable device identity that *should* survive a restore |
| `server_*`, `library_*` | no | the user's choice of server and library: harmless, and worth restoring |

Confirmed on the device afterwards — `Chronicle.xml` now holds exactly
`id`, `key_last_refresh`, `key_offline_mode`, `key_refresh_rate`, `key_skip_silence`,
`key_sync_location`, `library_id`, `library_name`, `server_connections_v2`, `server_id`,
`server_name`, `server_owned`, `uuid` — and none of the three credentials, each of which is
present in `ChronicleAuth.xml`.

### Correction to this task's own framing

The draft claimed the split would remove the need for `BACKUP_SETTING_KEYS`. **It does not.** The
settings file still holds `uuid`, `id`, `library_*` and `server_*`, none of which belong in a
settings export, so the allowlist stays load-bearing.

What the split actually buys is narrower but still worth having: a mistake in the allowlist can no
longer leak a **token**. The security property becomes structural, and the allowlist degrades from
"the only thing between a token and a plaintext file the user syncs to Dropbox" to "the thing that
keeps unrelated bookkeeping out of an export".

### The trap that shaped the design

`server_token` was written **inside the `server` setter**, in one `commit()` with `server_name`,
`server_id` and `server_owned`. That cannot stay atomic across two files, so the *ordering* decides
which orphan an interrupted write leaves:

- **token first** (chosen) — a crash leaves a token with no server. Invisible: the getter's
  `name.isEmpty()` guard already reads that as "no server chosen", and the next login overwrites it.
- **server first** — a crash leaves a server with no token. Identical to the user, but it *lost a
  working credential* to get there.

Same visible outcome, opposite cost. The comment in the setter records why.

### The migration

One shot on construction, guarded by a marker in the auth file, ordered so **no credential is ever
absent from both files**:

1. the marker short-circuits a completed migration (idempotent);
2. values **and** marker land in the auth file in one `commit()`, so the marker cannot appear
   without the data it describes;
3. only then are the originals removed from the settings file.

A crash before (2) leaves everything in the settings file and the migration re-runs. A crash
between (2) and (3) leaves the values in both, and reads prefer the auth file, so the duplicate is
invisible and cleaned up on the next write.

Reads fall back to the settings file, and every credential *write* clears the other side — so
whichever file was written last is the one that answers, and a signed-out account cannot be
resurrected from a leftover value.

`clear()` deliberately leaves both markers intact: wiping them would re-run the migration against
a settings file with nothing left to move.

### The premium keys

Verified genuinely orphaned before touching them: `key_is_premium` and `key_premium_token` appear
nowhere in `app/src/main` except one comment. Exact strings confirmed from cu-60's own diff rather
than from the surviving comments. Guarded by its own marker, separate from the credential
migration's — conflating the two would mean a future change to either re-runs the other.

### Verification

14 tests in `AuthPrefsMigrationTest`, all sabotage-checked:

- **clear-before-commit** (the classic version of this mistake) fails 3 tests, including
  `an interrupted migration recovers on the next launch`;
- **removing the idempotency guard** fails 2, including `the auth file wins over a stale settings
  copy`.

On the device, against live credentials: `Moved 3 credential(s) into the separate auth prefs file`,
`hasAccountToken = true`, library loaded. Second launch re-ran nothing and stayed signed in. No
premium keys were removed because this install postdates cu-60 — the marker was still set, so it
will not look again.

`BackupRulesTest` gained two tests asserting the settings file is **not** excluded, which is the
point of the split; they parse the `path` attributes rather than substring-matching, since
`"ChronicleAuth.xml"` and `"Chronicle.xml"` are easy to confuse in a `contains` check.

Coverage 28.16% -> 28.39%.

## Notes

Note that (2) does **not** depend on (1) and could ship on its own. Splitting this draft into two
tasks on promotion is reasonable if the owner wants the cheap half now and the structural change
later.

Related: [[cu-17]] (the schema, allowlist and Auto Backup exclusions), [[cu-77]] (the SAF picker
that raised both questions), [[cu-60]] (which orphaned the premium keys).
