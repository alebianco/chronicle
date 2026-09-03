---
id: cu-137
title: startForeground is deferred behind a cover-art fetch
status: Done
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - playback
  - bug
milestone: m-2
dependencies: []
priority: high
---

## Description

Found in the 2026-09-02 branch review, re-confirmed against the tree on 2026-09-03. Every
`startForeground` call site awaits a **network fetch** first, so the 5-second foreground-service
deadline is racing a cover-art download.

`NotificationBuilder.buildNotification` is `suspend`, and at `NotificationBuilder.kt:252` it awaits:

```kotlin
val largeIcon = plexConfig.getBitmapFromServer(artUri)
```

All three call sites in `MediaPlayerService` post the notification only after it returns:

| site | context |
|---|---|
| `MediaPlayerService.kt:238` | service start — the comment two lines above states the 5 s deadline |
| `MediaPlayerService.kt:322` | `onMediaChangedCallback` |
| `MediaPlayerService.kt:557` | media-button event — same deadline comment above it |

### Why it can exceed the deadline

`getBitmapFromServer` (`PlexConfig.kt:112-134`) runs Coil's `SingletonImageLoader.execute`, and
Coil is wired to the app's **media OkHttp client** (`ChronicleApplication.kt:84-93`), which carries
`CONNECT_TIMEOUT_SECONDS = 5` + `READ_TIMEOUT_SECONDS = 15` (`AppModule.kt:61,68`). So a cold cache
on a slow route can block for up to **20 s** before `startForeground` is reached — four times the
budget.

Two details worth keeping straight, because the original review got them slightly wrong:

- It is **Coil**, not a raw OkHttp call, and the body catches `Throwable` and returns null — so it
  does not *throw* past the caller. The defect is purely that it **blocks**.
- The memory-cache guard at `NotificationBuilder.kt:250` means this only bites when the book
  changes or the bitmap was recycled. First play of a book with cold artwork is exactly that case.

Symptom on Android 12+: `ForegroundServiceDidNotStartInTimeException`; on older releases, an ANR.
Worst on a relay connection, which is also when a listener is least able to retry.

### Direction

The standard fix: post a notification with the small icon immediately, call `startForeground`, then
fetch the art and `notify()` a second time to attach it. That splits `buildNotification` into a
synchronous "enough to satisfy the deadline" build and an async art attach. Note
`buildNotification` returns `Notification?` and both sites pass the result straight into
a non-null `startForeground(Int, Notification)` (`MediaPlayerService.kt:869`) — worth checking that
path while here.

## Acceptance Criteria

- [x] `startForeground` is reached without awaiting any network call, at **all four** call sites
      — there were four, not three: `OnMediaChangedCallback.kt:100` was missed when this was filed.
- [x] Artwork still appears in the notification once fetched, via a second `notify()`
      — confirmed by screenshot on device: full cover art plus all five transport controls.
- [x] A test proves the deadline property: a `getBitmapFromServer` that never returns must not
      delay `startForeground` — `ForegroundDeadlineTest`, and the sabotage result is **stronger
      than a test failure**: re-adding `suspend` breaks compilation at all three synchronous call
      sites, so the fix is now compiler-enforced.
- [x] The nullable-`Notification` path into `startForeground` is either made non-null by
      construction or handled explicitly, not implicitly unwrapped
      — `buildNotification` and `buildNotificationWithoutArtwork` both return non-null now.
- [x] Verified on a device: first play of a book with cold artwork over a slow/relay route posts
      the notification promptly and does not throw `ForegroundServiceDidNotStartInTimeException`

## Implementation Notes

**The split.** `buildNotificationWithoutArtwork` is synchronous and builds everything a valid
notification needs — actions, media style, small icon, titles — because all of it is local. Only the
large icon was remote. `buildNotification` is now a thin suspend wrapper that adds the art. Callers
holding a deadline post the first, then re-post the second when the fetch lands.

**Four call sites, not three.** The task named three; `OnMediaChangedCallback.updateNotification`
was the fourth, and it is on the `STATE_PLAYING` path that *promotes* the service. Its second phase
deliberately uses `notify` and **not** `startForeground`: the state machine has already decided
this state's foreground status, and PAUSED releases it on purpose to stay swipe-dismissable, so
re-promoting there would undo that. Two tests pin this.

**Measured on device** (Samsung SM-A336B, API 36, live Plex server, Coil disk cache cleared so the
fetch had to be cold):

| time | event |
|---|---|
| `11:06:37.918` | service created |
| `11:06:37.935` | **`startForeground` reached** — `startForegroundCount: 0 -> 1`, **17 ms** in |
| `11:06:38.026` | art fetch *begins*, after the promotion |
| `11:06:46.598` | a later art fetch — **8.6 s** after service start |

That 8.6 s fetch is the finding, demonstrated: under the old code it was *in front of*
`startForeground`, against a 5 s budget. Zero `ForegroundServiceDidNotStartInTimeException`, zero
ANRs, audio confirmed playing, and the notification shows full artwork.

**Sabotage-verified, and it upgraded to a compile error.** Making the immediate build `suspend`
again fails `compileDebugKotlin` at all three synchronous sites rather than merely failing a test —
the same "turn a process failure into a mechanical one" shape as `DebugHooksContract`. Separately,
dropping the `postArtwork()` call fails
`the artwork phase updates the notification without re-promoting the service`.

**Two corrections to the original finding**, from reading the code rather than trusting the review:
the fetch goes through **Coil**, not a raw OkHttp call, and it catches `Throwable` and returns null
— so it never *threw* past the caller, it only **blocked**. The timeout figures stand, because Coil
is wired to the media OkHttp client (`ChronicleApplication.kt:84`): 5 s connect + 15 s read.

**Lint caught a real issue in my own new code** — which is only possible because the sibling finding
made lint fatal. The new `notify` needed `POST_NOTIFICATIONS` handling; the two existing calls
beside it are baselined as accepted debt, but rather than widen the baseline for new code I catch
`SecurityException`. A denied notification permission must not take playback down over a cover
image, and the notification the state machine already posted stays valid.

**Also fixed while here:** the jump-interval preference listener was building its notification
inside `serviceScope.launch { withContext(dispatchers.io) { … } }` purely because the build used to
suspend. It no longer does, so it runs inline beside `updateCustomActions()`.


## Related

- [[cu-89]] — Android Auto notification work touches the same builder
- [[cu-103]] — the media FGS permission; same foreground-service surface
