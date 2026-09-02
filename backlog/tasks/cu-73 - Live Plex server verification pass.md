---
id: cu-73
title: Live Plex server verification pass
status: In Progress
assignee: []
created_date: '2026-08-31'
updated_date: '2026-09-02 10:20'
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

- [ ] **Network switch mid-playback recovers in under 5 seconds.** Wi-Fi to cellular and
      back while a book plays. The arithmetic supports it (1.5s tier budget + 5s connect
      timeout) but elapsed time cannot be measured in a unit test.
- [x] **The real `/resources` response shape.**
      **Verified 2026-09-02.** `relay` is present and spelled as the model expects (`"relay":false`
      on both connections; this server exposes no relay route). `IPv6` **is** present as a boolean
      and is `false` on every connection — so [[draft-75]] has its answer: the flag exists and is
      parseable, but nothing on this server needs it. Full captured payload is quoted in [[cu-107]]. Capture it and confirm `relay` is present
      and spelled as the model expects, and note whether `IPv6` connections appear at all —
      that decides whether [[cu-75]] is worth opening.
- [ ] **End-to-end connection selection via `FakePlexServer`.** The chooser's tests inject
      the probe, so the real `checkServer` wiring in `PlexConfig` is covered only by the
      Dagger graph resolving. Same harness cu-10 needs.

### Data and parsing

- [ ] **cu-62 (draft) — Moshi codegen.** Reflection is lenient about absent/null fields
      in ways generated adapters are not. The fixtures cover the fields they cover; a
      real library exercises the rest (missing narrators, odd chapter data, unusual
      collections). If codegen is adopted, this pass is where its risk is actually
      retired.
- [x] **Plex metadata conventions.**
      **Verified 2026-09-01.** Confirmed: narrator/series render, and the library populated correctly from a real 500+ book library. Narrator via `Style` tags and series via `Mood` tags
      are a community convention, not a guarantee. Confirm a real library populates them
      as expected — several R2/R3 features depend on it.

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

- [ ] **cu-9 — the "position not synced" badge on screen.** Its state is unit-tested and
      the terminal-failure path is confirmed in logcat, but the badge has never been
      *seen*: the currently-playing sheet did not lay out during the emulator run. Pull
      the player up on a real phone with the server unreachable and confirm it appears —
      and that it does **not** appear during ordinary playback, which is the failure mode
      that would make it worthless.

### Authentication

- [ ] **cu-10 — a rotated server token recovers silently.** Rotate the server's token
      (reset `PlexOnlineToken`, or re-claim the server) while the app holds a stale one,
      then play something. The 401 should be invisible: refreshed and retried, no message.
- [ ] **cu-10 — an invalidated account token degrades honestly.** Change the Plex password
      with "sign out connected devices", then play. Expect the sign-in-expired message,
      **no login wall**, downloaded books still playing, and — importantly — *no repeated
      401 storm* against plex.tv. The retry-once guard is unit-tested but never seen against
      a real server.
- [ ] **cu-10 — end-to-end 401 handling via `FakePlexServer.stubUnauthorized`.** The
      authenticator's tests exercise the decision, not the wiring; an integration test
      through MockWebServer would confirm the `AppModule` hookup actually fires.

### Downloads (cu-12 / cu-76)

- [ ] **A large book (ideally ~2GB m4b) downloads to completion**, and memory is watched
      while it does. — **BLOCKED 2026-09-02 (session 3), and it answered #83 on the way.** A 293 MB
      m4b **crashed the debug build with `OutOfMemoryError`** ~50 s in, zero bytes on disk, PSS
      climbing 248 -> 302 -> 350 MB before the process died on Fetch2's own `LibGlobalFetchLib`
      thread.

      Cause found and filed as [[cu-109]]: `HttpLoggingInterceptor` at **`Level.BODY`** is on the
      media OkHttp client, and [[cu-76]] routed downloads through that client — so `BODY` buffers
      the whole audiobook in memory. **Debug builds only** (`LOG_NETWORK_REQUESTS = BuildConfig.DEBUG`),
      so not a shipping bug, but it makes downloads untestable in the only instrumentable build.

      So [[cu-12]]'s open question is settled: #83 is **real**, and it is in neither app logic nor
      Fetch2 — it is the client Fetch2 was handed. Re-run this item, and the five below, after
      cu-109.

- [ ] **Wi-Fi drop mid-download, then reconnect.** Expect resume, not a silent stop. Then
      check the book is *not* marked available offline while incomplete — that promotion of
      partial files is [[cu-76]]'s first defect.
      — **blocked on [[cu-109]]** (downloads OOM in debug; see the first item in this group).
- [ ] **Kill the app mid-download and relaunch.** Same expectation.
      — **blocked on [[cu-109]]** (downloads OOM in debug; see the first item in this group).
- [ ] **A downloaded book plays with the server unreachable.** Should already work (cached
      tracks resolve to a local path), but confirm the UI does not block on a connection check.
      — **blocked on [[cu-109]]** (downloads OOM in debug; see the first item in this group).
