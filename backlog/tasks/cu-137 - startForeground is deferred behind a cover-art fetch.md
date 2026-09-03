---
id: cu-137
title: startForeground is deferred behind a cover-art fetch
status: To Do
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

- [ ] `startForeground` is reached without awaiting any network call, at all three call sites
- [ ] Artwork still appears in the notification once fetched, via a second `notify()`
- [ ] A test proves the deadline property: a `getBitmapFromServer` that never returns must not
      delay `startForeground` — sabotage-verified (restoring the await makes it fail)
- [ ] The nullable-`Notification` path into `startForeground` is either made non-null by
      construction or handled explicitly, not implicitly unwrapped
- [ ] Verified on a device: first play of a book with cold artwork over a slow/relay route posts
      the notification promptly and does not throw `ForegroundServiceDidNotStartInTimeException`

## Related

- [[cu-89]] — Android Auto notification work touches the same builder
- [[cu-103]] — the media FGS permission; same foreground-service surface
