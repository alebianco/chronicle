---
id: DRAFT-66
title: OkHttp 5 and Retrofit 3 migration
status: Draft
assignee: []
created_date: '2026-08-31'
labels: [R2, hygiene]
dependencies: [cu-9, cu-10, cu-11]
priority: low
milestone: m-2
---

> **Draft id note.** Filed as `DRAFT-66` so the Backlog.md drafts view can see it —
> the tool keys drafts on the `DRAFT-` id prefix, not the directory or the status field.
> On promotion it becomes a `cu-` task again. Existing references to **cu-66** mean this file.

## Description

Deferred from cu-65. Both are major-version migrations sitting in the Plex networking layer:

- **OkHttp 4.12.0 → 5.x** — Kotlin rewrite; some APIs moved to properties, `MediaType`/`Headers`
  factory changes, deprecated methods removed.
- **Retrofit 2.11.0 → 3.x** — requires OkHttp 5, and adjusts converter/call-adapter APIs.

### Why it is deferred rather than done

R1 (cu-9 progress reporting, cu-10 silent re-auth, cu-11 connection resiliency) is about to rework the
exact layer these libraries sit in — `PlexInterceptor`, `PlexConfig`'s connection tiering, and the
whole retry/auth path. Migrating first would mean migrating code that is about to change, then
migrating it again.

Doing it after R1 also means the cu-16 fixture pack and `FakePlexServer` will have grown the coverage
needed to verify the migration properly, rather than checking it by compilation alone.

### When picked up

Take them together — Retrofit 3 requires OkHttp 5, so splitting them creates an unbuildable
intermediate state. `FakePlexServer` uses `okhttp3.mockwebserver`, which also moves in OkHttp 5
(the artifact is `mockwebserver3`), so the test server needs updating in the same change.

## Acceptance Criteria

- [ ] OkHttp 5 and Retrofit 3 in use; `FakePlexServer` migrated to mockwebserver3
- [ ] cu-16 contract and FakePlexServer tests pass unchanged
- [ ] Verified against the mock: login, library sync, and playback all still work
