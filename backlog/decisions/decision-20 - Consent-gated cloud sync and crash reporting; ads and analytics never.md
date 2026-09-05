---
id: decision-20
title: Consent-gated cloud sync and crash reporting; ads and analytics never
type: adr
status: accepted
created_date: '2026-09-05'
---

## Context

[[decision-19]] re-stated principle 7 so the ban targets **data extraction** rather than proprietary
code, and admitted a proprietary SDK only for *a device capability the platform exposes no other
way* (Google Cast being the first). Written that way, it bans two things the owner does want:

- **Settings cloud sync** — barred by "anything requiring a cloud account".
- **Crash reporting** — named explicitly alongside analytics and telemetry.

Neither is a device capability, so neither fits decision-19's carve-out. They are a different case:
**data that leaves the device with the user's knowledge and on the user's instruction.** decision-19
drew one line (does data leave?) where two are needed (does data leave, *and did the user ask?*).

The owner's position, 2026-09-05: cloud settings sync as a commodity for users who have Play
services; crash reporting only behind a prompt and confirmation; **ads and analytics never**; data
local as much as possible.

## Decision

**Principle 7 gains a second admission route, alongside decision-19's device-capability one.**

> A dependency that sends data off the device is permitted **only** when all of:
>
> 1. **The user asked for it**, per feature, off by default. Consent is *informed* — the user is
>    told what leaves and where it goes — and revocable, with revocation stopping future sends.
> 2. **It is not the product's business model.** Nothing may exist to monetise, measure, or profile
>    the user. This is what separates a crash report from telemetry, and it is not negotiable.
> 3. **The payload is an allowlist, never a dump.** Enumerated fields only, and the same allowlist
>    discipline the settings export already enforces (`BACKUP_SETTING_KEYS`, never
>    `sharedPreferences.all`).
> 4. **No credentials, ever** — see the auth rule below.
> 5. **The feature degrades to absent.** A user who declines, or a device that cannot reach the
>    service, loses that feature and nothing else.
>
> **Permanently barred regardless of consent**, because consent is not the objection: advertising,
> behavioural analytics, usage telemetry, and any profile of what the household listens to. A user
> cannot opt into these because they will not be built. This restates [[decision-9]]/[[decision-15]]
> from the data side rather than the money side.

### Crash reporting: opt-in once, every report still shown

Enabled in settings, off by default. **Each individual crash still requires a tap to send**, with
the payload viewable first. Consent to the *feature* is not consent to a *standing upload channel* —
that distinction is the whole point, and an "enable once, upload silently" design is exactly the
telemetry shape condition 2 bars.

Costs, accepted: a crash loop can nag, and crashes the user cannot be bothered to send are never
seen. That is the price of no unattended uploads, and it is the right trade for a household app
whose north star is *zero interventions* for the listener, not maximal diagnostics for the
developer.

A report must carry a stack trace and build metadata and **nothing identifying the library** — no
book titles, no server names, no URLs. Note this is already a solved problem here: `TokenLoggingTest`
and `CollectionLoggingTest` fail the build on a log line that interpolates a token or a collection,
so the same scrubbing discipline extends to a crash payload.

### Settings cloud sync: the user's own Drive, app-private

Target is the **Google Drive `appDataFolder`** — a per-app private folder in the *user's own* Drive,
invisible to other apps and to the developer, needing no server of ours. "Commodity for users who
have Play services" is exactly what this is: no infrastructure, no account of ours, no cost.

Degrades to absent on a device without Play services — which is both development tablets, so the
declined path is the one that gets exercised.

### Auth is not synced, and D8 is unchanged

The owner's first framing was *"if it's private data include auth too, anything that makes device
migration seamless"*. **Auth is excluded, and migration is still one tap** — the goal is met without
the token leaving the device.

[[decision-8]] says plainly: *"Auth tokens excluded (re-login on restore)."* That is enforced in four
places — `data_extraction_rules.xml`, `backup_rules.xml`, the `BACKUP_SETTING_KEYS` allowlist, and
`ChronicleAuth.xml` as a separate preferences file (cu-108) — with `BackupRulesTest` failing the
build if the two rules files disagree. Three specifics make a synced Plex token worse than the
Android backup this machinery already refuses:

