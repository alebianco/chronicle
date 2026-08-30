---
id: cu-64
title: Audio fixture for end-to-end playback verification
status: Draft
assignee: []
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

## Acceptance Criteria

- [ ] Mock serves an audio stream for track part requests, with range support
- [ ] A scripted run reaches ExoPlayer `STATE_READY` and confirms `isPlaying`
- [ ] Fixture audio is generated or licence-clean; no third-party media committed
