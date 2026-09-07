---
id: cu-195
title: Replace Fetch2 with a maintained download stack
status: In Review
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

- [x] A decision recorded as an ADR: which stack, and why — including the honest answer on whether
      multiplatform actually bought anything here
- [x] `libs/fetch2-mirror/` deleted and the JitPack repository entry removed from
      `settings.gradle.kts`
- [x] No Fetch2 import remains; a guard test pins that, in the manner of `ServiceLocatorUsageTest`
- [x] On-disk layout unchanged — `SyncLocationMoveTest` passes untouched, and no already-downloaded
      book needs re-downloading
- [ ] Resume over HTTP Range verified against a real interrupted download, not only unit tests
- [x] Auth headers attached; a token-redaction guard equivalent to `RedactingFetchLogger` is in
      place and `TokenLoggingTest` passes
- [ ] Verified on the tablet in **both** directions across its two real volumes (internal + physical
      SD card), as cu-153 did — different filesystems, so `Files.move` may fall back to copy+delete
- [x] `./verify.sh` green; `./test_release_build.sh` passes (download classes are R8-sensitive)

## What landed

Seven commits, each green through all 8 `verify.sh` stages. **The stack chosen is not the one this
task expected** — see decision-24, which was written recommending OkHttp + WorkManager and then
overturned by the owner.

### The decision changed, and why

The first ADR argued OkHttp: already first-class, `fetch2okhttp` meant downloads already ran on it,
so the swap removed a layer and changed no HTTP behaviour. All true, and still the lowest-risk path
for *downloads alone*.

Three facts overturned it:

- **OkHttp 5.0 dropped Kotlin Multiplatform support**, and this project is on 5.4.0. Square built
  it, disliked the trade-offs, removed it, and now recommends Ktor. So standardising on OkHttp was
  a step *away* from portability, not neutral on it.
- **Retrofit 3.0 is not KMP-capable either**, so "keep Retrofit, add a Ktor downloader" would have
  pinned the network layer to the JVM permanently.
- **`coil-network-ktor3` exists at our exact Coil version.** The ADR's claim that OkHttp would stay
  in the APK regardless because Coil pulls it was simply wrong — the owner caught that.

The "two HTTP stacks" objection only held if the migration stopped half-way. Moving the whole layer
leaves one.

### The toolchain pin, which is not cosmetic

**Ktor 3.2.1 + Ktorfit 2.6.5** is the pair built against Kotlin 2.2.x. Ktorfit 2.7.5 (current)
requires kotlin-stdlib 2.4.0, and adding it produced **nine failures that never mention Ktor** —
four `[MissingType]: Element 'Audiobook'`, a Room `BookDatabase` failure, and four Hilt
assisted-injection errors citing `error.NonExistentClass` for `com.tonyodev.fetch2.Fetch`, a class
that resolved perfectly well. Raising the stdlib under KSP makes unrelated types disappear, so the
symptom points nowhere near the cause. Bisected rather than guessed. Raising either means raising
Kotlin first, which is its own task — the same shape as decision-22's compileSdk 37 / AGP 9.1 pin.

### The 401 path, which was the gate

`PlexTokenAuthenticator` was an OkHttp `Authenticator` rather than an `Interceptor` *because* OkHttp
invokes it only on a 401 and threads `Response.priorResponse` — so "retry exactly once" was a
**framework** property. Ktor has no equivalent: its `Auth` plugin wants `Authorization: Bearer` with
a refresh grant, and Plex has a custom header and no refresh at all.

The port puts the retry on the `Send` hook, where once-only is *structural in the function*: one
`proceed`, then at most one more as the last statement on its path. `PlexReauthWiringTest` holds all
six properties the OkHttp wiring test held, plus four more, and is sabotage-verified — replacing the
single retry with a loop fails `it retries exactly once and then gives up`. decision-24's fallback
(stop at downloads + Coil, keep Retrofit) was therefore not needed.

### Three production bugs the tests found

**`checkServer` needed `@Url`, not `@Path`.** It takes a whole server address, since its job is to
reach a server not yet chosen. Retrofit replaced the base URL for an absolute value in an encoded
`@Path`; Ktorfit *concatenates*, producing
`http://localhost:64821http://localhost:64821/lan/identity`. No unit test of the chooser could see
it — the chooser takes its probe as an injected lambda — and `ConnectionProbeWiringTest` exists for
exactly that blind spot. `/identity` then moved to the caller as `PlexConfig.identityUrl`, because
`@Url` takes its value verbatim.

