---
id: cu-64
title: Audio fixture for end-to-end playback verification
status: In Review
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: [cu-16]
priority: medium
---

## Description

The cu-16 mock serves JSON and cover art, so the app can be driven to the point of *constructing* a
player but never decodes or renders audio. cu-7 (Media3 1.3.0 → 1.11.0) could confirm ExoPlayer
initialises and `MediaPlayerService` starts, but not that a book actually plays.

That is the gap between "playback is wired" and "playback works", and it sits on the app's single most
important behaviour.

### Scope

1. Add a short, freely-licensed or generated audio file to `plex-fixtures/` — a few seconds of tone is
   enough; this tests the pipeline, not fidelity. Must be **DRM-free and licence-clean** (D12 rule 7);
   generating a sine wave avoids the question entirely.
2. Serve it from `MockPlexServer` for `/library/parts/...` requests, with correct `Content-Type` and
   `Content-Length`, and ideally honour range requests since ExoPlayer seeks.
3. Extend `capture-screens.sh` (or add a sibling script) to press play and assert from logcat that the
   player reaches `STATE_READY` and `isPlaying = true`.

### What this would unlock

- cu-7's unchecked criterion, and any future Media3 bump.
- **cu-9** (progress-reporting overhaul), **cu-13** (chapter correctness) and **cu-12** (download
  rebuild) all change behaviour that only manifests during playback.
- Seek, chapter boundaries, speed changes and sleep-timer behaviour become observable without a real
  server.

Consider also whether a fixture can exercise multi-track books, since track transitions are where
position loss historically occurred (the #88/#112/#68 family).

## Implementation Notes

### The fixture

`plex-fixtures/track.wav` — a **generated** 5s 440Hz sine tone (22.05kHz mono 16-bit, ~220KB), with a
short fade so it does not click. Generated rather than sourced, which makes the licence question moot
(D12 rule 7): no third-party media enters the repo.

### Served with range support, which is the part that matters

Both `MockPlexServer` (debug app) and `FakePlexServer` (tests) now serve `/library/parts/...` as audio
and honour a single-range `Range` header, returning 206 with `Content-Range`.

That is not optional polish: **ExoPlayer range-requests when it seeks and treats a server without
`Accept-Ranges` as non-seekable.** A fixture that ignored `Range` would appear to work while silently
making every seek test vacuous — the same shape of trap as the mapping.txt guard in cu-45 and the
screenshot script in cu-58.

### Fixture durations had to change with it

Track durations claimed 1 hour against 5 seconds of audio. Left alone, progress percentages and chapter
boundaries would be computed against a length the audio does not have, making cu-9/cu-13 verification
meaningless. Durations, `viewOffset` and chapter offsets are now scaled to the real 5s tone, and the
cu-16 contract tests were updated to match — those tests **caught the change**, which is what they are for.

### Tests

`AudioFixtureTest` (6 cases) pins the transport contract: a decodable RIFF/WAVE body, `Accept-Ranges`,
exact slice sizes on a bounded range, open-ended ranges, graceful handling of an out-of-bounds range,
and that **all three** track parts serve audio — track transitions being where position loss
historically occurred. Verified to bite: removing the `Accept-Ranges` header fails the suite.

### What is verified, and what is not

**Verified:** the fixture server delivers a real, seekable audio stream over HTTP, and the app reaches
ExoPlayer construction with `MediaBrowserService` running under `targetSdk 36`.

**Not verified: audio actually decoding and rendering in the app.** Driving the play button by `adb
shell input tap` proved unreliable — coordinates that work on one screen state miss on another, and the
media-key path needs an active session. The transport half is now solid and covered by tests; the
in-app trigger is not. Left In Review rather than Done for that reason.

The cleanest fix is a debug-only intent that starts playback of a known book directly, removing tap
coordinates from the loop entirely. Worth adding when cu-9 needs it.

## Acceptance Criteria

- [x] Mock serves an audio stream for track part requests, with range support — in both the debug app
      and the test server, covered by 6 tests
- [ ] A scripted run reaches ExoPlayer `STATE_READY` and confirms `isPlaying` — **not done**: driving
      play via `adb input tap` is unreliable; needs a debug intent to trigger playback directly
- [x] Fixture audio is generated or licence-clean; no third-party media committed — generated sine tone
