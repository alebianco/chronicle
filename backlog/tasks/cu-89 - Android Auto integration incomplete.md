---
id: cu-89
title: 'Android Auto integration incomplete (no icon, no media card)'
status: In Progress
assignee:
  - claude
created_date: ''
updated_date: '2026-09-06 00:00'
labels:
  - R2
  - comfort
  - bug
milestone: m-2
dependencies:
  - cu-73
priority: medium
ordinal: 1000
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

**Reproduce on the phone first — Android Auto is probably not needed.** The symptom is about which
session the *system* considers active, and that is decided on the device, not by the car. So try:

1. Play something in another media app (Pocket Casts). Pause it.
2. Start a book in Chronicle and leave it playing.
3. Look at the lockscreen / notification shade media controls.

If the other app still owns those controls while Chronicle is playing, the bug is reproduced with no
car involved, and everything below can be done at a desk. If Chronicle *does* take the controls on
the phone but not in Auto, the problem is Auto-specific after all and the browse/session interaction
needs looking at instead.

### Capturing the evidence

```bash
adb shell dumpsys media_session
```

**`dumpsys media_session` is a live snapshot, not a log.** It lists the sessions that exist *at that
moment* plus a short recent-history tail — not sessions from previous days. A session dies with its
service, so Chronicle only appears while `MediaPlayerService` is running. Therefore:

- Run it **while Chronicle is actively playing**, with the other app having played recently.
- Capture it twice for contrast: once with only Chronicle playing, once after the other app has
  played, so the difference in the media-button owner is visible.

What to read in the output:

- **Sessions Stack** — is Chronicle's session listed at all? Is it marked active?
- **The media button session** — this is the one that owns the card. If it names the other app while
  Chronicle is playing, that is the bug, stated precisely.
- **More than one Chronicle session** — would mean the service registers a session per start without
  releasing the previous one (lead 1).

Worth capturing alongside:

```bash
# Audio focus owner — lead 2. Chronicle should hold focus while playing.
adb shell dumpsys audio | sed -n '/Audio Focus stack/,/^$/p'
```

If focus is requested but immediately lost back to the other app, the system's notion of "who is
playing" never moves, and no amount of session configuration will fix it.

Mock-Plex mode gives a server-free way to reproduce:
`adb shell am start -n io.github.mattpvaughn.chronicle/.application.MainActivity --ez mock_plex true`,
then `--es play_book <id>` to start playback without tapping.

Only after that evidence exists should any further code change be made.

## Device findings, 2026-09-05 — the central question is answered, and the answer is "it wins"

**Chronicle holds the media session, on the phone and on the tablet.** `dumpsys media_session`
captured *while playing* (the evidence criterion 2 asks for), against the real library:

```
Sessions Stack - have 2 sessions:
  Chronicle io.github.mattpvaughn.chronicle.debug/Chronicle   active=true   flags=7
    state=PlaybackState {state=3, position=14231, speed=1.0, ...}
    controllers: 8      metadata: Ender's Game, Orson Scott Card
    queueTitle=Ender's Game, size=107
  PocketCastsMediaSession au.com.shiftyjelly.pocketcasts       active=false
    state=PlaybackState {state=0, position=0, speed=0.0, ...}

Media button session is io.github.mattpvaughn.chronicle.debug/Chronicle
```

Chronicle is **first in the stack**, active, PLAYING, holds the media button session, and carries
correct metadata and a 107-item queue. Pocket Casts is inactive and stopped. So **lead 1 (two
competing sessions) and lead 2 (audio focus not held) are both ruled out**, and so is lead 4 — the
notification binds, since 8 controllers are attached.

This does **not** yet reproduce the owner's report. Either it was fixed by something since
2026-08-31 (the session claims media buttons now — the one ticked criterion), or the failure is
specific to the head unit rather than to the session, which is where the remaining leads live.

### An Android Automotive emulator now exists, and Chronicle runs on it

Set up headlessly this session — see the `chronicle-auto-emulator` memory for the exact route, and
note **Gradle Managed Devices cannot do this**: AGP refuses with *"TV and Auto devices are presently
not supported with Gradle Managed Devices."* A manual AVD (`chronicle_auto`, API 33
`android-automotive` arm64) boots in ~10s and reports `ro.build.characteristics=automotive`.

What it establishes so far:

- The system enumerates Chronicle as a media app — `cmd package query-services -a
  android.media.browse.MediaBrowserService` lists it as **Service #0, `isDefault=true`**, ahead of
  the built-in local player.
- Auto connects to the browse tree: `MediaPlayerService: Getting root!` in logcat.
- No crash on an Automotive image.

**Still unverified there**: the four browse categories rendering with content, playing from Auto,
and the card/icon appearance — the emulator boots to user 10 and `com.android.car.media` threw when
launched from the shell, so the browse tree was reached but not driven. That is the next step, not
a blocker.

## Owner clarification, 2026-09-06 — it was real Android Auto, and the layout names the surface

Asked whether the car might have been a non-Android-Auto integration (Ford calls its system
**SYNC 3**, which can also play Android audio over Bluetooth A2DP/AVRCP or over Ford's own
**AppLink/SmartDeviceLink** platform). The owner settled it:

