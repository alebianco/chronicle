---
id: decision-24
title: "The HTTP stack moves to Ktor, and downloads move with it"
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

**This decision was first written recommending OkHttp + WorkManager and was overturned by the
owner.** The superseded reasoning is kept below, because the fact that it was wrong is the useful
part of the record.

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

**Ktor becomes the app's only HTTP stack**, and the new downloader is written against it behind a
`Downloader` interface.

Concretely:

| from | to |
|---|---|
| Fetch2 + `fetch2okhttp` | Ktor client, `prepareGet(...).execute { }` streaming to a sink, `Range` for resume |
| Retrofit 3.0 (`PlexService`, 25 endpoints) | **Ktorfit** — same annotation shape, KSP-generated |
| `okhttp3.Interceptor` (`PlexInterceptor`) | Ktor `defaultRequest` + a custom plugin |
| `okhttp3.Authenticator` (`PlexTokenAuthenticator`) | a custom plugin on the `Send` hook |
| `coil-network-okhttp` | `coil-network-ktor3` — same Coil 3.3.0, drop-in |
| MockWebServer fixtures | Ktor `MockEngine`, or kept where they front a real socket |

OkHttp leaves the APK entirely.

### Why the first answer was wrong

The superseded version argued for OkHttp on the grounds that it was already first-class, that
`fetch2okhttp` meant downloads already ran on it, and so the swap "removes a layer rather than
adding one" and changes no HTTP behaviour. All of that is true, and it is still the lowest-risk
path for *downloads alone*.

What it got wrong was treating portability as a tie-breaker with no tie, when the relevant facts
point the other way:

- **OkHttp 5.0 dropped Kotlin Multiplatform support outright**, and this project is on 5.4.0.
  Square built it, disliked the trade-offs — no engine for Kotlin/JS, unwilling to build a TLS API
  for Kotlin/Native — and removed it. **Square's own recommendation for multiplatform is Ktor.**
  So standardising on OkHttp is not neutral on portability; it is a step away from it, and the ADR
  originally implied the opposite.
- **Retrofit 3.0 is not KMP-capable either.** It is still OkHttp/Okio-bound. So "keep Retrofit, add
  a Ktor downloader" permanently pins the network layer to the JVM.
- **`coil-network-ktor3` exists at our exact Coil version (3.3.0).** The claim that OkHttp would
  stay in the APK regardless, because Coil pulls it, was simply wrong — the owner caught this.

The "two HTTP stacks" objection that drove the first answer only holds if the migration stops
half-way. Moving the whole layer resolves it by leaving **one** stack, not two.

### On "multiplatform", honestly

A prior measurement put `app/src/main` at **23.7% portable**, and found the portable part is
already the best-tested part. That measurement is why the first version of this ADR discounted
portability, and it is worth being precise about what this decision does and does not buy.

**It does not make downloads portable.** They remain a foreground service, WorkManager (no KMP
equivalent), notifications and volume paths. The `Downloader` seam is pure Kotlin over a small
value type, so the *decision logic* sits on the portable side and only the engine would ever be
written twice — but that is a seam benefit, not a Ktor benefit.

**What it does buy** is that the HTTP layer and the models it deserializes stop being
JVM-bound. `PlexService`'s 25 endpoints and the Plex model DTOs are the largest slice of otherwise
portable code that Retrofit was holding on the Android side. This raises the portable share as a
side effect of work we were doing anyway, which is the test the library-questions task sets for
exactly this kind of change.

**This is not KMP adoption.** No `commonMain` source set is created and no KMP plugin is applied —
that decision belongs to the multiplatform research task, which this must not pre-empt. The
portable share is to be **re-measured** afterwards so that task inherits a current number.

## Consequences

- `libs/fetch2-mirror/` is deleted and the JitPack repository entry leaves `settings.gradle.kts`.
  JitPack was there **only** for Fetch2.
- A guard test pins that no `com.tonyodev.fetch2` import returns, in the manner of the existing
  service-locator and token-logging guards. A second guard pins that `okhttp3` does not return
  either, once it is gone.
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
  replacement, and `TokenLoggingTest` continues to fail the build if a token can be logged. Ktor's
  `Logging` plugin has its own sanitisation hook, which is where this now lives.
- Fetch2's Int-only group API goes away, and with it the book-id hashing it forced. The real book id
  travels directly instead of in an `Extras` map, because a hash could not be reversed.

### The two risks this decision accepts

**1. The 401 path loses a framework guarantee.** `PlexTokenAuthenticator`'s KDoc is explicit that
it is an OkHttp `Authenticator` rather than an `Interceptor` *because* OkHttp calls it only on a
401 and threads `Response.priorResponse`, which makes "retry exactly once" a property of the
framework rather than hand-rolled state. Ktor has no `Authenticator` equivalent: its `Auth` plugin
is shaped for `Authorization: Bearer` with a refresh-token grant, and **Plex has neither** — it
uses a custom `X-Plex-Token` header and has no refresh mechanism at all.

The port is a custom plugin on the `Send` hook, which calls `proceed(request)`, inspects the
response, and calls `proceed` **at most once more**. The once-only property therefore becomes
*explicit code* rather than an inherited invariant, so it must be pinned by a test that fails if a
second retry ever becomes possible. `ReauthWiringTest` is the existing guard and must survive the
port with its semantics intact, including the three distinct outcomes: refresh succeeded, refresh
returned the same token (account signed out — surfaced to the user), and refresh failed for network
reasons (**not** a signed-out signal, because being offline is not being signed out).

**2. The mock Plex fixtures front the device tooling.** `FakePlexServer` (tests) and
`MockPlexServer` (debug) are MockWebServer-based, and `plex-session.sh` — the script that swaps the
real Plex session for mock mode without `pm clear` — depends on the debug one. A fixture rewritten
carelessly breaks *device verification*, not just tests. Where a fixture needs a real socket it may
keep MockWebServer as a **test-only** dependency; only `app/src/main` must be free of OkHttp.

## What would change this

If Ktorfit or the `Send`-hook auth port cannot preserve `ReauthWiringTest`'s three outcomes, the
honest fallback is to stop at the downloader plus Coil, keep Retrofit, and record that the network
layer stays JVM-bound — rather than shipping a weaker 401 path for a portability gain that no
target currently consumes.
