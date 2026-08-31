---
id: cu-89
title: Android Auto integration incomplete (no icon, no media card)
status: To Do
labels: [R2, comfort, bug]
dependencies: [cu-73]
priority: medium
---

## Description

Owner-reported (2026-08-31): *"android auto does not work as well as other apps (no icon, no media
playback card reported)."*

**Read the code first; most of it is already right.** What was checked and found correct:

- `MediaPlayerService` is declared with `foregroundServiceType="mediaPlayback"` and the right intent
  filters (`android.media.browse.MediaBrowserService`, `MEDIA_PLAY_FROM_SEARCH`, `MEDIA_BUTTON`).
- `automotive_app_desc.xml` declares `<uses name="media"/>`; `SmallIcon` meta-data is present.
- `MediaButtonReceiver` is registered and exported.
- The session **is** activated (`isActive = true` in `ServiceModule.mediaSession`) — an earlier read
  of `MediaPlayerService` alone suggested it never was, because the only occurrence there is the
  `false` on teardown. It is set at construction.
- `prefsRepo.allowAuto` defaults to `true`, and Android Auto is in `auto_allowed_callers.xml`.
- `onGetRoot`/`onLoadChildren` build four browsable categories and return `FLAG_PLAYABLE` items.

So the cause is **not** obvious from source, and this cannot be diagnosed on a laptop. Two leads
worth checking on the device before writing any code:

1. **Cover icons are fetched by Auto's own process.** `Audiobook.toMediaItem` sets
   `setIconUri(plexConfig.makeThumbUri(thumb))`, a `*.plex.direct` URL with `X-Plex-Token` in the
   query. Auto fetches that itself, with no access to the app's OkHttp client, its interceptors, or
   cu-11's chosen connection. If the URL points at a LAN address the phone can reach but Auto's
   fetcher cannot — or if the cert/redirect handling differs — every icon silently fails to load.
   That would present exactly as "no icon". Consider passing a bitmap
   (`METADATA_KEY_ALBUM_ART`) or a content:// URI the app serves, instead of a remote URL.
2. **`setFlags(FLAG_HANDLES_QUEUE_COMMANDS)` omits `FLAG_HANDLES_MEDIA_BUTTONS` and
   `FLAG_HANDLES_TRANSPORT_CONTROLS`.** Those are documented as unnecessary on recent API levels,
   which is presumably why they were dropped, but `minSdk` here is 27 — worth confirming rather than
   assuming, since a session that does not advertise transport controls is a plausible cause of "no
   media playback card".

## Approach

Diagnose on hardware first, as part of [[cu-73]]. `adb logcat` while connecting to the Auto
simulator ("Android Auto Simulator" is already in the caller allowlist, and
`DebugHooks`/mock-Plex mode can stand in for a server) should show whether `onGetRoot` is reached,
whether it returns the empty root, and whether icon fetches fail. Only then decide what to change —
writing speculative fixes against a working manifest is how this stays broken.

## Acceptance Criteria

- [ ] Diagnosis recorded: which of `onGetRoot` / `onLoadChildren` / icon fetch / session flags is
      actually failing, with logcat evidence
- [ ] Book covers appear in the Auto browse tree
- [ ] The media playback card appears with working transport controls
- [ ] Browsing the four categories works, and playing from Auto starts the right book at the right
      position
- [ ] Whatever is fixed is covered by a test where a test is meaningful (media item construction,
      session flags), with the device-only parts recorded in [[cu-73]]
- [ ] Verify loop green
