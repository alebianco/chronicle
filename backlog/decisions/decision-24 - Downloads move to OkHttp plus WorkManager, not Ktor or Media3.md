---
id: decision-24
title: "Downloads move to OkHttp plus WorkManager, not Ktor or Media3"
status: accepted
created_date: '2026-09-07'
---

## Context

Downloads ran on **Fetch2**, which is abandoned: last commit 2024-12-03, no release after 3.4.1,
121 open issues including one titled *"This repository seems to be out of maintenance."* It arrived
through **JitPack**, which builds on demand and offers no guarantee an artifact stays resolvable.

An earlier task vendored it into `libs/fetch2-mirror/` — 436 KB of Apache-2.0 binaries — and was
explicit that this was **de-risking, not a fix**: its last acceptance criterion read *"No migration
attempted."* The mirror stops a JitPack outage breaking the build. It does not stop the dependency
being dead, and the embedded copy is the thing the owner objected to:

> *"I don't like having a library embedded in our code and would like a more seamless solution.
> Something multiplatform maybe."*

This is the decision that replaces it.

## Already settled, and not relitigated here

**Media3 `DownloadManager` stays rejected.** Not because of Fetch2's health — because of the
on-disk layout. Downloads are plain files at `<cachedMediaDir>/<trackId>.<ext>`, and **7 files
depend on that**, including `MoveSyncLocationWorker`, which moves audio between the SD card and
internal storage. Media3's `SimpleCache` uses an opaque internal layout with its own index, so
adopting it means rewriting or dropping storage-location support *and* migrating every
already-downloaded book or forcing a re-download. Two prior tasks reached this conclusion
independently.

**The platform `DownloadManager` stays rejected.** It cannot attach per-request auth headers
cleanly, and Plex requires `X-Plex-Token` on every media request.

## Decision

**OkHttp directly, orchestrated by WorkManager, behind a `Downloader` interface.**

### Why OkHttp

- **It is already a first-class dependency** at 5.4.0, with `logging-interceptor` and
  `mockwebserver3` already declared. Nothing new enters the build.
- **Fetch2 already delegated to it.** The `fetch2okhttp` artifact is what performed every byte of
  every download. So this *removes a layer* rather than adding one — the HTTP client, connection
  pool and TLS stack are unchanged, which is the strongest available argument that download
  behaviour will not shift.
- **WorkManager already owns the surrounding concerns** — retry, exponential backoff, network
  constraints, foreground notification — and is already wired with a `WorkerFactory` (now Hilt's).
  Fetch2 duplicated all of it in its own service.
- **Resumable download over HTTP `Range` is small**, on the order of a few hundred lines, and is the
  one piece Fetch2 contributed that we must reproduce.

### Weighed against principle 3

Principle 3 prefers a maintained third-party library over hand-rolled code, and this decision
writes code. The principle's own exception applies: **the thing being replaced is unmaintained**,
so "a boring, well-tested dependency" is not on offer. The choice is between owning a few hundred
lines of `Range` handling and owning a 436 KB binary mirror of a dead project — and the mirror
cannot be patched, audited against a source tree, or upgraded.

### Why not Ktor

Genuinely multiplatform, actively maintained, Apache-2.0 — and still declined:

- It is **a second HTTP stack** alongside OkHttp. Retrofit is not moving, so both would ship.
- Using its **OkHttp engine** does not fix that: it adds the Ktor client layers *on top of* the
  OkHttp we already have, so the cost is additive rather than near-zero.
- It buys multiplatform reach in **the least portable part of the tree**. Downloads are a
  background service, WorkManager, notifications and SD-card paths.

### On "multiplatform", honestly

The owner asked for it, so it is answered directly rather than quietly dropped: **it buys nothing
here.** A prior measurement put `app/src/main` at 23.7% portable and found that the portable part
is already the best-tested part — so sharing would happen where sharing is least needed. Downloads
sit squarely in the unportable 76%: a foreground service, WorkManager (which has no KMP
equivalent), notifications, and volume paths.

Treated as a **tie-breaker, not a requirement**, per the task's own framing. There was no tie:
OkHttp wins on Android grounds alone. The `Downloader` interface introduced by this decision is
pure Kotlin over a small value type, so if a future Wear or desktop target ever appears, the
*decision logic* is already on the portable side of the seam and only the engine needs writing
twice — which is the outcome KMP would have given us anyway, without adopting KMP.

**No `commonMain` source set is created and no KMP plugin is applied.** That decision belongs to
its own task.

## Consequences

- `libs/fetch2-mirror/` is deleted and the JitPack repository entry leaves `settings.gradle.kts`.
  JitPack was there **only** for Fetch2.
- A guard test pins that no `com.tonyodev.fetch2` import returns, in the manner of the existing
  service-locator and token-logging guards.
- **The on-disk layout does not change.** `<cachedMediaDir>/<trackId>.<ext>`, with partials named
  exactly like finished files — no `.part` suffix. This is not an aesthetic choice: a prior task
  established that partials are named identically because Fetch2 downloads in place,
  `MoveSyncLocationWorker` selects with `MediaItemTrack.cachedFilePattern`, and *giving partials a
  distinguishing suffix starts orphaning them*, because the prune only scans the active
  `cachedMediaDir`. `SyncLocationMoveTest` passes untouched.
- **Zero forced re-downloads.** An existing partial is resumed with a `Range` header; an existing
  complete file is left alone. The household has real downloaded audio, and four separate prior
  tasks have failure modes that end in deleted audio — this is the highest-risk area in the app.
- The token-redaction guarantee that `RedactingFetchLogger` provided is preserved by the
  replacement, and `TokenLoggingTest` continues to fail the build if a token can be logged.
- Fetch2's Int-only group API goes away, and with it the book-id hashing it forced. The real book id
  travels directly instead of in an `Extras` map, because a hash could not be reversed.

## What would change this

A maintained, Android-native, resumable download library with per-request headers and a
caller-controlled on-disk layout. None was found. If one appears, the `Downloader` seam is the
place it plugs in, and that is the main reason the seam exists rather than the engine being wired
in directly.
