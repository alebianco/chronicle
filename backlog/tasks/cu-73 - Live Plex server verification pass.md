---
id: cu-73
title: Live Plex server verification pass
status: To Do
assignee: []
labels: [R1, agentic, verification]
dependencies: []
priority: high
created_date: '2026-08-31'
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

- [ ] **cu-42 — HTTPS on the LAN.** Confirm a local server connects over
      `https://<hyphenated-ip>.<hash>.plex.direct` and **not** plain http. This is the
      one with a known behaviour change: a server with Secure Connections set to
      *Disabled* will no longer connect on its LAN address. Verify the default
      (*Preferred*) works, and note what the failure looks like if someone has disabled
      it, so the error is diagnosable.
- [ ] **cu-42 — `resources.json` fidelity.** The fixture now models local connections as
      hyphenated-IP HTTPS. Capture a real `/api/resources` response and confirm the shape
      matches; correct the fixture if not.

### Connection tiering (cu-11)

- [ ] **A LAN-only server connects, and over LAN.** Confirm from the log which tier was
      chosen (`Chose LAN connection: ...`) rather than assuming — the whole point is that
      relay no longer wins races it should not be in.
- [ ] **Network switch mid-playback recovers in under 5 seconds.** Wi-Fi to cellular and
      back while a book plays. The arithmetic supports it (1.5s tier budget + 5s connect
      timeout) but elapsed time cannot be measured in a unit test.
- [ ] **The real `/resources` response shape.** Capture it and confirm `relay` is present
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
- [ ] **Plex metadata conventions.** Narrator via `Style` tags and series via `Mood` tags
      are a community convention, not a guarantee. Confirm a real library populates them
      as expected — several R2/R3 features depend on it.

### Playback

- [ ] **cu-64 — seek over a real range request.** The 206/`Content-Range` path is
      unit-tested but never exercised end-to-end: ExoPlayer read the fixture
      sequentially (`range=null`) and never seeked. A real book long enough to scrub
      through will exercise it.
- [ ] **cu-9 — progress reporting round-trip.** Local progress must reach the server and
      survive an app restart, a track boundary, and playback from a second client. This
      is the position-loss family (#88/#112/#68) and the fixture cannot prove it, since
      the mock accepts any timeline write without modelling server-side state.
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
      while it does. [[cu-12]] found no OOM mechanism in app code, so #83 is either in
      Fetch2's internals or stale — this is what decides which.
- [ ] **Wi-Fi drop mid-download, then reconnect.** Expect resume, not a silent stop. Then
      check the book is *not* marked available offline while incomplete — that promotion of
      partial files is [[cu-76]]'s first defect.
- [ ] **Kill the app mid-download and relaunch.** Same expectation.
- [ ] **A downloaded book plays with the server unreachable.** Should already work (cached
      tracks resolve to a local path), but confirm the UI does not block on a connection check.
- [ ] **Which route a download takes** once `OkHttpDownloader` is wired ([[cu-76]] item 3):
      downloads should get the same tier as playback, not a relay while playback uses LAN.
- [ ] **Range resume genuinely continues rather than restarting.** Watch the transferred byte
      count across an interruption: Fetch2 claims HTTP-Range resume, but nothing in the repo
      proves the server honours it for these URLs. A restart-from-zero on a 2GB book is a very
      different user experience from a resume, and both look like "it downloaded eventually".
- [ ] **A download stranded at `FAILED` before the fix is retried on next launch.**
      `ResumePlan.idsToRetry` is unit-tested against mocked state; what is unverified is that a
      real exhausted-retry download actually presents as `FAILED` (rather than `CANCELLED` or
      `REMOVED`) — the whole resume path hinges on that status being the one Fetch2 reports.
- [ ] **A rotated server token mid-download recovers.** cu-10's re-auth now sits in the download
      path via `OkHttpDownloader`; that combination has never run. Rotate the token during a
      download and expect a retry that succeeds, not a failed book.

### Chapters and artwork (cu-13)

- [ ] **Do the chapters shown match the m4b's embedded chapters?** Chapters come from Plex's
      `retrieveChapterInfo`, which is Plex's own read of the file — so this verifies Plex's
      parse as much as the app's. Compare against the file's chapter list read directly (e.g.
      `ffprobe -show_chapters`). Check a book with many short chapters and one with none.
- [ ] **A book with no embedded chapters falls back to one chapter per file.** Fixed in cu-13
      (`asChapterList` built its chapters and discarded them, so such books showed none at
      all); unit-tested, but never seen against a real library.
- [ ] **Which artwork the player shows for each track** (#119). `MediaItemTrack.thumb` is
      Plex's per-track thumb; in the fixture pack it happens to point at the *album's* art, so
      the bug cannot be reproduced offline. On a real server, check whether any track carries
      its own art — if so the lockscreen/Auto shows chapter art instead of the book cover, and
      the fix is to model `parentThumb` and prefer it.
- [ ] **Chapter highlight tracks playback** across a track boundary, and jumping to a chapter
      in a later file seeks to the right place.

### Sync drift (cu-14)

- [ ] **A second device's position is adopted.** Listen on device A, stop, then open the book
      on device B and refresh. B should jump to A's position. This is the round trip the
      timestamp fix enables and that no mock can prove — the fixture server accepts timeline
      writes without modelling server-side state.
- [ ] **`lastViewedAt` really is seconds on a live server.** The fix normalises seconds to
      millis and passes through anything already large enough to be millis. Capture a real
      `/library/metadata/{id}/children` response and confirm the magnitude — if a server
      reports something else, the threshold heuristic could be wrong either way.
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
- [ ] **Mark as read, then unread, is a clean round trip** ([[cu-86]]) — including that a sync
      afterwards does not revert it, which is where local and server semantics could still disagree.
      Check the library list shows finished / in-progress / not-started correctly for each.
- [ ] **Chapter highlight matches the timeline on a cold start** ([[cu-87]]). Open a part-listened
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
- [ ] **Being offline is not reported as being signed out** ([[cu-84]]). Aeroplane mode must not
      produce the signed-out state. This is the failure that would nag every user on a train.
- [ ] **Which media session owns the card** ([[cu-89]]). Reproduce on the phone first — see that
      task; Auto may not be needed. Note the session-flag change only affects API 27.

### Unofficial endpoints

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
