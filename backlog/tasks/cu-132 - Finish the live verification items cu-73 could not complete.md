---
id: cu-132
title: Finish the live verification items cu-73 could not complete
status: To Do
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels: [R2, verification, agentic]
dependencies: []
priority: medium
milestone: m-2
---

## Description

> **Reclassified to R2, 2026-09-03, when R1 was frozen.** None of these is a known defect — each is
> a *half* of an item whose other half passed, or a check whose method got in the way. Every defect
> [[cu-73]] actually found is fixed and closed. Holding the R1 freeze open for verification
> bookkeeping would misrepresent what is unfinished.


[[cu-73]] closed at **43 verified / 7 carried**. This holds the remainder so the pass could be
closed without pretending those seven were done.

None is a known defect. Each is either a *half* of an item whose other half passed, or a check
whose method — not the app — got in the way. The real failure cu-73 found is separately filed as
[[cu-131]], which is fixed.

Two of the seven already have homes and are **not** repeated here: the media-session card
([[cu-89]], still In Progress) and the mini-player rendering ([[cu-74]], **closed 2026-09-03** as a
duplicate of [[cu-119]]).

## What is left, and exactly what remains of each

### 1. A download stranded at `FAILED` is retried on next launch

The only item never run at all. `ResumePlan.idsToRetry` is unit-tested against mocked state; what
is unverified is that a real exhausted-retry download presents as **`FAILED`** rather than
`CANCELLED` or `REMOVED` — the whole resume path hinges on that being the status Fetch2 reports.

**Method matters, and cost an attempt.** Killing Wi-Fi to exhaust retries also kills **Wi-Fi adb**,
which is how the tablet is reached — `adb: device offline`, unrecoverable without physically
re-enabling Wi-Fi or attaching USB. Do it one of these ways instead:

- attach **USB adb** first, *then* disable Wi-Fi; or
- drop only the server route: `iptables -I OUTPUT -d <server-ip> -j DROP` (this worked in
  [[cu-128]] and leaves the device reachable throughout).

### 2. Network switch mid-playback, Wi-Fi ↔ cellular

The Wi-Fi drop-and-restore half was measured in session 3 (~4.6 s, inside the 5 s target, no
`ExoPlaybackException`). What is untested is a genuine **Wi-Fi → cellular → Wi-Fi** transition,
because the test tablet has no SIM. Needs a device with mobile data.

### 3. cu-64 — seek over a real range request, ExoPlayer side

The **server** side is proven: the real server honours `Range` on part URLs (session 3, against
the 293 MB *Malleus* m4b). What is unproven is that **ExoPlayer's** seek path issues those requests
end to end during playback, rather than the 206 path being exercised only by unit tests.

### 4. Chapter highlight — jumping to a chapter in a later file

The *tracking* half is verified: crossing a track boundary moved the highlight unprompted. The
*jump* half is not, and could not be on the mock, whose `fixtureFor` returns the same three
chapters for every track. Now easy: the live library has 107-chapter books
(see [[cu-73]]'s notes), so tap a chapter in a later file and confirm the seek lands correctly.

### 5. Chapter title across a track boundary — the mini-player half

Verified in the **full** player. The **mini** player half was unverifiable at the time because
[[cu-119]] kept it off screen. That is fixed, so this is now a quick re-check.

### 6. Previous-chapter threshold — the go-back half

The *restart-current-chapter* branch is confirmed (`skipToPrevious → back to start of current
chapter`, well into a chapter). The *go to previous chapter* branch — pressing just after a chapter
start — was never run; the app backgrounded before the second press.

## Method notes worth keeping

Collected while running the pass, because each cost time:

- **Do not build sync fixtures by editing the database.** Plex only records progress for playback
  it believes it is serving; a hand-written position produces reports it answers
  `{"playbackState":"ignore"}` and silently discards, and the app then looks broken when it is not.
- **Read Room databases with their `-wal`** (`track_db`, `track_db-wal`, `track_db-shm` together,
  or `run-as … sqlite3` on the device). A main-file-only read reports stale data and fabricated a
  bug once already.
- **Plex does not validate the server token on LAN connections** — bogus token → `200` on the LAN,
  `401` over WAN. Any 401-path test must force a non-LAN tier ([[cu-128]]).
- **`uiautomator dump` fails while the expanded player is open** but succeeds with the mini player
  or when paused. Pause before dumping, or use `screencap`.

## Acceptance Criteria

- [ ] Item 1 verified with a method that keeps the device reachable
- [ ] Item 2 verified on a device with mobile data, or explicitly dropped as untestable here
- [ ] Items 3–6 verified against the live library's multi-chapter books
- [x] [[cu-74]] closed as a duplicate of [[cu-119]]
- [ ] Anything that fails becomes its own task, as in cu-73

## Related

- [[cu-73]] — the pass this carries forward
- [[cu-131]] — the one genuine defect cu-73 found, **fixed and closed 2026-09-03**
- [[cu-89]] / [[cu-74]] — the two carried items that already have homes
