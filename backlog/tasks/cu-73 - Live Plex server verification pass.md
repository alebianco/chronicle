---
id: cu-73
title: Live Plex server verification pass
status: In Progress
assignee: []
created_date: '2026-08-31'
updated_date: '2026-09-02 22:55'
labels:
  - R1
  - agentic
  - verification
dependencies: []
priority: high
milestone: m-1
ordinal: 4000
---

## Description

A collection point for everything that can only be confirmed against a **real Plex
server with real credentials**. The cu-16 fixture pack made most work verifiable without
an account, which is why the backlog moves at all — but a fixture is a model of a server,
and a few things are only true if the real one agrees.

Rather than blocking each task on credentials, those items are recorded here and checked
in **one pass**, on a real server, at the end of the release.

**This task is the owner's to run** (it needs their Plex account and LAN). Agents add
items to the checklist; the owner executes and reports back.

### Why a single pass

Each item individually is a five-minute check. Setting up credentials, a device on the
right network and a populated library is the expensive part, so doing it once for a batch
is far cheaper than eleven times. It also surfaces interactions a per-task check would
miss — e.g. HTTPS enforcement (cu-42) and connection tiering (cu-11) both touch
connection selection.

## Checklist

Grouped by what breaks if the real server disagrees.

### Connection and transport

- [x] **cu-42 — HTTPS on the LAN.**
      **Verified 2026-09-01.** Confirmed: the app reached the server over `*.plex.direct` HTTPS with no cleartext exception. Confirm a local server connects over
      `https://<hyphenated-ip>.<hash>.plex.direct` and **not** plain http. This is the
      one with a known behaviour change: a server with Secure Connections set to
      *Disabled* will no longer connect on its LAN address. Verify the default
      (*Preferred*) works, and note what the failure looks like if someone has disabled
      it, so the error is diagnosable.
- [x] **cu-42 — `resources.json` fidelity.**
      **Verified 2026-09-02.** The fixture is faithful: `local`, `relay`, `protocol` and
      hyphenated-IP HTTPS all match a real response. Two harmless differences, no fixture change
      needed — the real payload also carries `address`, `port` and `IPv6` (all ignored by the
      model), and the real server reports **no relay connection** while the fixture has one, which
      is correct since the fixture must exercise that tier. Capture a real `/api/resources` response and confirm the shape
      matches; correct the fixture if not.

### Connection tiering (cu-11)

- [x] **A LAN-only server connects, and over LAN.**
      **Verified 2026-09-02, session 3.** `Chose LAN connection: https://192-168-1-54.<hash>.plex.direct:32400`
      — the exact log line this item demanded rather than an assumption, reproduced on two
      consecutive cold starts. Traffic really goes that way: **42** requests to the LAN address
      against **0** to the WAN one, and **0** cleartext. The whole cu-11 chain is now proven end to
      end: the server reports `local: true`, [[cu-107]]'s persistence keeps the flag, the chooser
      builds a LAN tier, and that tier wins.

      **Session 2's blocker was misdiagnosed, and the correction matters.** It recorded
      "`plex.direct` does not resolve on this network". The real fault is narrower: the router at
      `192.168.1.1` (TIM/homenet) does **two** separate wrong things — it returns an *empty answer*
      for the correct `...plex.direct` name, and its DHCP **search domain**
      `homenet.telecomitalia.it` gets appended by the device's resolver, producing
      `...plex.direct.homenet.telecomitalia.it`, which the router answers with a wildcard
      **`127.0.0.1`**. That `127.0.0.1` is where session 2's "resolves to loopback" came from — the
      search-domain append, not the `plex.direct` lookup. Cloudflare, Google and Quad9 all return
      the correct `192.168.1.54`.

      So this was **fixable on the device, not a dead end**: setting Private DNS (DNS-over-TLS) to
      `one.one.one.one` bypasses the router's resolver entirely, after which the device resolves the
      LAN name to `192.168.1.54` at ~5 ms. Worth keeping as the standing workaround for this
      network, and worth knowing generally — a resolver that filters rebind-style private answers
      breaks Plex LAN addressing for every client, not just this app.

- [~] **Network switch mid-playback recovers in under 5 seconds.** Wi-Fi to cellular and
      back while a book plays. The arithmetic supports it (1.5s tier budget + 5s connect
      timeout) but elapsed time cannot be measured in a unit test.
      **Measured 2026-09-02, session 3, for a Wi-Fi drop-and-restore** (this tablet has no SIM —
      `mDataRegState = OUT_OF_SERVICE` — so the cellular half is still untested):

      | event | time |
      |---|---|
      | Wi-Fi disabled | 11:04:05.6 |
      | Wi-Fi re-enabled | 11:04:11.9 |
      | app re-chose LAN | **11:04:16.5** |
      | Android marked network validated | 11:04:19.0 |

      **~4.6 s** from the link returning to a working connection — inside the 5 s target, and
      notably *before* the platform considered the network validated, so the app is not waiting on
      `onAvailable` validation to retry.

      Crucially **playback never stopped**: `state=3` throughout, position advanced normally, and
      **zero** `ExoPlaybackException` / source errors. The stream survived a 6 s outage, which is
      ExoPlayer's buffer doing its job.

      Left partial rather than done for two honest gaps: no **cellular** transport (no SIM), and
      the tablet has a configured hotspot from the owner's phone so a true *Wi-Fi-to-Wi-Fi
      handover* is possible but was not run — a same-SSID reconnect is an easier case than
      switching to a different network with a different route to the server.
- [x] **The real `/resources` response shape.**
      **Verified 2026-09-02.** `relay` is present and spelled as the model expects (`"relay":false`
      on both connections; this server exposes no relay route). `IPv6` **is** present as a boolean
      and is `false` on every connection — so [[draft-75]] has its answer: the flag exists and is
      parseable, but nothing on this server needs it. Full captured payload is quoted in [[cu-107]]. Capture it and confirm `relay` is present
      and spelled as the model expects, and note whether `IPv6` connections appear at all —
      that decides whether [[cu-75]] is worth opening.
- [x] **End-to-end connection selection via `FakePlexServer`.** The chooser's tests inject
      the probe, so the real `checkServer` wiring in `PlexConfig` is covered only by the
      Dagger graph resolving. Same harness cu-10 needs.
      **Done 2026-09-02: `ConnectionProbeWiringTest`, 9 tests.** Drives the *production* probe
      lambda — `plexMediaService.checkServer(uri).isSuccessful` — over real Retrofit + Moshi
      against `FakePlexServer`, so the `{url}/identity` call, the encoded path parameter, the
      converter and the `isSuccessful` reading are all exercised.

      **Sabotage proves the gap was real:** a one-character typo in the endpoint
      (`/identity` -> `/idendity`) — which would leave the app unable to connect to *any* server —
      fails **6 of 9** new tests while **all 9** existing `ConnectionChooserTest` cases stay green.

      Covered: a live probe succeeding, that it really requests `/identity`, 401 and 500 both
      reading as unreachable, an empty `{}` body still counting as reachable, a dead LAN address
      falling through to WAN, relay chosen when it is all there is, and nothing-reachable
      returning null.

      One honest limit recorded in the test: with *two* reachable addresses the winner is decided
      by which `Deferred` settles first in `awaitFirstSuccess`'s `select`, and two real suspending
      HTTP calls on an unconfined test dispatcher settle nondeterministically. Tier *preference*
      with instant probes stays `ConnectionChooserTest`'s job; what is new here is that the
      decision runs on real HTTP.
### Data and parsing

- [x] **cu-62 — Moshi codegen.** Reflection is lenient about absent/null fields
      in ways generated adapters are not. The fixtures cover the fields they cover; a
      real library exercises the rest (missing narrators, odd chapter data, unusual
      collections). If codegen is adopted, this pass is where its risk is actually
      retired.
      **Done 2026-09-02: `RealLibraryShapeTest`, 8 tests, against fixtures captured from the real
      196-book library** (scrubbed of identifying values, key-sets preserved). [[cu-62]] is Done
      and codegen *is* live (`ksp(libs.moshi.codegen)`, no `KotlinJsonAdapterFactory`), so the
      condition is met.

      cu-62 noted the leniency differences "did not materialise on fixture data" — true, and the
      weak part, since hand-written fixtures contain the fields their author thought of. A survey
      of all 196 real albums found **ten fields absent on at least one book**:

      | field | absent on |
      |---|---|
      | `skipCount` | 190/196 |
      | `Collection` | 170/196 |
      | `viewCount` | 135/196 |
      | `lastViewedAt` | 133/196 |
      | `titleSort` | 57/196 |
      | `rating`, `studio` | 30/196 |
      | `parentThumb` | 16/196 |
      | `year`, `originallyAvailableAt` | **1/196** |

      The 1-of-196 cases are the point: a library where 195 books have `year` and one does not is
      exactly the shape that passes every hand-written fixture and then throws on a real sync.

      **The most valuable property turned out to be a different one than the item assumed.** The
      real risk is not absent fields (the models carry defaults) but **unknown** ones: a real album
      object sends `Image`, `UltraBlurColors`, `loudnessAnalysisVersion` and `originallyAvailableAt`,
      none of which any model mentions. If codegen rejected unknown keys, every fetch would fail.
      Sabotage: an explicit JSON `null` on a non-null field fails **7 of 8**; stripping the
      unmodelled key fails exactly the test asserting it.

      **A correction worth recording.** `PlexMediaSource` declares `hasNarrator = true` and
      `hasSeries = true`, but **nothing parses `Style` or `Mood`** — `Audiobook` has no narrator or
      series field. Those are D11 scaffolding ("declared but not yet load-bearing", CLAUDE.md). The
      live server does send them, and only on the **detail** endpoint — `Style: ['Toby Longworth']`,
      `Mood: ['Series: Eisenhorn']`, absent from all 196 list entries. I nearly wrote tests
      asserting a feature that does not exist; the fixture keeps the real shape so it is ready when
      someone implements it.
- [x] **Plex metadata conventions.**
      **Verified 2026-09-01.** Confirmed: narrator/series render, and the library populated correctly from a real 500+ book library. Narrator via `Style` tags and series via `Mood` tags
      are a community convention, not a guarantee. Confirm a real library populates them
      as expected — several R2/R3 features depend on it.

### Performance (cu-110)

The mechanism is fixed and unit-proven; these are the *numbers*, which need a device. Reproduce
with the player sheet open over Home and a 100+ chapter book playing — the state that measured 88%
janky frames and a 4950 ms 90th-percentile frame.

