---
id: decision-19
title: Principle 7 bans data extraction, not proprietary code as such
type: adr
status: accepted
created_date: '2026-09-05'
---

## Context

Principle 7 ([[decision-12]] rule 7) read:

> **Open formats, DRM-free, licence-free tools.** Open file formats for state (JSON/zip exports per
> D8/cu-17, markdown for docs); DRM-free audio only (DRM stores are a permanent won't-do,
> decision-14); OFL fonts for branding; no proprietary SDKs (no Firebase, no analytics, no ad SDKs);
> prefer open/keyless APIs (Audnexus, Open Library, Wikidata pattern from RESEARCH_FINDINGS §5.1).

The clause **"no proprietary SDKs"** is stated as an absolute, but its three parenthesised examples
— Firebase, analytics, ad SDKs — are all **data-extraction** SDKs. The rule as written bans a
category; the examples describe a much narrower harm.

cu-168 (Google Cast) exposed the gap. The owner's instruction was unambiguous — *"we'll definitely
support casting"* — but Cast can only be reached through `play-services-cast-framework`, a
closed-source Google SDK, and there is **no open reimplementation**: the receiver protocol is
Google's and undocumented. UPnP/DLNA is the open alternative and is not a substitute, because Plex
servers do not advertise it and it cannot reach the Chromecast or Google-TV device a household
actually owns.

So the literal rule forbids a feature the owner asked for, on grounds that do not apply to it: Cast
sends the household's data to no third party. It moves audio from a server the household already
runs to a speaker in the same house.

**This is not a new exception.** The app already ships `play-services-oss-licenses` (plus its Gradle
plugin) and has since before this rule was written — `SettingsFragment` launches
`OssLicensesMenuActivity` to render the open-source licence list. A proprietary Google SDK, used to
*display licence attribution*, is the clearest possible case of the rule's letter contradicting its
purpose. It was never litigated because nobody noticed.

Two exceptions to one rule, both benign, is evidence the rule is mis-stated rather than evidence of
lax enforcement.

## Decision

**Principle 7 is re-stated. What is banned is a dependency that extracts the household's data or
holds functionality hostage — not proprietary code as such.**

The replacement text, which supersedes the rule 7 quoted above:

> **Open formats, DRM-free, no data extraction.** Open file formats for state (JSON/zip exports per
> D8/cu-17, markdown for docs); DRM-free audio only (DRM stores are a permanent won't-do,
> [[decision-14]]); OFL fonts for branding; prefer open/keyless APIs (Audnexus, Open Library,
> Wikidata pattern from RESEARCH_FINDINGS §5.1).
>
> **No dependency may extract the household's data or gate functionality behind a third party.**
> Analytics, telemetry, crash reporting, advertising, and anything requiring a cloud account are
> permanently out, whatever their licence — an *open-source* analytics SDK is equally banned, since
> the objection is the data flow, not the licence.
>
> A **proprietary SDK for a device capability the platform exposes no other way** is permitted when
> all four hold:
>
> 1. **It sends the household's data nowhere.** Media addressed by the receiver is not extraction;
>    a usage report is.
> 2. **It degrades to absent.** A device without it loses that one feature and nothing else — no
>    crash, no error, no nag, no degraded core playback.
> 3. **It is confined behind a seam**, so a future open replacement is a swap and not a rewrite.
> 4. **There is genuinely no open alternative** that reaches the same hardware. Inconvenience does
>    not qualify; absence of a working protocol does.
>
> Anything admitted this way is recorded as an ADR naming which capability and how the four
> conditions are met.

> **Amended 2026-09-05 by [[decision-20]].** The clause above bans crash reporting and anything
> needing a cloud account outright. That was too broad in one direction: it drew a single line
> (*does data leave?*) where two are needed (*does data leave, **and did the user ask?***).
> decision-20 adds a second admission route for **data the user explicitly opts into sending** —
> consent-gated crash reporting and settings cloud sync — while making the ads/analytics/telemetry
> ban *stronger*, since those are barred regardless of consent. Read the two together.

Google Cast (cu-168) qualifies: media flows from the household's own Plex server to a receiver on
the household's own network; a de-Googled device shows no Cast button and is otherwise unaffected;
every Cast SDK reference is confined to `CastPlayerProvider`; and no open protocol reaches a
Chromecast. `play-services-oss-licenses` qualifies on the same reading, retroactively.

## Consequences

**This narrows the rule's stated form while making it stricter in substance.** The previous wording
said nothing about *open-source* telemetry, which it therefore permitted by omission; the new
wording bans it outright. A licence test is replaced by a behaviour test, and behaviour is the thing
that was actually being protected.

**Re-checked against [[decision-14]]'s permanent exclusions. Nothing previously out becomes
allowed:**

| Exclusion | Still out under the new rule, because |
|---|---|
| DRM sources (Audible AAX/AAXC, Kobo, Play Books, Storytel) | Never an SDK question. The objection is decryption and circumvention exposure, plus the absence of a public playback API — untouched by any of this. |
| Cloud-drive SDK sources | Fails condition 1 independently: sending the library outward *is* the extraction being banned. Also fails 4 — WebDAV ([[cu-33.3]]) is the open alternative that reaches the same NAS/Nextcloud storage. |
| Paid tier / ads / open-core | Barred by [[decision-9]] and [[decision-15]], not by rule 7. Advertising is additionally named in the new text. |
| Firebase, analytics | Named explicitly in the new text, and now more clearly banned than before, since the licence loophole is closed. |
| SMB | Unchanged — covered by the WebDAV reasoning above. |
| Jellyfin/Emby/Subsonic | Excluded for being metadata-poor; nothing to do with licensing. |

**Costs, accepted.**

- **The four conditions are a judgement call, not a lint rule.** Nothing mechanical enforces them,
  and "no open alternative" is the softest of the four — it can decay as ecosystems change without
  anyone noticing. The ADR obligation is the only backstop.
- **Every such SDK is dead weight for users who cannot use it.** The Cast dependency ships to
  de-Googled installs that will never load it. Accepted rather than solved with product flavours:
  `NOTES.md` records that flavours (`freeAsInBeer`) were deliberately removed, and re-adding them
  would double every build and release path permanently.
- **A precedent invites more requests.** Mitigated by condition 4 being genuinely hard to satisfy —
  most proprietary SDKs have an open alternative, and most that do not are extraction SDKs failing
  condition 1 anyway.

**Not changed.** Open formats, DRM-free audio, OFL fonts and the preference for open/keyless APIs
carry over verbatim; only the SDK clause is re-stated, and the rest is repeated above solely so the
principle can be read as one sentence again.

## References

- cu-168 — Google Cast, the feature that forced this
- [[decision-12]] — the principles this amends (rule 7)
- [[decision-14]] — the permanent won't-dos, re-checked above and unchanged
- [[decision-9]], [[decision-15]] — monetization bans, which are what actually exclude ads/paid tiers
