---
id: cu-103
title: Playback stalls in Doze without the media FGS permission
status: Done
assignee: [claude]
created_date: '2026-09-01'
labels: [R1, trust, bug]
dependencies: []
priority: high
milestone: m-1
---

## Description

Streaming playback stopped every 10-15 minutes during a real listening session, with the phone
stationary in a car. Reported as "it stops playing, then crashes when I unlock" — which turned out
to be **two unrelated bugs** (the crash is cu-102).

## Root cause

`MediaPlayerService` declares `android:foregroundServiceType="mediaPlayback"`, but the manifest
declared only `FOREGROUND_SERVICE_DATA_SYNC`. Since Android 14 (API 34) a foreground service type
requires its matching `FOREGROUND_SERVICE_*` permission.

**The omission is completely silent.** The manifest merges, the build passes, lint says nothing, and
the service starts normally — it just never receives the exemptions the type is supposed to grant.
So the media service ran without the media-playback network exemption.

Evidence from the device logs:

```
22:15:41  DeviceIdleController: [DEEP] QUICK_DOZE_DELAY to IDLE
22:27:38  NetdEventListenerService: DNS Requested by 588, 10482(chronicle.debug), 4(FAIL), isBlocked=true
22:27:44  MediaPlayerService$playerEventListener: Exoplayer playback error: ... Source error
22:27:44  AudioPlaybackConfiguration ... state:paused
```

Twelve minutes into the Doze window the app's DNS is refused, and six seconds later ExoPlayer gives
up. The owner's hypothesis (idle/GC while the phone sat still) was correct as to mechanism.

A cached book would not have hit this — it needs the network. Worth re-testing offline separately
(cu-73).

## Why it was undiagnosable

`onPlayerError` logged `error.message`, which for a streamed book is the bare string `"Source
error"`. The HTTP status or IO fault lives in the `cause` chain. Nothing in the app's own log said
the network had been blocked.

## Implementation Notes

- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` added to the manifest, with a comment recording why.
- `ForegroundServiceTypeTest` parses the **source** manifest and fails on any declared
  `foregroundServiceType` lacking its permission. It also asserts at least one type exists, so the
  guard cannot pass vacuously. Sabotage: removing the permission fails it.
- `describePlaybackError` walks the cause chain, so the actionable fault is in the first log line
  and in the media session's error slot. 6 tests, including a cyclic chain (depth-capped).

Installed and verified on the device: `FOREGROUND_SERVICE_MEDIA_PLAYBACK: granted=true`.

## Acceptance Criteria

- [x] The media FGS permission is declared
- [x] A guard test fails the build if a service type lacks its permission
- [x] Playback errors log their cause chain, not just the wrapper message
- [ ] Confirmed on the device across a full Doze window (needs another listening session)

## Follow-ups

- The app is not on the Doze whitelist. It should not need to be — a correctly declared media FGS is
  exempt — but if stalls persist, `dumpsys deviceidle whitelist` is the next thing to check.
- Consider surfacing a "playback stopped: <cause>" message rather than silently pausing.
