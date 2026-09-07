---
name: chronicle-scoped-write-blind-spot
description: "cu-127's SourceId scoping guards reads but not writes; check the write path stamps a resolved scope before believing a feature \"has no data\""
metadata: 
  node_type: memory
  type: project
  originSessionId: dd59db19-e160-45b1-bcb7-a627a38e3c6f
  modified: 2026-09-06T12:58:38.313Z
---

`ScopedQueryTest` fails the build on an unscoped **read**, and nothing checks an
unscoped **write**. cu-197 found collections had been written with
`SourceId.UNKNOWN` since cu-127 — invisible to every scoped read, so the
Collections tab was hidden for every user on every library.

**Why:** the symptom looks exactly like "this library has no collections". Both
CLAUDE.md and cu-187's task text asserted the household library had none; it had
four. A feature that silently does nothing is the documented failure mode of
scoping (decision-21), and it is self-camouflaging.

**How to apply:** before accepting "there is no data for this feature", query the
device DB directly — `adb shell run-as <pkg> sqlite3 databases/<db> "SELECT
source, count(*) FROM <T> GROUP BY source;"`. An empty-string source is
`SourceId.UNKNOWN` and means the write path never resolved a scope.

Two things that generalise beyond collections:

- A migration stamping `LEGACY_PLEX` correctly proves nothing: the *next refresh*
  can overwrite it with `UNKNOWN` through the broken write path. Adoption must
  claim both markers.
- Prefer pinning this behaviourally over real in-memory Room (a refresh must
  stamp; an unresolved scope must write nothing) rather than by source scan — the
  bad value is constructed in a model factory and only becomes wrong several
  frames later, so a text scan either misses it or fires on every mention.

Related: [[chronicle-device-check-catches-wiring]].
