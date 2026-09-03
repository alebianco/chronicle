---
id: cu-64
title: Audio fixture for end-to-end playback verification
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: [cu-16]
priority: medium
milestone: m-1
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

### What is verified

The fixture server delivers a real, seekable audio stream over HTTP; the app fetches all three track
parts from it and decodes them, with `MediaBrowserService` running under `targetSdk 36`.

### The debug playback intent (added)

`--el play_book <id>` on the MainActivity intent calls `playFromMediaId` — the exact call the play
button makes, so it exercises the real path rather than a shortcut around it. Debug source set only;
R8 strips `DebugHooks` entirely from release (`R8$$REMOVED$$CLASS$$` in the mapping).

Two things it needed that are worth recording:

- **`onNewIntent`, not just `onCreate`.** MainActivity is `launchMode="singleInstance"`, so a second
  `am start` is delivered to `onNewIntent` and `onCreate` never re-runs.
- **A guard around `connect()`.** `MediaServiceConnection.connect()` throws
  `IllegalStateException: connect() called while neither disconnecting nor disconnected` if the session
  is already connected, so the hook connects only when it is not.

With it, a scripted run reaches playback:

```
I DebugHooks: play_book: starting playback of book 1001
I AudiobookMediaSessionCallback$playBook: Tracks: [3 tracks, duration=5000, progress=1500 ...]
I MediaMetadataCompatExtKt: Media uri is: http://127.0.0.1:57693/library/parts/3001/.../file.wav
I OnMediaChangedCallback: Playback state changed to STATE_BUFFERING
I OnMediaChangedCallback: Playback state changed to STATE_PLAYING
```

The track list, durations, saved progress and resolved media URI are all correct, and the session
reaches `STATE_PLAYING`.

### The "remaining gap" was an instrumentation defect, not a playback bug

This task was first closed claiming **no request for the audio ever reached the mock server**. That
conclusion was wrong, and the way it was wrong is the most useful thing recorded here.

`MockPlexServer.dispatch` logged the incoming request *below* its `/photo/` and `/library/parts/`
early returns. Those two paths therefore returned before ever reaching the log line: they were served
correctly and were simply invisible. The measurement had a hole in exactly the shape of the thing
being measured, so "zero audio requests in the log" was read as "zero audio requests", and a full
diagnostic run was spent chasing three hypotheses about ExoPlayer's `DataSource` — none of which were
real.

Hoisting the log above the branches (found by the R0-close adversarial review) showed the app had been
fetching all three track parts the entire time. `AudioFlinger` independently confirms 330,750 frames
delivered at 22.05kHz mono — exactly 15.000s, i.e. all three 5s tracks decoded and rendered, including
the track transitions this fixture exists to exercise.

**Bytes flow, and always did.** The lesson is the recurring one in this release: a check that cannot
fail proves nothing, and that applies to diagnostic logging just as much as to tests.

### One genuine gap remains

ExoPlayer fetched with `range=null` — it read the streams sequentially and never seeked, so the 206 /
`Content-Range` path is implemented and unit-tested (`AudioFixtureTest`) but not yet exercised
end-to-end by the app. Proving that needs a scripted seek, which belongs with cu-9, and a
real-server scrub is on the [[cu-73]] checklist.

## Acceptance Criteria

- [x] Mock serves an audio stream for track part requests, with range support — in both the debug app
      and the test server, covered by 6 tests
- [x] A scripted run reaches playback without tap coordinates — `--el play_book <id>` drives
      `playFromMediaId` directly; the session reports `STATE_BUFFERING` then `STATE_PLAYING`
- [x] Audio confirmed fetched from the fixture server — all three track parts requested and served;
      `AudioFlinger` confirms 330,750 frames = 15.000s decoded. The earlier "no requests" finding was a
      logging blind spot, not a bug; see the Implementation Notes.
- [x] Fixture audio is generated or licence-clean; no third-party media committed — generated sine tone
