---
id: cu-89
title: Android Auto integration incomplete (no icon, no media card)
status: In Progress
labels: [R2, comfort, bug]
dependencies: [cu-73]
priority: medium
assignee: [claude]
---

## Description

Owner-reported (2026-08-31): *"android auto does not work as well as other apps (no icon, no media
playback card reported)."* Clarified on follow-up: **Pocket Casts was still shown in the media card
while an audiobook was playing in Chronicle, and there was no app icon.**

That clarification reframes the whole issue. It is **not** about cover art or the browse tree — a
different app held the media card *while Chronicle was playing audio*. So Chronicle's
`MediaSessionCompat` is not being recognised as the active media session, and browse-tree or icon
theories are the wrong place to look.

**What was read and found correct** (do not "fix" these):

- `MediaPlayerService`: `foregroundServiceType="mediaPlayback"`, with intent filters for
  `android.media.browse.MediaBrowserService`, `MEDIA_PLAY_FROM_SEARCH` and `MEDIA_BUTTON`.
- The session **is** activated — `isActive = true` in `ServiceModule.mediaSession`. An earlier read
  of `MediaPlayerService` alone suggested otherwise, because the only occurrence there is the
  `false` on teardown.
- `service.sessionToken = sessionToken` is set, and `setSessionActivity` is given a PendingIntent.
- Audio focus **is** requested: `exoPlayer.setAudioAttributes(..., handleAudioFocus = true)` with
  `USAGE_MEDIA`, content type SPEECH or MUSIC depending on `pauseOnFocusLost`.
- A `MediaStyle` notification is posted with `setMediaSession(sessionToken)` and a real
  `setSmallIcon`, via `startForeground`.
- `PlaybackStateCompat` is built with actions, state, position and speed, and pushed with
  `setPlaybackState`.
- `automotive_app_desc.xml` declares `<uses name="media"/>`; `MediaButtonReceiver` is registered;
  `allowAuto` defaults true; Android Auto is in `auto_allowed_callers.xml`.

**So the cause is not visible in the source.** Everything the platform documents as required for a
media session to take the card is present. Leads worth testing on hardware, in order:

1. **Two competing sessions, or a session created per service start.** If `MediaPlayerService` is
   started more than once, or the session is recreated without releasing the old one, the system may
   track a stale session. `MediaSessionManager.getActiveSessions` on the device settles this
   immediately.
2. **Audio focus is requested but not actually granted or held.** `handleAudioFocus = true` asks
   ExoPlayer to manage it; if the request is rejected (or immediately lost back to Pocket Casts) the
   system's notion of "who is playing" never moves. Worth logging focus changes.
3. **`setFlags(FLAG_HANDLES_QUEUE_COMMANDS)` omits `FLAG_HANDLES_MEDIA_BUTTONS` and
   `FLAG_HANDLES_TRANSPORT_CONTROLS`.** Documented as unnecessary on recent API levels, but
   `minSdk` is 27 — cheap to add and rule out.
4. **Notification/session mismatch.** If the foreground notification's session token is not the same
   instance the service registered, the card can fail to bind.

Note the "no app icon" half may be a *consequence* rather than a second bug: with no active session,
there is no card, hence no icon in it. Confirm whether the icon is missing from the Auto launcher
too (a separate, manifest-level concern) or only from the absent card.

## Approach

Diagnose on hardware first, as part of [[cu-73]]. Do not write speculative fixes against a manifest
and session setup that already read as correct — that is how this stays broken.

Concretely: play a book in Chronicle with another media app (Pocket Casts) recently active, then
dump `adb shell dumpsys media_session` to see which session the system considers active and whether
Chronicle's appears at all. Log audio-focus transitions alongside it. The Auto simulator is already
in the caller allowlist, and mock-Plex mode (`--ez mock_plex true`) plus the generated audio tone
give a server-free way to reproduce.

## Acceptance Criteria

- [ ] Diagnosis recorded with `dumpsys media_session` evidence: whether Chronicle's session is
      registered, active, and whether a competing session holds the card
- [ ] Chronicle holds the media playback card while it is playing, displacing any previously active
      app
- [ ] The app icon appears in the card (and separately, in the Auto launcher)
- [ ] Transport controls in the card work: play/pause, skip, seek
- [ ] Browsing the four categories works, and playing from Auto starts the right book at the right
      position
- [x] One candidate cause ruled out cheaply: the session now claims media buttons and transport
      controls, not only queue commands
- [ ] Whatever is fixed is covered by a test where one is meaningful; device-only parts in [[cu-73]]

## Progress Notes

### One theory ruled out, not a claimed fix

`MediaSessionCompat.setFlags` was called with `FLAG_HANDLES_QUEUE_COMMANDS` alone. The media-button
and transport-control flags are auto-enabled from **API 28**, but `minSdk` here is **27** — so on the
oldest supported release the session advertised neither, and a session that does not claim transport
controls is a plausible reason for Auto showing no media card.

All three are now set. Harmless on newer releases, so this closes off the theory rather than leaving
it as a maybe. **It is not a confirmed fix** — the reported device's API level is unknown, and if it
is 28+ this changes nothing.

### Still the open question

The owner's clarification reframed this: **Pocket Casts held the media card while Chronicle was
playing audio.** So Chronicle's session is not becoming the *active* session, which is not a
browse-tree or artwork problem.

Everything the platform requires is present and was individually checked — `mediaPlayback` service
type, `isActive = true` (in `ServiceModule`, not `MediaPlayerService`, where the only occurrence is
the `false` on teardown), `service.sessionToken`, audio focus with `handleAudioFocus = true`,
`MediaStyle` with `setMediaSession`, a real `setSmallIcon`, `PlaybackStateCompat` with actions and
state, `automotive_app_desc.xml`, `MediaButtonReceiver`, and Auto in `auto_allowed_callers.xml`.

Remaining leads, in order:

1. **Two competing sessions, or one recreated per service start** without releasing the old.
   `dumpsys media_session` settles this immediately.
2. **Audio focus requested but not granted or immediately lost.** If focus never lands, the system's
   notion of "who is playing" never moves. Log focus transitions.
3. **Notification/session token mismatch**, which can stop the card binding.

Next step is hardware, not code.
