---
id: decision-11
title: Multi-backend: resurrect the MediaSource seam, ABS before local files
date: '2026-07-13'
status: accepted
---

## Context

Dead MediaSource scaffolding exists (all TODO()). Market: Plex ring shrinking under 2025-26 price shocks, Audiobookshelf ring growing (CVR §2/§3). Resolves the old C6 'decide LocalMediaSource'.

## Decision

Resurrect the seam ([[cu-15]]/[[cu-33]]) with Plex as sole impl first, then adapters in order ABS ([[cu-33.1]]) -> local files ([[cu-33.2]]) -> WebDAV ([[cu-33.3]]). Interface carries capability flags (hasNarrator/hasSeries/hasServerProgress) so UI degrades gracefully; metadata normalized in adapters; resolution order manual override ([[cu-37.2]]) > enrichment ([[cu-37]]) > native. Jellyfin/Emby/Subsonic cut (metadata-poor); DRM stores permanently out ([[decision-14]]).

## Consequences

Interface-first (reversing doubles work); enrichment promoted from differentiator to cross-backend leveler; local files gated on backup ([[cu-17]]).