- [ ] **Back and the nav bar respond reliably** in that state. This is the symptom the owner
      originally reported ("the back button and the nav button sometimes don't work when the
      playback screen is open"), and it is the one that actually matters.
- [ ] **Janky frames well under 88%**, from `dumpsys gfxinfo`, with the figure recorded here.
      Record the allocation rate and GC frequency too, so the before/after is comparable —
      the baseline was a GC every ~4 s freeing ~165,000 objects.
- [x] **`uiautomator dump` succeeds** while the player is open.
      **Verified 2026-09-02** on a Phh-Treble GSI (API 32) — succeeded on every attempt, including
      after a BACK press. Already ticked in [[cu-110]]; this copy was stale. It currently fails with
      `ERROR: could not get idle state`, which is the same saturation seen from outside; a pass
      also unblocks automated UI checks for the rest of this task.
- [ ] **The library's progress bars actually move.** A latent bug fixed alongside cu-110: the
      id-only short-circuit in `LibraryViewModel` returned a stale list, so a book's bar never
      updated. Listen for a few minutes, return to Library, confirm the bar advanced.
- [ ] **A large library still feels right.** The `MediaItemTrack` index (v5→v6) is the first index
      in the schema; confirm the migration runs cleanly on the real database and that library load
      and playback start are no worse. Relevant to [[cu-51]].

### Playback

- [~] **cu-64 — seek over a real range request.** — **server side proven, ExoPlayer side still
      open.** The real server unambiguously honours ranges on these part URLs (2026-09-02, over the
      LAN route, against the 293 MB *Malleus* m4b):

      - `bytes=1000000-1000999` -> `206 Partial Content`, `Content-Range: bytes
        1000000-1000999/293768919`, exactly 1000 bytes
      - deep mid-file `bytes=250000000-250009999` -> `206`, correct range (the seek case)
      - **open-ended `bytes=293700000-`** -> `206`, returning the 68,919-byte tail — this is the
        shape a *download resume* sends, so it answers the resume item's server half too
      - `Accept-Ranges: bytes` advertised

      `ffprobe` also read the moov atom over HTTPS without fetching the whole file, which only works
      via ranged requests. What is **not** yet proven is ExoPlayer issuing them: its HTTP traffic
      does not go through the app's OkHttp logger, so the client half needs either a proxy capture
      or a debug `EventLogger`. Recorded as partial rather than done.

- [x] **cu-9 — progress reporting round-trip.**
      **Verified 2026-09-01, and independently re-verified 2026-09-02 (session 3) with the fixes in
      place.** Confirmed, and it found two data-loss bugs on 09-01: `time=0` written on playback
      start (fixed), and `/:/scrobble` firing every tick, inflating `viewCount` to 183 and clearing
      `viewOffset` (fixed). Syncing a position written by the old app into the new one works.

      Session 3, live playback over LAN, ~4.5 minutes of a real book:

      - every `/:/timeline` carried a **real position** (`time=9441`, `19478`, `29513`, `39810`,
        `49902` … advancing ~10 s per report) — **no `time=0`**, so the 09-01 regression does not
        reproduce
      - `viewCount` stayed at **249 before, during and after** playback and across a pause: **zero
        scrobbles fired**. That is the one-shot `viewCount == 0L` guard holding against a real
        server.
      - `200 OK` on every report, all over the LAN address
      - the position **reached and persisted on the server**: `viewOffset: 269636` after a pause
      - and **survived a force-quit**: after a cold start the app restored `progress = 277670`,
        exactly its last in-app value, surfacing the book in "Recently listened"

      Note the restored local value (277670) is *ahead* of the server's (269636), i.e. the app kept
      its own newer position rather than being dragged backwards by a staler server value — the
      decision-16 property, observed rather than reasoned.

      **One thing worth recording so it is not re-reported as a bug:** the app deliberately sends
      `duration = track.duration * 2` (73,611,808 for a 36,805,904 ms book). That is an intentional
      documented workaround — Plex auto-finishes an item at 90% of whatever duration it is told, so
      the real value would mark a book complete at ~45%. I flagged it as a suspected bug mid-session
      and was **wrong**; `ProgressReporter.kt` explains it and warns against removing it. What this
      pass adds is the confirmation the workaround is *safe*: the server still stores the **real**
      duration (`36805904`), so the doubling affects only the finish calculation and does not
      corrupt stored metadata.

- [x] **cu-9 — the "position not synced" badge on screen.** Its state is unit-tested and
      the terminal-failure path is confirmed in logcat, but the badge has never been
      *seen*: the currently-playing sheet did not lay out during the emulator run. Pull
      the player up on a real phone with the server unreachable and confirm it appears —
      and that it does **not** appear during ordinary playback, which is the failure mode
      that would make it worthless.
      **Verified 2026-09-02, session 3 — and automated, so it is repeatable rather than a
      one-off.** Both halves, screenshotted on the tablet against the **live** server:

      - `--ez fail_sync true` -> **"Position not synced" visible in red** on the expanded player
      - `--ez fail_sync false`, otherwise identical -> **badge absent**, reports succeeding
        (`Worker result SUCCESS`)

      The second is the one that gives the first its meaning: a badge stuck on would have looked
      correct in the first screenshot alone.

      **Two debug-hook gaps had to be closed first, and both were real defects in the harness:**

      1. `--ez fail_sync true` only set a flag on [MockPlexMode]'s fixture server, which is null
         unless mock mode is running — so **against a live server it was a silent no-op**. Now
         `DebugHooks.wrapProgressApi` substitutes a failing `ProgressApi`, and the flag is
         *persisted*, because the report runs in a WorkManager process that may never have seen
         the intent.
      2. `--el play_book` drives playback through the **media session**, which never navigates the
         UI, so the player sheet never laid out — the exact reason this item had gone unverified
         since cu-9. Added `--ez show_player true`, which waits for playback and expands the sheet.

      Which failure to inject is not arbitrary: a **4xx** is needed. An `IOException` or a 5xx maps
      to `RETRY` -> `Result.retry()` -> ENQUEUED, which `hasFailedSync` deliberately ignores, so
      the intuitive "simulate no network" injection would leave the badge hidden and look like the
      badge was broken. `FailSyncInjectionTest` pins all three outcomes.

      One command now reproduces the whole thing on any device, with no tap coordinates:

      ```
      adb shell am start -n io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.application.MainActivity \
        --ez fail_sync true --ez show_player true --el play_book <id>
      ```

      The screenshot also incidentally re-confirmed [[cu-87]]: chapter highlight on Chapter 1 at
      26:11, position 06:40, chapter list matching the real file exactly.
### Authentication

- [ ] **cu-10 — a rotated server token recovers silently.** Rotate the server's token
      (reset `PlexOnlineToken`, or re-claim the server) while the app holds a stale one,
      then play something. The 401 should be invisible: refreshed and retried, no message.
- [!] **cu-10 — an invalidated account token degrades honestly.** — **RUN 2026-09-02, session 4.
      Four of five criteria pass; the message does not appear. Filed as [[DRAFT-123]].**

      Password changed with "sign out connected devices", both devices watched from a cleared log.
      `GET https://plex.tv/api/v2/resources` → **`401 Unauthorized`** (750 ms), so the token is
      genuinely dead.

      | criterion | result |
      |---|---|
      | sign-in-expired message | **✗ never shown** |
      | no login wall | ✓ |
      | library not empty (196 books from Room) | ✓ |
      | downloaded books still play | ✓ `file:///…155607.m4b`, `state=3`, position 2h58m |
      | no repeated 401 storm | ✓ **exactly one** 401 (tablet); 3 on the phone across retries |

      The app also surfaced an **"AVAILABLE OFFLINE"** section listing the downloaded book, and
      fell back to placeholder art — it degrades gracefully, just **silently**.

      Cause: `AccountAuthState.onAccountRejected()` reached **0 times**. It is only ever called
      from `PlexTokenAuthenticator`, which is attached to the **media client only** (deliberately —
      re-fetching resources with a dead account token cannot help). So a 401 on the *login* client
      has no path to recording the state, and `ChronicleApplication.setupNetwork`'s blanket
      `catch (e: Exception)` logs it as "Could not refresh server resources; keeping cached
      server" — treating 401 exactly like a timeout. Detection and recovery were conflated.

      Original text: Change the Plex password
      with "sign out connected devices", then play. Expect the sign-in-expired message,
      **no login wall**, downloaded books still playing, and — importantly — *no repeated
      401 storm* against plex.tv. The retry-once guard is unit-tested but never seen against
      a real server.
- [x] **cu-10 — end-to-end 401 handling via `FakePlexServer.stubUnauthorized`.** The
      authenticator's tests exercise the decision, not the wiring; an integration test
      through MockWebServer would confirm the `AppModule` hookup actually fires.
      **Done 2026-09-02: `ReauthWiringTest`, 7 tests.** A real `OkHttpClient` with
      `PlexTokenAuthenticator` attached, against a real 401 from `FakePlexServer`.

      **The gap was bigger than the item suggests.** `PlexTokenAuthenticator`'s own KDoc leans on
      the framework for its central property — "OkHttp invokes it only on a 401 and threads the
      previous attempt through `Response.priorResponse`, which is what makes 'retry exactly once' a
      property of the framework rather than hand-rolled state" — but all 12 existing tests call
      `authenticate(...)` directly with a hand-built `priorResponse`, i.e. they assert against
      their own fixture.

      Measured: with the **retry-once guard deleted from production code**, all **12** direct tests
      still pass while the new wiring tests fail. Detaching the authenticator entirely also fails 4
      of 7. Neither failure mode was previously detectable.

      Covered: OkHttp actually invoking the authenticator, the refreshed token being persisted and
      used on the retry, exactly-one refresh on a permanent 401 (no loop against plex.tv), 200 and
      500 never reaching it, a refresh that yields nothing leaving the cached token alone, and an
      unchanged token recording a signed-out account.
### Downloads (cu-12 / cu-76)

- [x] **A large book (ideally ~2GB m4b) downloads to completion**, and memory is watched
      while it does. **DONE 2026-09-02, session 3, on a 1.64 GB book** — close enough to the ~2 GB
      the item asks for to answer it.

      This item found [[cu-109]] first (`OutOfMemoryError` on a *293 MB* file at ~50 s, zero bytes
      on disk, PSS 248 -> 350 MB, dead). After the fix, `This Inevitable Ruin`
      (**1,639,241,764 bytes**) downloaded to completion with memory **flat**:

      | written | PSS |
      |---|---|
      | 661 MB | 144 MB |
      | 730 MB | 144 MB |
      | 799 MB | 141 MB |
      | 852 MB | 142 MB |
      | 1.64 GB (complete) | **135 MB** |

      Flat memory against a climbing byte count *is* the streaming proof — under the old code PSS
      tracked the transfer. Final PSS is **lower** than the 248 MB baseline before any download
      started. Zero `OutOfMemory` entries, and the file is **byte-identical** to the server's copy
      (md5 `307a354f5f96e445aebd99d92a203e22` both sides).

      So **[[cu-12]]'s open question is settled**: #83 is real, and it was in neither app logic nor
      Fetch2 but in the *client Fetch2 was handed*.

      **A measurement trap worth recording:** `stat` reports the file at full size within a second
      of starting, because Fetch2 preallocates. File size is **not** a progress indicator — read
      `_written_bytes` from `databases/LibGlobalFetchLib.db` instead. I briefly misread a
      preallocated file as an instant download.
- [x] **Wi-Fi drop mid-download, then reconnect.** Expect resume, not a silent stop. Then
      check the book is *not* marked available offline while incomplete — that promotion of
      partial files is [[cu-76]]'s first defect.
      **The partial-promotion half is verified 2026-09-02, session 3.** With the realistic state a
      Wi-Fi drop leaves — book `isCached=1`, track `cached=1`, file 40 MB of an expected 293 MB —
      a cold start reconciles both flags down correctly:

      ```
      Ignoring incomplete download for track 151313: 41943040 of 293768919 bytes
      Removed track: 151313
      Book: 151312
      ```

      DB after: track `cached=0` **and** book `isCached=0`. So **[[cu-76]]'s first defect is fixed
      against real hardware** — before it, this book would have shown as downloaded and played
      truncated.

      **A false alarm worth recording**, because it looks like a bug and is not. Deleting the file
      with `adb rm` behind Fetch2's back produces a genuinely inconsistent state: Fetch2's DB still
      holds the previous *successful* record, so `enqueueAction=UPDATE_ACCORDINGLY` short-circuits
      and reports `downloaded=293768919, status=COMPLETED` **without making a single HTTP request**
      (0 GETs, 0 206s, complete 93 ms after enqueue), while the file is 0 bytes. The track flag was
      already 0 from an earlier scan, so `toMarkUncached` was empty, `alteredTracks` was empty, and
      the **book**'s stale `isCached=1` was never revisited — reachable only because I had already
      desynchronised the two. Through the app's own paths the flags always move together, as the
      run above shows. Do not "fix" this without first reproducing it without `adb rm`.

      Still open: the **resume** half — that an interrupted transfer continues rather than
      restarting. The server honours Range (see the cu-64 item), so what remains is watching
      Fetch2's byte count across a real interruption. The LAN route completes a 293 MB file in
      under 10 s, which is too fast to interrupt by hand — needs a throttled link or a much
      larger book.
- [x] **Kill the app mid-download and relaunch.** Same expectation.
      **Verified 2026-09-02, session 3 — and it resumed exactly, not approximately.**
      `am force-stop` at **966,369,280** of 1,639,241,764 bytes (~59%); Fetch2 persisted that count
      across the kill. On relaunch, from the log:

      ```
      Range: bytes=966369280-
      <-- 206 Partial Content
      Content-Range: bytes 966369280-1639241763/1639241764
      ```

      It resumed from the **exact** byte it stopped at, with `_auto_retry_attempts = 0` (a clean
      continuation, not a retry), and ran to `_status = 4` (COMPLETED) with the file
      **byte-identical** to the server's copy.

      Note this evidence exists *because* [[cu-109]] kept download logging at `HEADERS` rather than
      dropping it to `NONE` — the `Range`/`Content-Range` pair is exactly the diagnostic the task
      argued for, and it is what distinguishes a resume from a restart.
- [x] **A downloaded book plays with the server unreachable.** Should already work (cached
      tracks resolve to a local path), but confirm the UI does not block on a connection check.
      **Verified 2026-09-02, session 3**, immediately after [[cu-109]] unblocked downloading.
      Aeroplane mode on, app force-stopped, cold-started: playback reached `state=3` from
      `file:///storage/emulated/0/Android/data/.../151313.m4b` — **scheme intact, so [[cu-83]]'s fix
      is confirmed on a real download** — with `AudioFlinger` actively mixing and no
      `ExoPlaybackException`. The UI did not block on a connection check; the book was already
      `cached=true` / `isCached=true` from `CachedFileManager: COMPLETED`.
- [x] **Which route a download takes** once `OkHttpDownloader` is wired ([[cu-76]] item 3):
      downloads should get the same tier as playback, not a relay while playback uses LAN.
      **Verified 2026-09-02, session 3** — captured before the [[cu-109]] OOM killed the transfer.
      The enqueued download URL was
      `https://192-168-1-54.<hash>.plex.direct:32400/library/parts/290802/1752356877/file.m4b?download=1`
      — the **LAN address, the same tier playback chose**, with the `X-Plex-Token` header attached,
      and the server answered `206 Partial Content` in 74 ms. So cu-76's gain is real: downloads do
      inherit the chosen connection rather than picking their own route.

- [x] **Range resume genuinely continues rather than restarting.** Watch the transferred byte
      count across an interruption: Fetch2 claims HTTP-Range resume, but nothing in the repo
      proves the server honours it for these URLs. A restart-from-zero on a 2GB book is a very
      different user experience from a resume, and both look like "it downloaded eventually".
      **Verified 2026-09-02, session 3 — both halves, on a 1.64 GB book.**

      *Server half:* `206` on a plain range, a deep mid-file range, and the **open-ended
      `bytes=N-`** form a resume actually sends.

      *Client half:* the byte count is the evidence the item asked for. Killed at
      **966,369,280** bytes; on relaunch Fetch2 sent `Range: bytes=966369280-` and the server
      answered `Content-Range: bytes 966369280-1639241763/1639241764`. It transferred the
      remaining ~673 MB only, reached `_status = 4`, and the result is **byte-identical** to the
      server's copy (md5 `307a354f5f96e445aebd99d92a203e22`).

      So it is a genuine resume, not a restart that merely ended up correct — the distinction the
      item was written to catch.
- [ ] **A download stranded at `FAILED` before the fix is retried on next launch.**
      `ResumePlan.idsToRetry` is unit-tested against mocked state; what is unverified is that a
      real exhausted-retry download actually presents as `FAILED` (rather than `CANCELLED` or
      `REMOVED`) — the whole resume path hinges on that status being the one Fetch2 reports.
      — **[[cu-109]] is fixed, so this is unblocked and simply not yet run.**
- [ ] **A rotated server token mid-download recovers.** cu-10's re-auth now sits in the download
      path via `OkHttpDownloader`; that combination has never run. Rotate the token during a
      download and expect a retry that succeeds, not a failed book.

      — **[[cu-109]] is fixed, so this is unblocked and simply not yet run.**
### Chapters and artwork (cu-13)

- [x] **Do the chapters shown match the m4b's embedded chapters?**
      **Verified 2026-09-02, session 3 — exact match.** Compared Plex's `retrieveChapterInfo`
      output for *Malleus* (track `151313`, a single 293 MB m4b) against the file's own embedded
      chapters read with `ffprobe -show_chapters` **straight off the server over the LAN route**:

      - chapter count: **25 vs 25**
      - titles: **all 25 byte-identical** (including the odd first one, `CASE FILE 442:41F:JL3:Kbu`)
      - worst start-offset difference: **49 ms**, which is Plex storing integer milliseconds against
        the file's fractional seconds — rounding, not drift

      Chapters are also contiguous (each `end` equals the next `start`) and the final `end`
      (36,806,000 ms) matches the track duration (36,805,904 ms) to within 96 ms. So Plex's parse is
      faithful *and* the app's read of it is. Offsets are absolute within the file, first chapter
      starting at `0`, consistent with the cu-13/cu-49 gotcha.

      Still to do on a **multi-file** book (this library has books of 240, 159 and 113 parts, where
      the absolute-vs-per-track offset trap actually bites) and on a book with **no** embedded
      chapters.

- [x] **A book with no embedded chapters falls back to one chapter per file.** Fixed in cu-13
      (`asChapterList` built its chapters and discarded them, so such books showed none at
      all); unit-tested, but never seen against a real library.
      **Verified 2026-09-02, session 3 — and this library is full of the case.** An API sweep found
      the split cleanly: **every multi-track book reports 0 embedded chapters per track** (they are
      already one file per chapter), while single-m4b books carry real chapter data. So the fallback
      is not an edge case here, it is how most of the library renders.

      Tested on `Ender's Game` (book 151444, **107 tracks**). The detail screen lists
      "Ender's Game - Chapter 96", 97, 98 … each with its own duration, grouped by disc. In
      `chapter_db`:

      - **107 chapters** for 107 tracks — exactly one per file
      - offsets **absolute and contiguous**: `0 -> 407275 -> 823536 -> 1129821 -> …`, each `end`
        equal to the next `start`
      - `MAX(endTimeOffset)` = **40,307,356** = the book's stored `duration`, exactly
      - **exactly one** chapter with `startTimeOffset = 0`

      Those last two are the [[cu-13]]/[[cu-49]] per-track-`0L` trap checked directly: were it
      present, every chapter would start at 0 and the chapters would not tile the book. They do.
- [x] **Which artwork the player shows for each track** (#119).
      **Verified 2026-09-02, session 3 — the bug cannot manifest on this library.** Swept **all
      1376 tracks across all 196 books** via `?type=10`, including books of 240, 159, 113 and 107
      separate files:

      - tracks whose `thumb` differs from their `parentThumb`: **0 of 1376**
      - books whose tracks carry differing thumbs from each other: **0 of 196**

      Plex reports the album's art as every track's `thumb`, exactly as the cu-16 fixture models it.
      So modelling `parentThumb` and preferring it is **not needed** for this library, and the
      lockscreen/Auto cannot show chapter art instead of the cover here. A stronger answer than the
      item asked for (it wanted a spot check), and it retires #119 for this server rather than
      deferring it. Keep the caveat that a *differently tagged* library could still differ.

- [~] **Chapter highlight tracks playback** across a track boundary, and jumping to a chapter
      in a later file seeks to the right place.

      **Highlight half verified 2026-09-02, session 4** (mock): crossing track 2 → 3 moved the cyan
      highlight to entry `03` unprompted. **Jump-to-chapter is not verified** — and it cannot be on
      the mock as currently routed, see the fixture-routing note in session 4 below: every track
      receives the same 3 chapters, so there is no distinct later-file chapter to jump *to*.

### Multi-track books (cu-115)

The unit half is covered by the `MultiTrackBook` fixture. **Correction (session 4): the mock
*does* serve a multi-track book** — The Hobbit, `1001`, three 180 s tracks (`2001`–`2003`) — so the
position-arithmetic items are checkable without credentials. What the mock cannot do is give each
track *distinct chapters* (see session 4's fixture-routing note), so the items needing a real
per-track chapter layout still want a live server. They also close cu-93's and cu-96's remaining
criteria.

**Second correction, same session: prefer the real library for what is left.** ANTARES has
multi-track books an order of magnitude bigger than the mock's — `151444` Ender's Game (107
tracks), `150974` Stone Blind (77), `151180` Forward the Foundation (113), up to `150697` (240) —
so the two remaining items below need neither mock mode nor a `pm clear`. Use them: an unordered
`getTrackStartTime` sum is much likelier to show across 107 tracks than across 3.

- [~] **Chapter title tracks playback across a track boundary.** — **Full player verified
      2026-09-02, session 4, on the mock.** Playback crossed track 2 → track 3 automatically and
      both the title ("A Short Rest") and the highlighted list entry (`03`, cyan) followed it;
      blank on any track but the first before cu-115. **The mini player half is unverifiable until
      [[DRAFT-119]] is fixed** — it is hidden on `STATE_STOPPED` and never returns, so there is no
      mini player on screen to read a chapter name from. Play a multi-track book into its
      second file and confirm the mini player and the full player both name the right chapter —
      this was blank on any track but the first before cu-115.
- [ ] **The chapter slider seeks where it points.** Drag it mid-chapter on a multi-track book. It
      used to send a book-absolute offset to a track-relative API, so the thumb jumped to the end
      of the current track.
- [x] **Chapter elapsed time is not negative or nonsense** on a later track.
      **Verified 2026-09-02, session 4.** On track 3 the track slider read `00:10 / 03:00` and the
      book readout `06:11/09:00 69%` — both positive and arithmetically right (180+180+10 = 370 s;
      371/540 = 68.7%). The 1 s discrepancy is the sampling gap between the two reads, not drift.
- [~] **Previous-chapter honours the threshold** on a later track: just after a chapter start it
      goes to the previous chapter, well into one it restarts the current chapter.

      **Half verified 2026-09-02, session 4.** The *restart* half is confirmed: paused 31.8 s into
      track 3 (well past the chapter start), previous-chapter logged
      `PlayerExtKt: skipToPrevious → back to start of current chapter` — the correct branch, named
      explicitly. The *go-back* half (pressing just after a chapter start) is **not** done: the app
      backgrounded to the launcher right after the seek. Re-run it with the position set a second
      or two into a chapter.
- [ ] **Book position survives a full sync** on a multi-track book. `getTrackStartTime` summed an
      unordered list, so the whole-library re-derive could report the position a whole track ahead
      of where it was; confirm the position is unchanged after a refresh.

### Sync drift (cu-14)

- [!] **A second device's position is adopted.** — **FAILS. Filed as [[DRAFT-121]]
      (2026-09-02, session 4, two real devices against ANTARES).**

      Tablet (API 32) played *Ender's Game* `151444` to **244973 ms** and reported it
      (`200 OK`). Phone (API 36) opened the book's details screen, fetched
      `/library/metadata/151444/children` (`200 OK`), and the response carried the right track
      with `"viewOffset":236955,"lastViewedAt":1788384988`. **The phone's stored position stayed
      at 189 ms.** It was handed the correct value and did not apply it.

      Ruled out by measurement: the write path (server echoed the offset back), LAN-vs-WAN
      routing (same failure on both tiers — see the DNS note below), and a seconds/ms units bug
      in `plexTimestampToMillis` (the conversion is correct, so `merge`'s comparison *should*
      favour the network copy). The hard clue is that `"Integrating network track"` never logs,
      so `merge` either is not called on this path or is losing with values that disagree with
      the JSON. DRAFT-121 has the full trail and points at
      `TrackRepository.loadTracksForAudiobook(…, forceUseNetwork = false)`.

      **Method trap worth heeding:** `--el play_book <id>` does **not** exercise this — it never
      opens the details screen, so no `/children` request happens at all. Tap the book in the UI.
      Two runs were wasted before noticing.

      Listen on device A, stop, then open the book
      on device B and refresh. B should jump to A's position. This is the round trip the
      timestamp fix enables and that no mock can prove — the fixture server accepts timeline
      writes without modelling server-side state.
- [x] **`lastViewedAt` really is seconds on a live server.**
      **Verified 2026-09-02, session 3.** Captured every album-level `lastViewedAt` in the real
      library: **63 values, all exactly 10 digits**, min `1685037794`, max `1788298546`. Read as
      seconds those are 2023-05-25 to 2026-09-01 (the latter being the day before this check, which
      is the sanity test that matters); read as millis they would all be *January 1970*. So the
      magnitude is unambiguous.

      `plexTimestampToMillis`'s threshold is `100_000_000_000` (10^11). The real values are ~1.8x10^9
      — two orders of magnitude below it — so they take the `* 1000` branch correctly, with roughly
      55 years of headroom before a seconds value could ever reach the threshold. The heuristic is
      not merely untripped, it is nowhere near tripping.

      Confirmed on the *local* side too: after a cold start the app held
      `lastViewedAt = 1788336422878` (13 digits, millis) for a book whose server value was 10-digit
      seconds — the conversion working end to end, not just in a unit test.

- [ ] **Local progress is not clobbered by a stale server value.** The converse of the above:
      listen on this device with no other client active, refresh, and confirm the position
      does not jump backwards.

### Owner-reported issues fixed blind (cu-83 to cu-90)

Eight functional issues the owner reported from real use on 2026-08-31. Every one was traced to a
mechanism in the code and fixed, but **every fix is unit-tested only** — none has run against a real
library, a real upgrade, or a second device. These are the checks that turn "the mechanism is right"
into "the symptom is gone".

- [x] **A downloaded book plays after a force-quit and relaunch, offline** ([[cu-83]]). The cached
      track URI now carries a `file://` scheme; the symptom was an unsupported-format error on
      downloaded books only. Try a book whose sync directory path contains a **space or a non-ASCII
      character**, since that is what the percent-encoding half of the fix is for.
      **Verified 2026-09-02, session 3 — including the awkward-path case the item asks for.** The
      1.64 GB download was moved to
      `.../files/Chronicle Downloads àéî/` (spaces *and* accents), the sync location repointed
      there, aeroplane mode enabled, the app force-stopped, then cold-started. It played:

      ```
      file:///storage/79AF-CD2E/Android/data/.../files/Chronicle%20Downloads%20%C3%A0%C3%A9%C3%AE/155607.m4b
      ```

      Spaces as `%20` and `àéî` as correct UTF-8 escapes (`%C3%A0%C3%A9%C3%AE`) — which is what
      `Uri.fromFile` produces and precisely what the old `"file://" + path` concatenation got wrong.
      `state=3`, **zero** `ExoPlaybackException` / `ERROR_CODE_IO`, `AudioFlinger` actively mixing,
      and the progress loop advancing (26669 -> 27673 ms). Off an SD card, with no network at all.

      So both halves of cu-83 are confirmed on real hardware: the scheme *and* the encoding.
- [x] **Cached status survives repeated relaunches** ([[cu-85]]), and — the case that matters — with
      downloads on an **SD card, eject it**: the books must read as unavailable, *not* as
      never-downloaded, and must come back when it is reinserted.
      **Verified 2026-09-02, session 3, on a real 59 GB SD card.** Earlier sessions assumed this
      needed absent hardware; the tablet has one mounted (`public:179,129`, `79AF-CD2E`), and `sm
      unmount` / `sm mount` drive a genuine eject without touching it.

      Setup was made real rather than simulated: sync location repointed to the card, the 1.64 GB
      download moved there, and the internal copy deleted, so the card held the *only* copy.

      | state | track `cached` | book `isCached` |
      |---|---|---|
      | card present (baseline) | 1 | 1 |
      | **card unmounted, path gone** | **1** | **1** |
      | card remounted | 1 | 1 |
      | + 3 further relaunches | 1, 1, 1 | 1, 1, 1 |

      With the volume genuinely absent (`ls /storage/79AF-CD2E` -> `No such file or directory`) the
      flags **stayed at 1** — "downloaded but unavailable", which is the correct and recoverable
      answer — and the 1.64 GB file was intact on reinsertion. Before cu-85, `cachedMediaDir` fell
      back to a different *readable* directory, the scan found none of the expected files there,
      and it un-cached the whole library.

      Repeated-relaunch stability confirmed separately: three cold starts, no drift.

      **Still not covered:** `MoveSyncLocationWorker` leaving a stale path in prefs after moving
      storage. I set the pref directly rather than driving the settings UI, so the worker itself
      never ran — that interaction remains unaudited, as the original item noted.
- [ ] **Two devices converge on one position** ([[cu-90]]). Listen on A into a *later* track, then
      open the book on B which last touched an *earlier* track. B must not drag the position
      backwards. Then reload book info repeatedly on one device: the position must not move.
- [ ] **A deliberate seek backwards survives a sync** ([[cu-90]]). Seek back a chapter, wait for a
      refresh, confirm it is not pulled forward again. This is the edge decision-16 flags as sharpest.
- [x] **Mark as read, then unread, is a clean round trip**
      **Verified 2026-09-01.** Confirmed working in the UI. ([[cu-86]]) — including that a sync
      afterwards does not revert it, which is where local and server semantics could still disagree.
      Check the library list shows finished / in-progress / not-started correctly for each.
- [x] **Chapter highlight matches the timeline on a cold start**
      **Verified 2026-09-01.** Confirmed after fixing the absolute-offset walk in both view models. ([[cu-87]]). Open a part-listened
      book after force-quitting the app, *without* pressing play. Also check skip-to-next-chapter and
      skip-to-previous-chapter land correctly before any playback, since `PlayerExt` reads the same
      value.
- [x] **Skip silence is listenable** ([[cu-88]]).
      **Verified by the owner 2026-09-02, session 4 — the constants stand, no revision needed.**
      Tested against **Malleus** (151312) and **Xenos** (151314), Dan Abnett / narrated by Toby
      Longworth: quiet, measured Warhammer narration, and — the reason this is strong evidence —
      **the exact books where the owner had trouble with skip silence before**. Verdict: "they
      seem to sound good now." ~58 min of listening on Malleus, so a sustained judgement rather
      than a spot check. `key_skip_silence = true` confirmed in prefs for the session.

      So `MINIMUM_SILENCE_DURATION_US = 800_000` and `SILENCE_RETENTION_RATIO = 0.55f` are now
      **measured-by-ear against the known failure case**, not merely reasoned starting points —
      which is what `AudiobookRenderersFactory`'s KDoc asked this pass to settle. Update that
      KDoc's "expected to be revised after the live pass" wording when convenient.

      Original item text: The retuned values (800 ms minimum, 0.55 retention)
      are reasoned starting points, **not measured** — this check is what sets them. Use a
      quiet-voiced narrator and check chapter boundaries as well as mid-sentence pauses. Expect to
      revise the constants.
- [!] **An expired token is noticed and recoverable** ([[cu-84]]). — **RUN 2026-09-02, session 4.
      "Noticed" FAILS; "recoverable" untested as a consequence. Filed as [[DRAFT-123]].**

      Same run as the cu-10 item above. The app does **not** say the login expired — it shows the
      cached library with grey art and dead sync, leaving the user to infer the cause.

      **Correction, found by looking rather than assuming:** the recovery path *is* built and is
      exactly right. **Settings → ACCOUNT → "Sign in again"**, subtitled *"Refresh your Plex login
      without losing your server, library or downloads"* — precisely this item's requirement. An
      earlier note in this session said the affordance "never appeared"; that was wrong, it is
      simply not on the Home screen where a signed-out user would look.

      So the gap is narrower than first written: **discovery, not capability.** Nothing on the
      degraded Home screen points at Settings, and no message names the problem. DRAFT-123 should
      surface `account_signed_out` with a route to this existing action rather than build anything
      new.

      **The "without re-picking server and library" half FAILS, and re-login could not be
      completed at all.** Taking "Sign in again" through OAuth:

      - `server_name`/`server_id` **survived**, but `library_id`/`library_name` were **cleared** —
        so the user *is* sent back through library selection, contrary to the item's requirement
        and to the button's own subtitle.
      - The picker then showed **"No libraries found"**. Cause is **not** an empty library: it is a
        TLS hostname mismatch — plex.tv now advertises `*.d8f64ea2….plex.direct` while the server
        still presents `CN=*.32080aae….plex.direct`. Both tiers failed,
        `Failure(reason=No connection answered)`. Chronicle is **right** to refuse (cu-42); the
        defect is reporting a connection/TLS failure as an empty library list. Filed as
        [[DRAFT-125]].
      - Backing out of the picker landed on a **working-looking Home** while the app's state was
        `LOGGED_IN_NO_LIBRARY_CHOSEN` with no library in prefs — owner's words: *"super confusing
        for a user"*. Filed as [[DRAFT-124]].

      The stale certificate was a **server-side condition**, **confirmed**: the owner restarted
      Plex Media Server and the cert immediately became `CN=*.d8f64ea2….plex.direct`, with strict
      verification returning `200`. Owner's note — *"didn't know i had to restart it after changing
      password"* — is the point of DRAFT-125: nothing said so, and the app's message pointed away
      from the cause.

      **After the failed re-login, the whole server configuration was gone**, not just the library:
      `Chronicle.xml` retained only `id`, `uuid`, `key_last_refresh`, `key_sync_location` and two
      playback settings. `ConnectionChooser` then reported `No connection answered out of **0**` —
      zero candidates — and the state fell to `NOT_LOGGED_IN`, requiring a full fresh login rather
      than the promised "without re-picking the server and library".

      **What did survive is the important half**: the **1564 MB download intact** and all **196
      books** still in Room. So the data-preservation promise holds even through a failed re-auth;
      it is the *configuration* promise that breaks.

      **Re-login after the server restart: clean, and nothing lost.** Fresh OAuth →
      `Chose LAN connection: https://192-168-1-54.d8f64ea2….plex.direct:32400` (the **new** cert
      accepted) → `LOGGED_IN_FULLY`. Verified afterwards: server **ANTARES**, library **14 /
      Audiobooks**, **196 books**, **1 cached**, **1564 MB** download intact, and the downloaded
      book plays from `file:///…155607.m4b` at `state=3`, position 2h59m.

      So the end state is right; the journey is not. Summary of this item as run:
      **downloads and library data are never at risk** — the failures are all in *notification*
      (DRAFT-123), *configuration retention* (this item), and *error attribution*
      (DRAFT-125/DRAFT-124).

      What *is* confirmed good: no empty library, no login wall, cached book plays
      (`state=3` from the local file), one 401 not a storm, and an "AVAILABLE OFFLINE" section
      appears listing the download. The failure is precisely the notification, not the degradation.

      Original text: Invalidate the token server-side
      (password change with "sign out connected devices"), then confirm: the app says the login
      expired rather than showing an empty library, cached books still play, and "Sign in again"
      restores sync **without** re-picking the server and library or losing downloads.
- [x] **Being offline is not reported as being signed out** ([[cu-84]]).
      **Verified 2026-09-02, session 3.** Aeroplane mode on (0 connected networks confirmed via
      `dumpsys connectivity`), app force-stopped, then cold-started fully offline:

      - `PlexLoginRepo: Login state: hasAccountToken = true`
      - `Navigator: Login event changed to LOGGED_IN_FULLY` — **not** the signed-out state
      - the library still rendered from Room (the in-progress book present in "Recently listened")
      - **zero 401s** in the whole offline launch, so no auth storm against plex.tv when the network
        is merely absent

      The connection failure is reported honestly and *separately* from auth:
      `No connection answered out of 2` -> `Failure(reason=No connection answered)`, with no login
      wall. Both tiers were still tried in declaration order (LAN, then DIRECT) while offline, so
      the tiering structure holds when nothing can answer. This is the failure that would nag every
      user on a train, and it does not happen.

- [~] **Which media session owns the card** ([[cu-89]]). Reproduce on the phone first — see that
      task; Auto may not be needed. Note the session-flag change only affects API 27.

      **Session 4: does not reproduce on the tablet (API 32), and the flag fix is ruled out as the
      explanation.** Measured with Pocket Casts — *the very app that stole the card* — installed
      and holding a session on the same device:

      - `Media button session is io.github.mattpvaughn.chronicle.debug/Chronicle`
      - Chronicle is **top of the Sessions Stack**; `PocketCastsMediaSession` sits below it,
        `active=false`
      - `flags=7` — media buttons + transport controls + queue commands, all three set
      - `controllers: 8`, real metadata (`Xenos, Dan Abnett`), `queueTitle=Xenos`
      - **exactly one** Chronicle session → cu-89 **lead 1** (two competing sessions, or one
        recreated per service start) is **ruled out**
      - `dumpsys audio`: Chronicle is the sole focus entry, `gain: GAIN`, `loss: none` → cu-89
        **lead 2** (focus requested but not granted, or immediately lost) is **ruled out**

      **The owner's phone is API 34+** (confirmed session 4), and the `setFlags` change only
      affects **API 27** — media-button and transport-control flags are auto-enabled from API 28.
      So that change *cannot* be what fixed the reported symptom, and cu-89's progress note
      already said as much ("not a confirmed fix ... if it is 28+ this changes nothing"). It is now
      confirmed to be 28+.

      **What that leaves.** Either the symptom has a cause not yet identified, or something else in
      the intervening work fixed it incidentally. Two sessions' worth of platform checks have found
      nothing wrong with Chronicle's session on API 32. The remaining honest step is to reproduce
      on the API 34+ phone itself — and if it no longer happens there either, close cu-89 as
      "no longer reproducible, cause unidentified" rather than claiming the flag change fixed it.
      Do **not** tick this off the flag change.

### Unofficial endpoints

- [ ] **A book switch flushes the outgoing position** ([[cu-91]]). On device A, play book X for a
      minute, then start book Y *without* pausing X first. On device B, open book X: it must show
      where A stopped, not an older position. Then check the inverse — pressing play, pause and
      resume on a single book must **not** emit a `STOPPED` report for it (watch `/:/timeline` in
      the server log). The flush now happens in `AudiobookMediaSessionCallback.playBook`, so it
      covers the mini player, Android Auto and media buttons too, not just the details screen —
      worth trying from at least two of those entry points.
- [x] **`/:/timeline`, scrobble, websockets.** Community-documented, not guaranteed
      (CLAUDE.md, Gotchas). Confirm the current Plex version still accepts the shapes the
      app sends — this is the item most likely to have silently drifted.
      **Verified 2026-09-02, session 3, against Plex Media Server 1.43.3.10896 (Windows).** No
      drift: `/:/timeline`, `/:/scrobble` and `/:/unscrobble` all accept the app's exact query
      shapes and return `200`.

      **One requirement worth pinning down, found by getting it wrong.** `/:/timeline` returns
      **`400`** without an `X-Plex-Client-Identifier` header, and `200` with one. Isolated to that
      single header — `X-Plex-Product` alone still 400s. The app is fine (`PlexInterceptor` sets it
      from the persisted `uuid`), but it means **the endpoint requires a client identity, not just
      a token**, which is not obvious from the community docs and would break any future code path
      that built a timeline request outside the interceptor.

      **The scrobble semantics, measured directly** — this is the mechanism behind session 1's
      data loss, now confirmed rather than inferred:

      | call | effect on a track |
      |---|---|
      | `/:/scrobble` | `viewCount` **1 -> 2** (it *increments*; it is not a flag) and `viewOffset` cleared |
      | `/:/unscrobble` | `viewCount` cleared entirely |

      So re-scrobbling an already-watched track both inflates the count *and* destroys the
      listener's position — exactly why `ProgressReporter`'s `viewCount == 0L` one-shot guard is
      load-bearing, and why the owner's library carried counts of 183/129/126.

      Also learned: an **album-level** `unscrobble` **cascades down and clears its tracks**, while
      an album's `viewCount` is *derived* from its tracks rather than stored independently —
      consistent with decision-16 ("position is owned by the tracks"). Test data was restored to
      its original `viewCount = 1`.

      **Websockets remain untested** — the app's usage of them was not exercised here.
## Acceptance Criteria

- [ ] Every checklist item above checked against a real server, with the result recorded
      (pass, fail, or "server behaves differently than assumed")
- [ ] Fixtures corrected wherever the real response differs from the modelled one — a
      fixture that disagrees with reality is worse than no fixture
- [ ] Any failure filed as its own task rather than fixed inline, so this pass stays a
      verification step and not an open-ended debugging session
- [ ] Items added by later tasks appended to the checklist as they arise

## Session 4 — 2026-09-02, same tablet (Phh-Treble GSI, Android 12 / SDK 32), **mock server**

Ran as a guided pass over the remaining manual items. Deliberately started on the mock, because
five cu-115 items and one cu-13 item looked mock-coverable. Three closed, three partially — and
the session turned up one real bug and one harness limitation that changes how the rest is planned.

### First: the device was not in the state session 3 left it

Session 3's notes describe a 1.64 GB download left in place as the fixture for the open download
items. **It is gone.** App data was wiped and the debug APK reinstalled at 22:15–22:25, so all four
databases are fresh, no cached files remain, and the app came back up pointed at the *mock* server
(`server_name=Mock Plex Server`, `http://127.0.0.1:51585`) rather than the live one.

Survived the wipe, because they are device settings rather than app data: the SD card
(`public:179,129`, 44 GB free) and the Private DNS strict-mode workaround. So the SD-eject item is
still viable.

**Lesson for the next session:** device state that a checklist item depends on has to be treated as
perishable. A note saying "left in place as the fixture" is not a guarantee; re-check before
planning around it.

### The bug: the mini player disappears for good when playback stops → [[DRAFT-119]]

**This is the actual cause of [[cu-74]]**, which had guessed at a large-screen layout problem and
asked whether it reproduced on a phone. It is not a layout bug at all. The mini player renders
correctly; it is *hidden on purpose* and then stranded.

`MainActivityViewModel.playbackObserver` maps `STATE_STOPPED`/`STATE_NONE` to
`setBottomSheetState(HIDDEN)`, and neither path off `HIDDEN` can fire afterwards: there is no
further non-stopped playback state, and `setAudiobook`'s `previousAudiobookId != bookId` guard
rejects re-selecting the same book. The collapsed player is the only handle that expands the sheet,
so the player becomes unreachable — cu-74's reported consequence, now traced to a cause.

Clean repro from a force-stop, with the log in order:

```
Bottom sheet state is HIDDEN                      # initial value
Observing playback: PlaybackState {state=6 ...}   # BUFFERING
Bottom sheet state is COLLAPSED                   # mini player appears, correctly
Observing playback: PlaybackState {state=3 ...}   # PLAYING
Observing playback: PlaybackState {state=1, position=180002 ...}  # STOPPED at track end
Bottom sheet state is HIDDEN                      # gone, permanently
```

A `uiautomator dump` then holds **zero** `currently_playing_*` views while `dumpsys media_session`
still reports the app's session `active=true`. That mismatch is the signature to look for.

**Why it hid for so long:** every previous observation was made after playback had already run out,
so it looked like the mini player never rendered. Two things made it visible now — cu-115's 180 s
tone (it was 5 s) widens the COLLAPSED window, and this book had all three tracks at full progress,
so every resume stopped within seconds. Note the corollary: for a *finished* book the mini player is
effectively never reachable.

The existing `MainActivityViewModelTest` asserts `HIDDEN` as an initial/expected value but never
that it is *escapable*, which is why the gate was green. That gap is in DRAFT-119's criteria.

### The harness limitation: the mock cannot serve per-track chapters

Worth recording, because it silently invalidates part of the plan. `MockPlexServer.fixtureFor`
routes on the path *shape* and ignores the requested id:

```kotlin
path.contains("/children")        -> "tracks.json"
path.startsWith("/library/metadata") -> "track-with-chapters.json"
```

So **every** book's `/children` returns The Hobbit's 3 tracks, and **every** track's metadata
returns all 3 entries of `track-with-chapters.json`. Measured consequence: `chapter_db` holds **18
rows** — the same 3 chapters written once per track — instead of the fixture's intended 8 distinct
chapters. The book's own `chapters` column is empty (`length(chapters) = 0`), so the player falls
back to `tracksAsChapters` and the CHAPTERS list shows the 3 *track* names, grouped "DISC 1 / DISC 2".

Two corrections follow:

- The CHAPTERS list showing track names, and `duration=360000` appearing in a `/:/timeline` report
  for a 540 s book, are both **fixture artifacts, not app bugs.** I nearly filed the duration one
  before checking; `albums.json` declares 1002 at 360000 while `/children` hands it 1001's tracks.
- **My own earlier claim in this session — that the five cu-115 items were fully mock-coverable —
  was too optimistic.** The three that depend only on *position arithmetic across a boundary* are;
  the two that need distinct per-track chapter data (jump-to-chapter, slider seek target) are not.
  Fixing this is a real improvement to the harness: route `fixtureFor` on the id so each track gets
  its own chapters. Filed as part of DRAFT-119's context rather than a separate task for now.

### What the mock did verify

The useful trick was resetting progress directly in the DB to place playback mid-book, since
`play_book` always resumes the saved position (`USE_SAVED_TRACK_PROGRESS`, no offset extra):

```
run-as ... sqlite3 .../track_db "UPDATE MediaItemTrack SET progress=175000 WHERE id='2002';"
```

With track 2 at 175/180 s, playback crossed into track 3 on its own and gave:

- **Track title follows the boundary in the full player** — "Roast Mutton" → "A Short Rest", and
  the highlight moved to entry `03`. Blank on later tracks before cu-115. The *mini* player could
  not be checked at all: DRAFT-119 keeps it off screen.
- **Position arithmetic is right, and ordered.** Track 2 at `00:49` → book `03:50/09:00 42%`
  (180+49 = 229 s; 229/540 = 42.4%). Track 3 at `00:10` → book `06:11/09:00 69%`
  (370 s; 68.7%). This is the specific thing cu-115 fixed: an unordered `getTrackStartTime` sum
  would have reported a whole track ahead (06:49 rather than 03:50).
- **Previous-chapter takes the restart branch** well into a chapter:
  `PlayerExtKt: skipToPrevious → back to start of current chapter`.
- **`uiautomator dump` succeeds with the player open** — confirms the already-ticked cu-110 item on
  this device, 28 KB hierarchy, including while the sheet was EXPANDED.

### Still needs a human or a live server

Unchanged from session 3's list, minus nothing — no live-server item was closed here. Note the
ordering constraint that came up: the app points at one server at a time, so the mock pass and the
live pass cannot be interleaved. Do the mock items first, then log in.

The two download items additionally need their fixture re-created (see the wipe above) before they
can run at all.

### Later the same session: back on the live server

**The clean-slate route matters, and the obvious route does not work.** Flipping
`--ez mock_plex false` is *not* enough to get back to a real server. `MockPlexMode.enable` seeds
`accountAuthToken` / `server` / `library` into prefs, and `determineLoginState` returns
`LOGGED_IN_FULLY` whenever all three are present — so the app boots believing it is logged into
`http://127.0.0.1:51585`, with no login screen and no way forward.

`MockPlexMode.disable()`, which would `plexPrefs.clear()`, is **dead code — nothing calls it**, and
`onMockPlexIntent` kills the process with `Runtime.exit(0)` before anything could. So the working
route is `adb shell pm clear <pkg>`, which also drops the `mock_plex` flag (it lives in
`chronicle_debug.xml`). Worth fixing, or worth a note in the hook's KDoc; recorded here for now.

Fresh login to **ANTARES**, library "Audiobooks" (id 14), `server_owned=false` — a *shared* user,
not the owner, which constrains the token-rotation items below. **196 books** synced.

Re-confirmed on a fresh login (both items were already ticked; this is independent evidence):

- **cu-42 HTTPS** — all three stored connections are `https://…plex.direct`, LAN on `:32400`,
  relay on `:8443`. No cleartext anywhere.
- **cu-11 tiering** — all three tiers stored and correctly classified (`local:true` /
  `local:false,relay:false` / `relay:true`), and on restart:
  `Trying 1 LAN connection(s)` → `Chose LAN connection: https://192-168-1-54.….plex.direct:32400`
  in **~1.4 s**. The Private DNS strict-mode workaround is holding; session 3's search-domain
  hijack did not recur.
- **cu-14, server → new device** — the book opened at `2:58:02/28:40:39 10%`, adopted from the
  server after a full data wipe. Progress made on another device came back intact. (This is not
  the same as the two-device convergence items, which still need a second device.)
- **cu-83 `file://` scheme** — the enqueued download target is
  `file:///storage/emulated/0/Android/data/…/155607.m4b`, scheme intact.

### A security bug found on the first real download → [[DRAFT-120]]

`AppModule` sets `.enableLogging(true)` on the Fetch2 config **unconditionally**, and Fetch2 logs
`DownloadInfo.toString()` — which includes the headers map. Result: `X-Plex-Token=<working token>`
written to logcat three times before a single byte transferred, in **release builds too**.

`TokenLoggingTest` could not catch this: it scans our own `Timber` calls, not a third-party
library's internal logging. The rule ("never log an auth token") was enforced only where we are the
caller. Filed with a fix sketch and, importantly, a criterion for a guard that *can* catch the
class.

### The download fixture, re-created

`This Inevitable Ruin` (155606, 28:40:39, single track) re-downloaded over the LAN to internal
storage. **Completed: 1564 MB allocated, `stat` 1,639,241,764 bytes, `isCached=1`**, in ~4.5 min
(~130 MB/min on this LAN).

**The measurement trap from session 3 reproduced exactly:** `ls`/`stat` reported the full
1,639,241,764 bytes within a second of starting, because Fetch2 preallocates. Session 3's advice —
read `_written_bytes` from `databases/LibGlobalFetchLib.db` — remains the right measure. `du -m`
also works (it reports allocated blocks: 308 → 607 → 887 → 1564 MB) and needs no SQL, but
`_written_bytes` is the authoritative one; prefer it.

**One thing I got wrong, recorded so the next session does not repeat it.** Mid-download the book
row read `isCached=1` while its track read `cached=0`, and since `MediaItemTrack.getTrackSource()`
branches on the *track* flag, I inferred playback would stream from the network despite a complete
local file. **It does not.** Testing it directly gave
`Media uri is: file:///storage/emulated/0/…/155607.m4b`, and a re-read showed `cached=1` — the
cache scan reconciles the track flag shortly after the group completes. The book/track ordering is
*eventual*, not inconsistent. The lesson is the repo's own: profile/observe, do not read and infer.

### The router DNS hijack reproduces on a second device (session 4)

Session 3 recorded the `homenet.telecomitalia.it` search-domain hijack as a tablet finding with a
device-level workaround. **It is not device-specific.** The Galaxy A33 (API 36), fresh onto the
LAN with no Private DNS set:

```
$ ping 192-168-1-54.<hash>.plex.direct
PING …plex.direct.homenet.telecomitalia.it (127.0.0.1)   ← loopback
```

Consequence: Chronicle chose **`87-17-202-231…` — the WAN tier — while sitting on the same LAN as
the server**, 17 ms away by IP. It works, so nothing looks broken; it just routes household audio
out to the internet and back. Applying the same workaround
(`settings put global private_dns_mode hostname` / `private_dns_specifier one.one.one.one`) fixed
it immediately: `Chose LAN connection: https://192-168-1-54.….plex.direct:32400`.

**So every device on this network needs the workaround, and a new device silently gets the WAN
path.** That strengthens session 3's conclusion that the real fix is on the router (drop the DHCP
search domain, or disable DNS rebind protection) — it is an owner decision, but it now affects two
devices out of two tested, i.e. all of them. Chronicle cannot influence it: the platform resolver
is not app-controllable.

Worth noting for the checklist's own integrity: the WAN tier is **not** why sync failed
(DRAFT-121 reproduces on the LAN tier too), but it was a confounder that had to be removed before
the sync result meant anything.

### Removing a device at plex.tv does NOT sign Chronicle out (session 4)

Recorded because it is the obvious thing to reach for when you want to invalidate a token without
touching the account password, and it **silently proves nothing**.

The owner removed every `Chronicle / Phh-Treble vanilla` entry from plex.tv's Authorized Devices.
Measured immediately after, tablet cold-started and then a book's details screen opened to force
authenticated traffic:

- **111 library requests, every one `200 OK`, zero 401s**
- `GET https://plex.tv/api/v2/resources` → `200 OK`, still listing `ANTARES`
- every request carried the **same single token**, the *server access token*

**Why.** plex.tv's device list governs *account*-level client registrations. Chronicle's library
traffic authenticates with the **server access token** — a separate credential minted by the
server, per `PlexTokenAuthenticator`'s own two-token model. Revoking the device entry leaves that
untouched, so no 401 ever occurs.

The app's 401 handling was **correct** — no 401 arrived, and per the cu-84 rule ("only an
authenticated request that came back 401 counts") it rightly claimed nothing. **But the outcome is
still a defect**, and the owner has ruled on it: *"remove the device from the plex list should
absolutely kick out chronicle, this needs to be fixed asap (after the cu-73 checklist is
completed)."*

Filed as **[[DRAFT-122]]**, R0/security. The measured cause is that Plex invalidates *neither*
token on device removal — the account token (`FdX…`) still gets `200 OK` from
`plex.tv/api/v2/resources`, and the server token (`FxC…_6S`) still gets `200 OK` from ANTARES — so
there is no rejection for the app to react to. `refreshServer` is only ever called from the
authenticator, i.e. reactively on a 401, and nothing proactively asks whether this client is still
authorized. The likely fix is to check the app's own `X-Plex-Client-Identifier` against a
*successful* `/api/v2/resources` response at startup; the trap to avoid is letting a failed or slow
call read as signed-out, which would reintroduce cu-84.

**What does work, for the record:** a password change with "sign out connected devices" (the blunt
instrument the items name), or corrupting the *stored* server token locally to force a 401 — the
latter tests the re-auth path but not an account-level sign-out, so the two items still need
different treatment. See the items themselves.

### Device state left behind after session 4

- **Logged into the live server (ANTARES), mock mode off, app data cleared once** to get there —
  so the mock fixture state from earlier in the session is gone. The mock/live switch is one-way
  per pass; plan mock items and live items as separate blocks.
- **`This Inevitable Ruin` (155606) downloading or downloaded** to internal storage
  (`/storage/emulated/0/Android/data/…/files/155607.m4b`, ~1.6 GB). This is the fixture for the
  three open download items. Check it with `du -m`, never `ls`.
- SD card (`public:179,129`, 50 GB free) and Private DNS strict mode untouched, exactly as
  session 3 left them.
- The Hobbit multi-track mock state is **gone** with the data clear — but that no longer matters.
  **The real library has far better multi-track fixtures**, so the remaining cu-115 items need no
  mock mode and no `pm clear` (which would destroy the download fixture above):

  | book | id | tracks | length |
  |---|---|---|---|
  | Forward the Foundation | 151180 | 113 | 968 min |
  | Ender's Game | 151444 | 107 | 671 min |
  | Stone Blind | 150974 | 77 | 521 min |
  | Weyward | 150621 | 57 | 651 min |

  (Two larger ones exist — 150697 with 240 tracks, 150361 with 159 — worth using for the
  large-library item.) Prefer these over the 3-track mock fixture: the task's own rule is that a
  fix verified against the easy fixture is not verified, and `getTrackStartTime`'s ordering bug
  is far more likely to show across 107 tracks than 3. Place playback mid-book with
  `UPDATE MediaItemTrack SET progress=… WHERE id='…'` as before.

## Session 3 — 2026-09-02, same tablet (Phh-Treble GSI, Android 12 / SDK 32), live Plex server

**13 of 45 done, up from 7.** Six items closed, one partially, one previously-failed item turned
green, and **one new bug found** that settles a question open since cu-12.

### The headline: #83 is real, and it is ours

Tapping Download on a 293 MB m4b **crashes the debug build with `OutOfMemoryError`** in ~50 s with
zero bytes on disk. Cause: `HttpLoggingInterceptor` at `Level.BODY` sits on the media OkHttp client,
and cu-76 routed downloads through that client — so `BODY` buffers the entire audiobook in memory.
Filed as [[cu-109]].

cu-12 had reasoned that there was "no OOM mechanism in app code" and left #83 as possibly stale.
That reasoning was correct as far as it went — the mechanism is not in app logic, and not in Fetch2
either. It is in the *client Fetch2 was handed*, which is a seam no amount of reading either side
would reveal. Debug-only, so not shipping, but it blocks six checklist items.

### The correction worth reading: session 2's DNS "dead end" was not one

Session 2 recorded that `plex.direct` "does not resolve on this network" and concluded the LAN tier
could not be exercised here. **That was too broad, and it cost this item a session.** The actual
fault is two distinct router misbehaviours:

- the correct name `...plex.direct` -> **empty answer** (rebind-style filtering)
- the DHCP **search domain** `homenet.telecomitalia.it` gets appended, and
  `...plex.direct.homenet.telecomitalia.it` -> wildcard **`127.0.0.1`**

The second is where session 2's "resolved to loopback" came from. Cloudflare, Google and Quad9 all
answer correctly with `192.168.1.54`. So the fix was on the device all along: Private DNS
(DNS-over-TLS) to `one.one.one.one`, after which the LAN name resolves in ~5 ms and
`Chose LAN connection` appears in the log.

**Method note, the third session running.** Sessions 1 and 2 both concluded "instrument, don't
reason". This one adds a corollary: when an environmental blocker is recorded, **test the blocker
itself, not just the thing it blocks**. One `dig` against three resolvers was the whole cost of
recovering an item written off as needing different hardware.

### What the API sweep gave cheaply

Querying the real server directly (rather than only through the app) closed three items in minutes:

- **`lastViewedAt` is seconds**: all 63 values exactly 10 digits, spanning 2023-05-25 to
  2026-09-01. The `10^11` threshold has ~55 years of headroom.
- **#119 cannot manifest here**: across **all 1376 tracks in all 196 books** — including books of
  240, 159 and 113 parts — **zero** tracks carry a `thumb` differing from their `parentThumb`.
- **Chapters match the file exactly**: 25 vs 25, all titles byte-identical, worst offset delta 49 ms
  against `ffprobe` read straight off the server.

Worth keeping as a habit: several cu-73 items are questions about *the server*, not about the app,
and answering those with `curl` and `ffprobe` is far cheaper than driving the UI.

### Confirmations that the earlier fixes hold

- **cu-9 progress**: ~4.5 min of playback, every report carrying a real position (no `time=0`),
  `viewCount` frozen at 249 through playback and a pause (no scrobble), position reaching the server
  (`viewOffset: 269636`) and surviving a force-quit (`progress = 277670` restored).
- **cu-84 offline**: aeroplane mode, cold start -> `LOGGED_IN_FULLY`, library rendered from Room,
  **zero 401s**. Offline is not signed-out.
- **cu-87 chapter highlight**: the detail screen after a cold start, without pressing play, showed
  `04:37/10:13:25` and chapters `06:25` / `26:11` — matching the real data to the second.
- The doubled `duration` sent to `/:/timeline` is **intentional** (Plex finishes at 90% of what it
  is told). I suspected a bug mid-session and was wrong; what this pass adds is that the server
  still stores the *real* duration, so the workaround is safe.

### Update, later the same session: cu-109 fixed, five more items closed

[[cu-109]] was fixed immediately after being filed, and re-running the download group closed
**five** items: the large-book download (on a **1.64 GB** book, so the ~2 GB question is
effectively answered), offline playback of a downloaded book, the kill-and-relaunch resume, the
Range-resume byte count, and the partial-promotion half of the Wi-Fi-drop item. **18 of 45 now,
from 7 at the start of the session.**

Three things worth carrying forward:

- **Flat memory against a climbing byte count is the real proof.** 661 -> 852 MB written while PSS
  sat at 141–144 MB, finishing at 135 MB — *below* the pre-download baseline. A completion check
  alone would not have distinguished streaming from a lucky buffer.
- **Keeping `HEADERS` logging paid for itself within the hour.** The resume evidence
  (`Range: bytes=966369280-` / `Content-Range: bytes 966369280-.../1639241764`) is only visible
  because cu-109 capped the level instead of dropping to `NONE`. That was argued for on
  diagnosability grounds before there was a use for it.
- **`stat` size is not download progress.** Fetch2 preallocates, so the file reports full size
  within a second. Read `_written_bytes` from `LibGlobalFetchLib.db`. I misread this once.

### Second sweep: what was automatable after all

A deliberate audit of the 27 then-remaining items found **six more** that needed no human, and all
six passed. Worth recording *why* they were missed, since the same bias will recur: each had been
written as a physical-world action ("eject the SD card", "switch networks", "a book with a space in
its path") when the actual requirement was a *state*, reachable with `sm unmount`,
`cmd wifi set-wifi-enabled`, `mv`, or a pref edit.

- **Network switch** — ~4.6 s recovery, playback never dropped (partial: no SIM for cellular)
- **SD-card eject** — on a real 59 GB card, flags correctly stayed "downloaded but unavailable"
- **cu-83 percent-encoding** — played from `Chronicle Downloads àéî/` as
  `Chronicle%20Downloads%20%C3%A0%C3%A9%C3%AE`
- **cu-13 chapter fallback** — 107 chapters for a 107-track book, offsets absolute and tiling the
  book exactly
- **cu-85 repeated relaunches** — three cold starts, no drift
- **`/:/timeline` + scrobble shapes** — no drift on PMS 1.43.3, and the scrobble *increment*
  semantics measured directly

The two most valuable findings came from being wrong first: `/:/timeline` 400s without
`X-Plex-Client-Identifier` (found by omitting it), and `opportunistic` Private DNS does **not** fix
the LAN route (found by recommending it and testing).

### Third sweep: the three items that were code, not device work

`FakePlexServer` and the Moshi item were never device work at all. Closing them took no hardware
and added **24 tests** (562 -> 586), coverage 28.44% -> 28.54%.

All three were the *same* blind spot, and it is the [[cu-107]] shape again: a seam left untested
because the unit on each side was tested well. Each is now measured rather than argued —

- **retry-once guard deleted from production**: all **12** existing `PlexTokenAuthenticatorTest`
  cases still pass; the new wiring tests fail
- **`/identity` misspelled `/idendity`**: **6 of 9** new probe tests fail; all **9**
  `ConnectionChooserTest` cases stay green
- **explicit JSON `null` on a non-null field**: **7 of 8** real-shape tests fail

The lesson worth keeping: when a KDoc says a property comes from the framework ("OkHttp threads
`priorResponse`") or that a dependency is "injected so this is testable without Retrofit", that
sentence is naming an untested seam. Both did, in as many words.

Two mistakes made along the way, both instructive. I first sabotaged codegen by removing a Kotlin
default, which broke the *test compile* rather than a runtime parse — a compile-time sabotage proves
nothing about adapter strictness. And an earlier sabotage appeared to pass only because KSP had not
regenerated the adapter; the generated file still had the old default in it. **Check the generated
source, not just the test result.**

### A bug the owner found while reading these screenshots

**[[cu-110]] — back and the nav bar stop responding while the player is open.** Reported after
looking at this session's screenshots, reproduced and measured immediately: it is not an input or
navigation defect but **main-thread saturation**. 88% janky frames, a 4950 ms 90th-percentile
frame, ~24% of a core burned continuously, and GC freeing ~165,000 objects every 4 seconds. Taps
and back presses are simply dropped.

Cause: `HomeViewModel.recentlyListened` is a Room `LiveData`, which re-emits on *any* write to the
Audiobook table — and `ProgressUpdater` writes once a second during playback. So the home list is
rebuilt every second **while the player covers it**, and each rebuild deserializes
`Audiobook.chapters` (~108 chapters × 2 books) through `ChapterListConverter`.

Three logging defects were fixed on the way, the worst producing **3.38 MB of log output across
2920 lines** on the main thread in one session (`Audiobook.toString()` drags in the serialized
chapters column). Worth recording that **fixing them changed nothing the owner reported** — jank
went 88% -> 82% — which is what proved the recomputation is the real cause. Stopping at the logging
would have looked like a fix and been one.

This also explains an annoyance throughout this session: `uiautomator dump` kept failing with
`ERROR: could not get idle state`, forcing screenshots instead of hierarchy dumps. Same root cause,
so cu-110 unblocks automated UI assertions here too.

### Still needs a human at the device

- ~~**The "position not synced" badge.**~~ **Done and automated** — see the item above. Both
  debug-hook gaps were fixed rather than worked around: `fail_sync` now applies to a live server,
  and `show_player` expands the sheet, so no tap coordinates are involved.
- **The SD-card eject test.** Now genuinely possible: the tablet has a real **59 GB card mounted
  with 44 GB free** (`public:179,129`, `79AF-CD2E`), and the app already has directories on both
  volumes. `sm` can simulate the eject without touching the hardware. Earlier sessions assumed this
  needed hardware that was not present.
- **Skip silence** — needs ears on a quiet-voiced narrator; the constants are explicitly unmeasured.
- **Token rotation, and a password change with "sign out connected devices"** — account-level
  changes only the owner can make.
- **A second device** for the two convergence items and the book-switch flush.

### Device state left behind after session 3

Deliberate, and worth knowing before the next session:

- **Private DNS is set to `one.one.one.one` in `hostname` (strict) mode.** This is the LAN-tier
  workaround for this router, not a test artifact — leaving it on keeps Plex traffic on the LAN
  instead of routing out to the WAN and back. Remove it only to reproduce the DNS failure.

  **`opportunistic` mode does NOT work — tested, 2026-09-02.** It looks like the safer choice
  (falls back to plaintext instead of hard-failing behind a captive portal) but it still sends
  queries *to the router*, upgrading the transport only if the router supports DoT. So the
  search-domain hijack returns: `ping` resolved
  `...plex.direct.homenet.telecomitalia.it -> 127.0.0.1` again and the app fell straight back to
  `Chose DIRECT connection`. Only **strict** mode bypasses the router's resolver.

  The tradeoff is therefore real and unavoidable at the device level: strict mode fixes the LAN
  route but cannot resolve anything if port 853 is blocked (hotel captive portals). The proper fix
  is on the **router** — drop the `homenet.telecomitalia.it` DHCP search domain, or disable its DNS
  rebind protection — which is an owner decision, not an app one. Chronicle cannot influence any of
  this; the platform resolver is not app-controllable.
- ~~**`This Inevitable Ruin` (1.64 GB) is downloaded**~~ — **gone as of session 4.** App data was
  wiped and the debug APK reinstalled at 22:15 on 2026-09-02, taking the download, all four
  databases and the live-server config with it. The still-open download items (offline playback of
  a *multi-hour* book, the `FAILED` retry, token rotation mid-download) need this fixture
  re-created before they can run. Treat any "left in place" note as perishable.
- Settings still carry **offline mode on and refresh rate 6h** from the cu-77 import test in
  session 2.

## Session 1 — 2026-09-01, owner's Galaxy A33 (Android 16 / SDK 36), live Plex server

5 of 45 checks done. The pass is **not** finished, but it has already justified itself: **fifteen
bugs** that no automated check in this repo could have found, because they need a real device, a real
account, or a real library.

Installed side by side with the owner's existing upstream v0.52.1 via a debug `applicationIdSuffix`,
so a working install was never at risk.

### Data loss — the two that matter most

- **`time=0` written to Plex on every playback start.** The progress loop begins the moment playback
  is requested and read the controller position with a `?: 0L` fallback, before the player had
  seeked. Captured: `time=0` then `time=28726270` one second later. Closing the app in that window
  would have made 0 the saved position. It fired **7 times** in one short session.
- **`/:/scrobble` on every progress tick.** Plex *increments* `viewCount` and clears `viewOffset`;
  once playback passed a track's final second every later report re-fired it. The owner's library
  carried `viewCount` 183, 129 and 126 on single tracks of books played a few times, and a book he
  was still listening to had `viewOffset = 0`. **Already-damaged server data is not repaired by the
  fix.**

Both are strong candidates for the original cross-device divergence report.

### The cu-58 binding sweep

Seven bindings were dropped when cu-58 hand-translated 106 DataBinding expressions across six
screens. Each is silent — the view renders, and is never told anything. Found: choose-user list,
search results (data *and* visibility *and* connected-state), both chapter lists, and the
"hide played" switch. Audited by extracting every `@{viewModel.…}` from the pre-conversion layouts;
that audit is now clean. `OrphanedAdapterTest` guards the shape going forward.

### Platform behaviour at SDK 36

- **All back handling was dead.** `targetSdk 36` on Android 16 makes predictive back mandatory and
  never calls `onBackPressed()`. Confirmed by instrumentation. Migrated to
  `OnBackPressedDispatcher`.
- **Bottom nav under the system bar** in 3-button navigation mode: padding a fixed-height view does
  not move it clear. Every emulator used gestures, where the inset is small enough to hide it.
- **Mini player squashed to 24dp** by a hardcoded `64 + 72 = 136dp` guideline that assumed the nav
  bar was exactly its declared height.

### Async-write races — three separate instances

`time=0`, the seek snap-back, and `postValue` on the sheet state. All the same shape: code assuming
an asynchronous write is immediately visible. Worth treating as a pattern rather than three bugs.

### Method note

Three fixes were attempted on the seek before the right one, all reasoned from reading the code. What
actually solved it was **measuring**: a log showing 228 UI recomputations per minute. On a device,
instrument first.


## Session 2 — 2026-09-02, tablet (Phh-Treble GSI, Android 12 / SDK 32), live Plex server

A second device, deliberately *unlike* session 1's Galaxy A33: **SDK 32, not 36**, and a
1920×1200 **landscape tablet**. So it re-verifies none of session 1's platform findings
(predictive back, bottom-nav insets and the mini-player squash are all SDK 35/36 behaviours) but
it does cover a form factor nothing in this backlog had ever been run on.

Installed side by side with the `.debug` suffix again; login, library population and the Home
screen all render correctly at this size.

### One bug, in the layer the unit tests cannot reach

**[[cu-107]] — the connection tier is destroyed by its own persistence.** `local` and `relay` are
dropped when the chosen server is written to `SharedPreferences` and rebuilt with the single-arg
`Connection(uri)` constructor, so every connection reads back as `DIRECT` and cu-11's tiering does
nothing from the second launch onwards. The app still connects, by whichever route answers first —
here, the **WAN address**, while a LAN address was available.

Worth dwelling on *why* every cu-11 unit test passes: they construct `Connection` objects directly
and assert the chooser's decisions. The defect is entirely in the round trip, which no test crosses.
The checklist item said "confirm from the log which tier was chosen **rather than assuming**" — that
wording is the only reason this was caught, since the observable behaviour is simply "it works".

### An environment fact that shaped the above

`192-168-1-54.<hash>.plex.direct` **does not resolve on this network** — verified independently
with `dig` from a second machine, which returns an empty answer, while the WAN name resolves fine.
On the device it resolved to `127.0.0.1`, so the LAN probe failed against loopback.

This is a DNS matter, not an app bug (Plex's wildcard `*.plex.direct` records are public DNS, so a
resolver that filters rebind-style answers — many routers and most public resolvers do — breaks LAN
addressing). It does mean **the LAN tier could not be exercised end-to-end here**: cu-107 explains
why no LAN tier was offered, but even with cu-107 fixed this network would still fail the LAN probe
and fall back to WAN. Re-checking the LAN half of cu-11 needs a network whose resolver returns
private answers for `plex.direct`.

**Fixed the same day ([[cu-107]] is Done).** The LAN tier is back and observable on device —
`Trying 1 LAN connection(s)` and `Trying 1 DIRECT connection(s)`, matching the server's two
connections. Fixing it also surfaced a second defect in `mergeServerRefresh`, which deduped
connections by whole-object equality and so kept a flagless cached copy alongside its freshly
flagged twin, putting one address in two tiers. The checklist item above stays **open** even so:
the LAN *probe* cannot succeed on this network's DNS, so "connects over LAN" is still unproven.

### A second bug, found by testing cu-77 on the same device

**The settings list never rebound a row whose value changed** (fixed in [[cu-77]]).
`PreferenceItemDiffCallback.areContentsTheSame` compared only title and explanation, so importing a
settings backup left every switch showing its old state while the preferences underneath were
already correct.

Same shape as the cu-107 finding above, and worth noting as a pattern: the code was correct
*locally* (the view holder reads `prefsRepo` live and would have rendered the right value), and the
defect was in a comparison deciding whether to run it at all. Both were invisible to unit tests and
both needed a screen.

### Method note, consistent with session 1

Session 1 concluded "on a device, instrument first". This session's finding came the same way: not
from reading `ConnectionChooser` (which is correct) but from reading **one log line that disagreed
with the model** — two connections classified into one tier when the server's own JSON said
otherwise. The fixture, the parse and the chooser were each individually right.

### Fourth sweep (2026-09-02): tooling repaired, and the real blocker named

A fresh audit of the 27 remaining items, on an attached **Phh-Treble vanilla GSI, Android 12 /
API 32, 1200x1920** (`sw800dp xlrg`, i.e. tablet-class). No Plex account, no SIM, no second device.

**Two script defects fixed — `capture-screens.sh` could not have worked on any device.**

1. Its shebang was corrupted to `#!/usr/b#!/usr/bin/env bash`. It parses as a comment, so
   `bash capture-screens.sh` worked and `./capture-screens.sh` failed with a bad interpreter —
   which is why it went unnoticed.
2. `PKG=io.github.mattpvaughn.chronicle`, the **release** id, while mock mode only exists in the
   debug variant (`...chronicle.debug`). `am start` therefore targeted a package that is not
   installed. Worse, the run's own foreground assertion still passed, because
   `grep -q "$PKG"` matches `...chronicle.debug` as a *substring* of the release name. It now
   derives the debug package (overridable via `CHRONICLE_PKG`) and the foreground check is
   word-boundary anchored.
3. Tab taps were hardcoded for "a 2560x1600 tablet". On this 1200x1920 device the settings tap
   landed off-screen, and the run reported `captured settings` having re-captured the library. The
   script's duplicate check caught it after the fact. **Now resolved from the live view hierarchy**
   via a new `tap_id` helper — which is only possible because [[cu-110]] fixed
   `uiautomator dump`.

Verified end to end: four **distinct** screens captured (home / book-details / library / settings),
no duplicates, settings visibly the settings screen with its tab highlighted. This is the first run
of this script that has actually navigated on this hardware.

**The real blocker for every player-open item is the fixture audio, not the hooks.** Sessions 1-3
diagnosed these as debug-hook gaps and fixed the hooks; `show_player` and `play_book` both work.
But the generated tone is **~5 seconds**, so playback reaches `state=3` and ends within about a
second — `dumpsys gfxinfo` accumulates only 14-16 frames, which is statistically meaningless, and
the sheet collapses before it can be measured. This is the *inverse* of the bias recorded in the
second sweep: an item written as needing a device actually needs a **fixture**.

The single highest-leverage fixture change on the list is therefore: **regenerate the tone at ~10
minutes and add a genuine multi-track mock book with chapters crossing track boundaries.** It is
generated, so there is no licence question, and `PlexFixtureContractTest` / `AudioFixtureTest`
already catch a duration/offset mismatch (they did once, per cu-64). That one change unblocks the
cu-110 jank measurement, the back/nav check, and the device halves of the multi-track items.

**Corrections to earlier framing**, both verified:

- The fixture is **not** chapter-less: `track-with-chapters.json` carries 3 chapters on track 2001,
  and `MockPlexServer.fixtureFor` serves it for any `/library/metadata` path. The gap is a
  multi-**track** book whose chapters *cross* boundaries — the unit fixture
  (`testing/MultiTrackBook`) has that shape, the mock pack does not.
- The cu-64 ExoPlayer-client range check needs **no proxy and no `EventLogger`**:
  `MockPlexServer.kt:50` already logs `range=${request.headers["Range"]}` on every request. Play,
  seek, grep for `range=bytes=`.
- The v5->v6 index item is **already better covered than a device check would be**:
  `RoomSchemaTest` opens a genuinely migrated file, asserts the index exists in `sqlite_master`,
  and asserts `EXPLAIN QUERY PLAN` reports `USING INDEX` with no `TEMP B-TREE`.
- Items "local progress is not clobbered by a stale server value" and "a deliberate seek backwards
  survives a sync" are effectively **already done** by `TrackProgressConflictTest` — the second
  matches a test name verbatim.

**The second unit-test tier nobody noticed.** cu-115 landed `MultiTrackBook` plus 24 cases, but
they pin the **helpers**; the *ViewModel consumers* are still untested. Specifically
`CurrentlyPlayingViewModel.chapterProgress`/`chapterProgressForSlider` have **no test at any track
count**, and their `coerceAtLeast(0L)` masks a frame regression as a stuck `0:00` rather than a
negative — invisible to both a test and a human watching the screen. The two hand-inlined seek
conversions (`:753`, `:1000`) and both `skipToPrevious` copies (`PlayerExt.kt:82`,
`CurrentlyPlayingViewModel.kt:592`) are also uncovered. That is real work available *before* any
device or fixture change.

**`FakePlexServer` cannot simulate token rotation**, which blocks two cu-10 items from being unit
tests: `stub()` is a prefix->single-response map, so a second stub *replaces* the first rather than
queueing. `ReauthWiringTest:81` has a comment claiming "401 once, then accept whatever comes next"
that the harness cannot honour — it passes only because it asserts on refresh *count*, never on a
200, and nothing inspects the retry's `X-Plex-Token`. A response queue (~30 lines) fixes both.

**The irreducible minimum is five areas**, unchanged in size but not in membership:

- **Skip silence** — real ears on real narration; a sine tone has no speech pauses.
- **Wi-Fi -> cellular** — no SIM in this GSI.
- **Two-device convergence** (x2) — a second physical device, or server-side state the fixture does
  not model.
- **Account-level changes** — token rotation and a password change with "sign out connected
  devices" are the owner's alone.
- **Android Auto card ownership** — needs a head unit or DHU, *and* the flag change matters only on
  API 27, which this API 32 device cannot test regardless.
