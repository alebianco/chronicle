---
id: DRAFT-170
title: "Settings cloud sync to the user's own Drive"
status: Draft
assignee: []
labels: [comfort, feature]
dependencies: []
priority: medium
---

## Description

Owner decision 2026-09-05, recorded as [[decision-20]]: settings sync as **a commodity for users who
have Play services**. Target is the **Google Drive `appDataFolder`** — a per-app private folder in
the *user's own* Drive, invisible to other apps and to us, needing no server of ours.

Opt-in, off by default, degrades to absent without Play services — which is both development
tablets, so the declined path is the one that gets exercised locally.

## The thing to get right

**No auth token syncs.** [[decision-8]]'s "auth tokens excluded (re-login on restore)" stands, and
decision-20 records why syncing one is worse than the Android backup that already refuses it: Plex
has **no per-device revocation and no refresh token** (cu-10, cu-122), the token is account-wide,
and syncing it would make a Google account breach a Plex account breach with no way to contain it.

Migration is still one tap: sync carries **server id, library id, connection list, all settings,
bookmarks and per-book speed** — the restored device is fully configured and prompts only for a Plex
sign-in.

**Known gap, and the first task.** `BACKUP_SETTING_KEYS` today is **preferences only** — it carries
no server, library or connection, so the existing export alone would *not* deliver seamless
migration. Extending it is step one, and every added key must be checked against the cu-108
credential split (`PREFS_AUTH_TOKEN_KEY`, `PREFS_SERVER_ACCESS_TOKEN`, `PREFS_USER` are the three
secrets, already grouped as separable in `SharedPreferencesPlexPrefsRepo`) rather than added by
pattern match.

Note the cu-77 trap: the allowlist gates **keys, not values**, so an imported value with a closed
set of valid options still needs validating on the way in.

## Acceptance Criteria

- [ ] Off by default; enabling it states plainly what syncs and where it goes
- [ ] `BACKUP_SETTING_KEYS` extended to carry server id, library id and connections — with a test asserting **no credential key** is reachable through it, sabotage-verified
- [ ] A restored device is fully configured and asks only for a Plex sign-in
- [ ] Nothing syncs on a device without Play services, and no error is shown for it
- [ ] Disabling sync stops future uploads and says whether existing data is removed
- [ ] Bookmarks and per-book speed survive the round trip (cu-22: no server holds a copy of a bookmark)
