---
id: decision-14
title: "Won't do: DRM sources, paid tier, cloud-SDK/SMB backends, and other cuts"
date: '2026-07-13'
status: accepted
---

## Context

Scope discipline (D12 rule 7 / [[decision-12]], [[decision-9]], [[decision-11]]).

## Decision

Permanently out: DRM sources (Audible AAX/AAXC, Kobo, Play Books, Storytel — no public playback API, decryption = circumvention exposure; users rip to their own Plex/ABS upstream via Libation/OpenAudible); paid tier / ads / open-core service (per [[decision-9]]); cloud-drive SDK sources + SMB (WebDAV [[cu-33.3]] covers NAS/Nextcloud without proprietary SDKs); Jellyfin/Emby/Subsonic adapters (metadata-poor). Deferred (revisit on demand): Wear OS; voice boost/EQ; Compose/Hilt/Jetpack-Navigation as standalone projects (adopt opportunistically only); playlists/collections rework and lks-nbg chapter-progress toggle (superseded by [[cu-19]]/[[cu-24]]/[[cu-30]]).

## Consequences

Keeps the roadmap focused on the household daily-driver; each cut is revisitable if the named condition changes.

**Re-checked 2026-09-05 against [[decision-19]]**, which re-stated D12 rule 7 (the authority several of these cuts lean on) so that the ban targets *data extraction* rather than proprietary code as such. **Nothing here becomes allowed.** DRM fails on circumvention exposure and the absent playback API, which is not an SDK question at all; cloud-drive SDKs fail the data-extraction test independently, and WebDAV ([[cu-33.3]]) is the open alternative reaching the same storage; paid tier and ads are barred by [[decision-9]]/[[decision-15]]. The item-by-item table is in decision-19.