**The probe had to stop throwing.** `expectSuccess = true` is *required*, because `ProgressReporter`
and the account-rejection check both branch on a thrown `ResponseException` and Ktor 3 defaults it
to **false** — so no exception ever arrived and a failed scrobble read as a success. But that made
an unreachable address abort the whole connection selection instead of falling through, which on a
household with a stale WAN entry means never finding the LAN server. Now
`runCatching { … }.getOrDefault(false)`.

**The `fail_sync` debug hook stopped working.** Throwing `IllegalStateException` was the obvious
port, and is wrong: `ProgressReporter` catches `IOException` and `ResponseException` specifically,
so a plain exception escapes uncaught and the sync-failure badge the hook exists to trigger never
appears. It now synthesises a real 400 through a throwaway `MockEngine`.

### The near-miss that mattered most

`partialsSafeToPrune` deletes a partial only when three things hold, the third being **the engine has
no record of it** — which is what stops the prune deleting a `PAUSED`/`FAILED` download a `Range`
request could resume. Fetch2 answered from its own SQLite queue, which survived a restart.
`KtorDownloader` tracks jobs in memory, so reading the engine there would report *nothing* pending
after a relaunch and make every resumable partial look abandoned. That is the app deleting the
user's partly-downloaded audio.

Hence `DownloadIntentStore`: a durable record, written *before* enqueueing so a crash between the
two leaves bytes protected, and deliberately **kept on failure** because a failed download is
exactly the resume candidate the rule protects.

### Retired

`libs/fetch2-mirror/` (436 KB), the JitPack repository entry — it was there only for Fetch2, so
every dependency now resolves from google() or mavenCentral() — `ResumePlan`,
`FetchGroupStartFinishListener`, `RedactingFetchLogger`, `PlexInterceptor`,
`PlexTokenAuthenticator`, three `OkHttpClient` providers, two Retrofit builders, and the
`EXTRA_BOOK_ID` round-trip that existed because Fetch2's `Int`-only group API hashed the book id
irreversibly.

`RetiredDependencyTest` keeps all three carves closed: no Fetch2, no OkHttp API, no Retrofit in
`app/src/main`. MockWebServer stays a **test/debug** dependency, deliberately — it fronts the mock
Plex fixtures that `plex-session.sh` drives, and `ConnectionProbeWiringTest` needs a real socket to
prove a URL went where it was meant to.

### Measured

| | |
|---|---|
| Release APK | 7,605,018 → **7,251,456** bytes (**-345.3 KiB** across cu-192 + this) |
| Coverage | 53.30% → 54.36% |
| `SyncLocationMoveTest` | passes **untouched** — last modified before this work began |
| `TokenLoggingTest` | passes; redaction is now `sanitizeHeader`, sabotage-verified with logging forced on |
| `./test_release_build.sh` | dex assertions pass |

Ktorfit's generated impls are excluded from coverage on the same reasoning as the Dagger/Room/Hilt
entries. Worth noting it is not a coverage *loss*: Retrofit built services as runtime proxies, so
there was no bytecode to measure, and Ktorfit generating real classes dropped `data/sources/plex`
7.7 points without a single test changing.

## Still open — why this is In Review

- [ ] Resume over HTTP Range verified against a real interrupted download, not only unit tests
- [ ] Verified on the tablet in **both** directions across its two real volumes (internal + physical
      SD card)

Range resume is unit-tested against `MockEngine` in four cases including the silent-corruption one
(a server that ignores `Range` and answers 200 — appending there splices a file's head onto its own
middle and yields a plausible-length corrupt track). **That is not the same as a real interrupted
download**, and this task is right to demand one. Device verification is running now; whatever it
does not cover stays on this list.

## A note for the multiplatform task

The portable share should be **re-measured**. This moved the HTTP layer off JVM-only libraries, but
the *models* stay Moshi-bound — `MoshiContentConverter` was written precisely so a serializer
migration did not have to happen at the same time as a transport migration. So the gain is the HTTP
layer's, not the models'. No `commonMain` source set was created and no KMP plugin applied.
