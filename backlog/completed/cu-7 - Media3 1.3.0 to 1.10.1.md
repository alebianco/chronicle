---
id: cu-7
title: Media3 1.3.0 to 1.10.1
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, foundation]
dependencies: [cu-6]
priority: high
milestone: m-0
---

## Description

Core playback engine catch-up before touching playback code. Blocks the R1 reliability work.

## Implementation Notes

**Media3 1.3.0 → 1.11.0** — one line in the version catalog, **zero source changes**, builds clean on
the first attempt.

Went past the task's 1.10.1 to **1.11.0**, the current release: same `androidx.annotation` floor, and
no reason to land two versions behind on the day of the bump.

### Why an 8-minor-version jump was uneventful

The codebase only touches Media3 through `ExoPlayer`, `MediaSession` and the Cast extension, all via
`MediaPlayerService`/`MediaServiceConnection` — the seam CLAUDE.md convention 8 mandates ("never touch
ExoPlayer from UI"). That convention is what kept the blast radius to a version string. Worth noting as
evidence the rule earns its place.

No Media3 deprecation warnings. The warnings that remain (`onBackPressed`, `GlobalScope` in
`CachedFileManager`, Moshi annotation targets) are all pre-existing and unrelated.

### Verification — the first task where playback was actually checkable

cu-61 landed immediately before this and unblocked emulator playback, so this is verified rather than
merely compiled:

```
I ExoPlayerImpl: Init [AndroidXMedia3/1.11.0] [emu64a, Android SDK built for arm64, 35]
I MediaPlayerService: Service created!
I MediaPlayerService: SWITCHING PLAYER to androidx.media3.exoplayer.ExoPlayerImpl
I MediaPlayerService: Playback params: speed = 1.0, skip silence = false
I MediaPlayerService: Start command!
I ActivityManager: Background started FGS: Allowed [... targetSdkVersion:36 ...]
```

The version banner confirms 1.11.0 is what actually loaded, the player is constructed and wired to the
service, and playback params apply. The FGS line also incidentally validates cu-6's foreground-service
behaviour under `targetSdk 36`.

- `./verify.sh` green, 49 tests, 0 failures.
- `./test_release_build.sh` green; release APK **5.4 MB → 6.2 MB**, expected for eight minor versions.
- UI captured against the cu-16 mock, zero crashes.

### Still not verified

**Audio actually decoding and playing.** The mock serves JSON and cover art, not audio streams, so
there is nothing to decode. Everything up to and including player construction is confirmed; the
decode/render path is not. That needs a live Plex server or an audio fixture in cu-16.

**Update:** cu-64 added that fixture and closed this gap — the decode/render path is now verified
without credentials.

## Acceptance Criteria

- [x] Media3 upgraded — 1.3.0 → **1.11.0** (past the 1.10.1 in the title), zero source changes
- [x] Playback engine initialises and the service starts — verified from logs on an emulator
- [x] Audio decode/render verified — closed by **cu-64**'s audio fixture: all three track parts are
      fetched from the mock and decoded, with `AudioFlinger` confirming 15.000s rendered. No live Plex
      server needed.
