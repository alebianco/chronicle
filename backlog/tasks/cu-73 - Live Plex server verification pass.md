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