- **Plex has no per-device revocation and no refresh token** (cu-10, cu-122/cu-123). A leaked
  account token is invalidated only by a password change with "sign out connected devices", which
  logs out *every* device the household owns. Removing a device at plex.tv invalidates **nothing**.
- **The token is account-wide**, not app-scoped: full Plex account, every server.
- **It would couple two independent compromises.** A Google account breach becomes a Plex account
  breach, permanently, and nothing above can contain it.

`data_extraction_rules.xml` already applies this reasoning to direct device-to-device transfer, on
the grounds that *"a token restored onto hardware whose owner has not authenticated to Plex is
exactly what D8 rules out."* A cloud round-trip is strictly weaker than that case.

**What sync must carry instead, so migration is genuinely seamless:** the server id, library id and
connection list, plus every user-set preference, bookmarks and per-book speed. All of these sit in
ordinary preferences beside the credentials — `SharedPreferencesPlexPrefsRepo` already groups the
three secrets (`PREFS_AUTH_TOKEN_KEY`, `PREFS_SERVER_ACCESS_TOKEN`, `PREFS_USER`) as separable,
while `server_id`, `library_id` and `server_connections_v2` are not secret. A restored device is
therefore fully configured and prompts only for a Plex sign-in.

**Known gap for whoever implements this:** `BACKUP_SETTING_KEYS` today is *preferences only* — it
carries no server, library or connection, so the current export alone would **not** deliver seamless
migration. Extending it is the implementation's first task, and every added key must be checked
against the cu-108 credential split rather than added by pattern.

## Consequences

**Good.** The two features the owner wants become buildable under a rule that states why they are
different from telemetry, instead of an exception that says they are special. The line moves from
*does data leave the device* to *does data leave, and did the user ask* — which is the distinction
that was actually meant. Ads and analytics get a stronger ban than before: previously implied by the
monetization decisions, now stated as a data rule that consent cannot unlock.

**Costs, accepted.**

- **"The user asked for it" is auditable only by reading the code.** No build gate can prove a
  consent prompt is honest, unlike `TokenLoggingTest` which mechanically proves a token is not
  logged. Each such feature needs its consent path reviewed by a human, and that is an on-device
  review item, never a `Done`-by-machine one.
- **Two admission routes make principle 7 longer.** It no longer fits in a line. Accepted: the
  single-line version was what produced two accidental violations (decision-19).
- **Consent decay.** A user who enabled crash reporting years ago has not meaningfully consented
  today. Not solved here; worth a re-prompt on a material change to what is sent.
- **Drive `appDataFolder` is Google-specific.** A user without Play services gets no sync at all
  rather than a portable alternative. Deliberate — the owner framed it as a commodity for users who
  have it, and a backend-agnostic sync (WebDAV, Nextcloud) is a much larger scope that
  [[decision-11]]'s instinct would favour if it is ever wanted.

**Unchanged.** [[decision-8]] stands verbatim, including the auth-token exclusion and the cu-108
machinery. [[decision-14]]'s exclusions stand — cloud-drive **sources** remain out, and this is not a
loophole for them: syncing a settings file to the user's own app-private folder is not the same as
adding a cloud-drive media backend, which fails condition 3 (a whole library is not an allowlist)
and is covered by WebDAV ([[cu-33.3]]) anyway. [[decision-9]]/[[decision-15]] stand and are now
reinforced from the data side.

## References

- [[decision-19]] — the device-capability admission route this sits beside
- [[decision-8]] — auth tokens excluded from backup; unchanged, and the reason sync carries none
- [[decision-9]], [[decision-15]] — monetization bans, restated here from the data side
- [[decision-12]] — the principles both this and decision-19 amend (rule 7)
- cu-108 — the `ChronicleAuth.xml` credential split that makes "sync settings, not secrets" mechanical
- cu-17, cu-22, cu-77 — the export format, its allowlist, and the keys-not-values validation trap