- [x] **Which route a download takes** once `OkHttpDownloader` is wired ([[cu-76]] item 3):
      downloads should get the same tier as playback, not a relay while playback uses LAN.
      **Verified 2026-09-02, session 3** — captured before the [[cu-109]] OOM killed the transfer.
      The enqueued download URL was
      `https://192-168-1-54.<hash>.plex.direct:32400/library/parts/290802/1752356877/file.m4b?download=1`
      — the **LAN address, the same tier playback chose**, with the `X-Plex-Token` header attached,
      and the server answered `206 Partial Content` in 74 ms. So cu-76's gain is real: downloads do
      inherit the chosen connection rather than picking their own route.

- [ ] **Range resume genuinely continues rather than restarting.** Watch the transferred byte
      count across an interruption: Fetch2 claims HTTP-Range resume, but nothing in the repo
      proves the server honours it for these URLs. A restart-from-zero on a 2GB book is a very
      different user experience from a resume, and both look like "it downloaded eventually".
      — **client half blocked on [[cu-109]]**, but the **server half is already proven**: an
      open-ended `bytes=293700000-` returns `206` with the correct 68,919-byte tail (session 3, see
      the cu-64 item). So the server does honour Range on these URLs; what remains is watching
      Fetch2's own byte count across an interruption.

- [ ] **A download stranded at `FAILED` before the fix is retried on next launch.**
      `ResumePlan.idsToRetry` is unit-tested against mocked state; what is unverified is that a
      real exhausted-retry download actually presents as `FAILED` (rather than `CANCELLED` or
      `REMOVED`) — the whole resume path hinges on that status being the one Fetch2 reports.
      — **blocked on [[cu-109]]** (downloads OOM in debug; see the first item in this group).
- [ ] **A rotated server token mid-download recovers.** cu-10's re-auth now sits in the download
      path via `OkHttpDownloader`; that combination has never run. Rotate the token during a
      download and expect a retry that succeeds, not a failed book.

      — **blocked on [[cu-109]]** (downloads OOM in debug; see the first item in this group).
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

- [ ] **A book with no embedded chapters falls back to one chapter per file.** Fixed in cu-13
      (`asChapterList` built its chapters and discarded them, so such books showed none at
      all); unit-tested, but never seen against a real library.
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

- [ ] **Chapter highlight tracks playback** across a track boundary, and jumping to a chapter
      in a later file seeks to the right place.

### Sync drift (cu-14)

- [ ] **A second device's position is adopted.** Listen on device A, stop, then open the book
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

- [ ] **A downloaded book plays after a force-quit and relaunch, offline** ([[cu-83]]). The cached
      track URI now carries a `file://` scheme; the symptom was an unsupported-format error on
      downloaded books only. Try a book whose sync directory path contains a **space or a non-ASCII
      character**, since that is what the percent-encoding half of the fix is for.
- [ ] **Cached status survives repeated relaunches** ([[cu-85]]), and — the case that matters — with
      downloads on an **SD card, eject it**: the books must read as unavailable, *not* as
      never-downloaded, and must come back when it is reinserted. Also confirm
      `MoveSyncLocationWorker` does not leave a stale path in prefs after moving storage; that
      interaction was not audited.
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
- [ ] **Skip silence is listenable** ([[cu-88]]). The retuned values (800 ms minimum, 0.55 retention)
      are reasoned starting points, **not measured** — this check is what sets them. Use a
      quiet-voiced narrator and check chapter boundaries as well as mid-sentence pauses. Expect to
      revise the constants.
- [ ] **An expired token is noticed and recoverable** ([[cu-84]]). Invalidate the token server-side
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

- [ ] **Which media session owns the card** ([[cu-89]]). Reproduce on the phone first — see that
      task; Auto may not be needed. Note the session-flag change only affects API 27.

### Unofficial endpoints

- [ ] **A book switch flushes the outgoing position** ([[cu-91]]). On device A, play book X for a
      minute, then start book Y *without* pausing X first. On device B, open book X: it must show
      where A stopped, not an older position. Then check the inverse — pressing play, pause and
      resume on a single book must **not** emit a `STOPPED` report for it (watch `/:/timeline` in
      the server log). The flush now happens in `AudiobookMediaSessionCallback.playBook`, so it
      covers the mini player, Android Auto and media buttons too, not just the details screen —
      worth trying from at least two of those entry points.
- [ ] **`/:/timeline`, scrobble, websockets.** Community-documented, not guaranteed
      (CLAUDE.md, Gotchas). Confirm the current Plex version still accepts the shapes the
      app sends — this is the item most likely to have silently drifted.

## Acceptance Criteria

- [ ] Every checklist item above checked against a real server, with the result recorded
      (pass, fail, or "server behaves differently than assumed")
- [ ] Fixtures corrected wherever the real response differs from the modelled one — a
      fixture that disagrees with reality is worse than no fixture
- [ ] Any failure filed as its own task rather than fixed inline, so this pass stays a
      verification step and not an open-ended debugging session
- [ ] Items added by later tasks appended to the checklist as they arise

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

### Still needs a human at the device

The remaining items need taps I cannot drive reliably, or hardware/account changes: the
"position not synced" badge (needs the player sheet open with the server unreachable — the
`fail_sync` hook only works in mock mode, not against a live server), the SD-card eject test (the
tablet **does** have a real 59 GB card, so this is now possible), skip-silence tuning by ear,
token rotation and a password change, and a second device for the convergence checks.

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
