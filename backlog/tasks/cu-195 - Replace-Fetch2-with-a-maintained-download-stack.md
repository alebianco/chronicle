---
id: cu-195
title: Replace Fetch2 with a maintained download stack
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - trust
  - debt
dependencies: []
priority: medium
milestone: m-2
ordinal: 63500
---

## Description

Owner ask, 2026-09-06: *"I don't like having a library embedded in our code and would like a more
seamless solution. Something multiplatform maybe."*

cu-166 vendored Fetch2 into `libs/fetch2-mirror/` (436 KB of Apache-2.0 binaries) because
`com.github.tonyofrancis.Fetch:fetch2` is **abandoned** — last commit 2024-12-03, no release after
3.4.1, 121 open issues including one titled *"This repository seems to be out of maintenance."* —
and arrived via **JitPack**, which builds on demand and offers no guarantee an artifact stays
resolvable.

That was explicitly **de-risking, not a fix**: cu-166's last acceptance criterion reads *"No
migration attempted."* The mirror stops a JitPack outage breaking the build; it does not stop the
dependency being dead, and it is the embedded copy the owner objects to. This task is the fix.

## What is already settled — do not relitigate

**Media3 `DownloadManager` is rejected, and the reason is not Fetch2's health** (cu-12, re-confirmed
by cu-166). Downloads are plain files at `<cachedMediaDir>/<trackId>.<ext>`; **7 files depend on
that layout**, including `MoveSyncLocationWorker`, which moves files between SD card and internal
storage. Media3's `SimpleCache` uses an opaque internal layout with its own index, so adopting it
means rewriting or dropping storage-location support *plus* migrating every already-downloaded book
or forcing a re-download. cu-12 rejected it on those grounds and cu-166 left that conclusion
standing.

**Android's platform `DownloadManager` is rejected** — it cannot attach per-request auth headers
cleanly, which Plex requires.

## On "multiplatform"

Worth being straight about what this buys, because [[cu-182]] already measured the surrounding
question: only ~24% of the tree is portable, and it is *already* the best-tested part, so KMP would
share where sharing is least needed. **Downloads sit in the unportable part** — a background
service, WorkManager, notifications, SD-card paths.

So multiplatform should be treated as a **tie-breaker, not a requirement**. A KMP-capable HTTP layer
is worth preferring if it costs nothing extra, because it keeps a future Wear/desktop option open
(cu-182's Wear case is the strong one, and Wear *is* Android anyway). It is not worth accepting a
worse fit on Android to obtain.

## The candidates to weigh

The honest framing is that this is **less a library swap than a decision about how much we own.**

1. **OkHttp directly, orchestrated by WorkManager.** OkHttp 5.4.0 is already a first-class
   dependency, and `fetch2okhttp` is what Fetch2 delegates to — so this removes a layer rather than
   adding one. Resumable download over HTTP Range is roughly a few hundred lines; WorkManager
   already handles retry, backoff, constraints and foreground notification, and cu-179 is adding a
   `WorkerFactory`. **Weigh against principle 3**, which prefers a maintained dependency over
   bespoke code — but note the principle's own exception, and that the thing we would be replacing
   is *unmaintained*, so "boring well-tested dependency" is not on offer here.
2. **Ktor client.** Genuinely multiplatform, actively maintained (JetBrains), Apache-2.0. Costs a
   second HTTP stack alongside OkHttp unless Retrofit moves too — which is a much larger change.
   Assess whether its OkHttp *engine* makes that cost near-zero.
3. **Something else maintained and Android-native** — survey honestly rather than assuming these
   two are the field.

## The things to get right

- **The on-disk layout is load-bearing and must not change.** `<cachedMediaDir>/<trackId>.<ext>`,
  no `.part`/`.tmp` suffix — cu-153 established that partials are named exactly like finished files
  because Fetch2 downloads in place, `MoveSyncLocationWorker` selects with
  `MediaItemTrack.cachedFilePattern`, and *giving partials a distinguishing suffix starts orphaning
  them*, since cu-81's prune only scans the active `cachedMediaDir`. `SyncLocationMoveTest` pins
  this. **Any replacement that changes naming or resume strategy must be checked against that
  test**, and a layout change means a migration for every downloaded book.
- **Zero forced re-downloads.** The household has real downloaded audio; four separate tasks
  (cu-85, cu-81, cu-153, cu-76) have failure modes that end in *deleted audio*. This is the
  highest-risk area in the app.
- **Auth headers per request.** The Plex token is resolved in exactly one place
  (`PlaybackSession.authToken`) and empty counts as absent (cu-33). Never log it —
  `TokenLoggingTest` fails the build. Note `RedactingFetchLogger` exists precisely to keep tokens
  out of Fetch2's logs; whatever replaces it needs the same guard.
- **13 files import Fetch2 today**, and it reaches the `MediaSource` seam (`HttpMediaSource`,
  `PlexMediaSource`, `MediaSource`). Introducing an interface first — so the swap is behind a seam
  rather than a 13-file rewrite — is likely the right first step, and mirrors what cu-80 did for
  ingestion.
- **Downloads are not currently exercised end-to-end after the WorkManager change** (recorded in
  `maintainability-review-2026-09.md`). Fix that *before* swapping the engine, or there is no
  baseline to compare against.

## Acceptance Criteria

- [ ] A decision recorded as an ADR: which stack, and why — including the honest answer on whether
      multiplatform actually bought anything here
- [ ] `libs/fetch2-mirror/` deleted and the JitPack repository entry removed from
      `settings.gradle.kts`
- [ ] No Fetch2 import remains; a guard test pins that, in the manner of `ServiceLocatorUsageTest`
- [ ] On-disk layout unchanged — `SyncLocationMoveTest` passes untouched, and no already-downloaded
      book needs re-downloading
- [ ] Resume over HTTP Range verified against a real interrupted download, not only unit tests
- [ ] Auth headers attached; a token-redaction guard equivalent to `RedactingFetchLogger` is in
      place and `TokenLoggingTest` passes
- [ ] Verified on the tablet in **both** directions across its two real volumes (internal + physical
      SD card), as cu-153 did — different filesystems, so `Files.move` may fall back to copy+delete
- [ ] `./verify.sh` green; `./test_release_build.sh` passes (download classes are R8-sensitive)
