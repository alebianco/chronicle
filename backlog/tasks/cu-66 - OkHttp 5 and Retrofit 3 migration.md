---
id: cu-66
title: OkHttp 5 and Retrofit 3 migration
status: Done
assignee: []
created_date: '2026-08-31'
labels:
  - R2
  - hygiene
milestone: m-2
dependencies:
  - cu-9
  - cu-10
  - cu-11
priority: low
ordinal: 86000
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

- [x] OkHttp 5 and Retrofit 3 in use — **5.4.0** and **3.0.0**; see the version note below
- [ ] `FakePlexServer` migrated to mockwebserver3 — **deferred to [[cu-163]]**, see below
- [x] cu-16 contract and FakePlexServer tests pass unchanged
- [x] Verified against the **live server**, not the mock: login, library sync and playback all work

## Implementation Notes (2026-09-04)

**Two of the draft's premises did not survive checking.**

*"Retrofit 3 requires OkHttp 5, so splitting them creates an unbuildable intermediate state."*
Retrofit 3.0.0's POM declares **OkHttp 4.12.0**. The two are independent and either could have
landed alone. They were still taken together, but because that is tidier, not because it was forced.

*"Some APIs moved to properties, factory changes, deprecated methods removed."* The app needed
**one** change across 25 networking files — see below. The rest compiled untouched.

### OkHttp 5.4.0, not 5.5.0

5.5.0's `okhttp-android` artifact requires **compileSdk 37**; this project is on 36 (cu-6). Bumping
compileSdk is a toolchain decision with Play Store implications and does not belong inside a library
upgrade. 5.4.0 is the newest release that builds against 36 — checked by reading `minCompileSdk` out
of each AAR's metadata from 5.0.0 up.

Worth knowing when compileSdk next moves: 5.5.0 is then available, and this pin can go.

### The one real break, and it was our fault

`AudiobookDetailsBindingAdapters` imported **`okhttp3.internal.toHexString`** — reaching into
another library's internal package for a hex conversion, in a log line. OkHttp 5 closed the package,
which is what an internal package is for. Replaced with Kotlin's `toString(16)`, which needs no
dependency at all. It was the only `okhttp3.internal` import in the app.

### mockwebserver: the bridge works, the migration is separate

The legacy `okhttp3.mockwebserver` package **still ships at 5.4.0** as a compatibility shim — the jar
contains a literal `DeprecationBridgeKt`. So all four users compile and pass unchanged, and the
draft's "the artifact is `mockwebserver3`" is true of the new API rather than a forced move.

Migrating properly is a real rewrite, not an import swap: v3's `MockResponse` is immutable
(builder-based) where the code uses `MockResponse().setResponseCode(...)` — 16 call sites across 525
lines of two fixture servers. Filed as [[cu-163]] rather than bundled into a dependency upgrade that
is otherwise a two-line diff.

### Verification

`./verify.sh` green and `./test_release_build.sh` passes its R8 dex assertions (7,882 classes). More
to the point for a networking change, verified on the tablet against the **household server**: the
196-book library synced with cover art, and playback streamed — position advanced 131s → 160s with
`buffered position` at 357s, so real audio moved over the new stack. A compile proves none of that.
