---
id: decision-8
title: Anything not re-derivable from Plex must be exportable
date: '2026-07-13'
status: accepted
---

## Context

Plex holds progress/ratings/queue, but local-only state accumulates (settings, bookmarks, per-book speed, votes/affinity, enrichment match-fixes, metadata/cover overrides in [[cu-37.2]], want-list).

## Decision

Every feature creating such state MUST register it in the versioned backup schema ([[cu-17]]) as part of its definition-of-done. Auth tokens excluded (re-login on restore).

## Consequences

Backup framework ([[cu-17]]) is a hard prerequisite for local-only backends ([[cu-33.2]]); adds a DoD line to many feature tasks.