> *"it was not via bluetooth, it was wired and the screen was replaced with a different one when the
> phone was connected. it had pocketcasts pinned at the bottom and on the right side, even while I
> was playing an audiobook with chronicle, and the navigator on the bigger left area"*

**So all three alternative-integration theories are dead**, and should not be re-investigated:

- **Not Bluetooth.** Wired, and the head unit's own UI was replaced — that is projection.
- **Not AppLink/SmartDeviceLink.** SDL requires integrating an SDK and registering an App ID with
  Ford (`FordDev@ford.com` approval). There is no SDL code in this repo, so under AppLink Chronicle
  would be *entirely absent*, not showing the wrong app. (Adopting SDL is also a poor fit for
  principle 7 / D13: it is an OEM-gated registration.)
- **SYNC 3 is therefore not the variable at all.** SYNC 3 v2.0+ supports genuine Android Auto over
  USB; from that point the head unit is only a display and Google's UI is what draws.

### What the described layout actually is

Navigation in a large left pane, media pinned bottom/right — that is **Android Auto's Coolwalk
split-screen** (shipped late 2022), not anything Ford draws. The relevant surfaces are Google's
**media card** and **taskbar media widget**.

### The likely cause, and why nothing reproduced at a desk

Google's documented behaviour for the era described: **the media card tracks the most recently used
media app *as Android Auto understands it*, and historically showed exactly one at a time.** Switching
apps replaced the card; getting the previous app's card back required *reopening that app and resuming
playback from within Android Auto*. That is a plausible exact match for the report — Pocket Casts had
been used in the car, Chronicle was playing (probably started on the phone), and the card never moved.

This also explains the cu-73/cu-89 non-reproduction: `dumpsys media_session` measures the **platform**
media session stack, and Chronicle demonstrably wins it (top of stack, `active=true`, media button
owner, audio focus `GAIN`, 8 controllers, correct metadata, 107-item queue). Android Auto's card is a
**separate selection made by the Android Auto app**, not a read of that stack. A correct media session
is necessary but not sufficient, so no amount of session-side evidence could have reproduced this.

Note Google shipped **multiple swipeable media cards** in late 2025, which changes this behaviour. The
phone's Android Auto version at the time of the report is therefore load-bearing and unknown.

### The one open question, and it needs the car

Whether Chronicle takes the media card **when launched from the Android Auto launcher itself** (rather
than being already-playing from the phone). If it does, this is Android Auto's documented app-switching
behaviour and there is **nothing to fix in Chronicle** — close as working-as-intended. If it does not
even then, it is a genuine Chronicle defect and the browse/session interaction is the place to look.

Owner has no car access until roughly **2026-10**. Blocked on that; do not spend further desk time on
session-side theories in the meantime.

**Also worth checking in the same sitting** (all cheap once in the car): whether Chronicle appears in
the Android Auto **launcher** at all, whether the icon is present there, and whether the four browse
categories render — the still-unticked halves of [[cu-23]] and [[cu-165]].

## Acceptance Criteria

- [x] Established whether the bug reproduces **on the phone** (lockscreen/shade media controls) or
      only in Auto — this decides whether it is a session problem or an Auto-specific one
- [x] Diagnosis recorded with `dumpsys media_session` evidence *captured while playing*: whether
      Chronicle's session is registered, active, and which app owns the media button session
- [ ] Chronicle holds the media playback card while it is playing, displacing any previously active
      app
- [ ] The app icon appears in the card (and separately, in the Auto launcher)
- [ ] Transport controls in the card work: play/pause, skip, seek
- [ ] Browsing the four categories works, and playing from Auto starts the right book at the right
      position
- [x] One candidate cause ruled out cheaply: the session now claims media buttons and transport
      controls, not only queue commands
- [ ] **Decisive test (needs the car, ~2026-10): launch Chronicle from the Android Auto launcher**
      and confirm whether it then takes the media card. If yes → Android Auto's documented
      most-recent-app behaviour, close as working-as-intended, no Chronicle change. If no → a real
      Chronicle defect, investigate the browse/session interaction
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

> **Settled 2026-09-02 (cu-73 session 4): the reported phone is API 34+, so this change is
> irrelevant to the symptom.** The flags are auto-enabled from API 28. This theory is therefore
> not merely unconfirmed but *excluded*; keep the flags (harmless, correct on API 27) and look
> elsewhere.
>
> Also measured that session, on a tablet at **API 32 with Pocket Casts installed and holding its
> own session** — i.e. the competing app from the report:
>
> - `Media button session is …chronicle.debug/Chronicle`, Chronicle top of the Sessions Stack,
>   `PocketCastsMediaSession` below it and `active=false`
> - one and only one Chronicle session → **lead 1 ruled out**
> - `dumpsys audio`: Chronicle the sole focus entry, `gain: GAIN`, `loss: none` → **lead 2
>   ruled out**
>
> So the symptom does not reproduce on API 32 and the two leading theories are dead. Next step is
> a reproduction attempt on the API 34+ phone. If it does not reproduce there either, close this
> as *no longer reproducible, cause unidentified* — do not attribute it to the `setFlags` change.

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
