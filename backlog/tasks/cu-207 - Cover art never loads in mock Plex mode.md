---
id: cu-207
title: Cover art never loads in mock Plex mode
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R1
  - debug
  - bug
milestone: m-1
dependencies: []
priority: medium
---

## Description

In mock Plex mode **no book cover renders anywhere** — home shelves, library grid, the details
header, the mini player. Every cover is a blank square.

Found during cu-206's device verification and confirmed **pre-existing**: the baseline screenshots
captured from the XML build, before any of that task's changes, show exactly the same blank covers.
It is not a Compose regression.

## What is known

- **Coil makes no request at all.** `adb logcat | grep MockPlexServer` shows the fixture server
  receiving `/photo/:/transcode?...` only from `NotificationBuilder`, which uses
  `PlexConfig.getBitmapFromServer` (a plain OkHttp call) and **succeeds** — the log reads
  "Successfully retrieved album art". No request arrives from Coil.
- So the URL, the server and cleartext-to-loopback all work: `network_security_config.xml`'s debug
  override permits `127.0.0.1`, and the notification proves the exact same path is reachable.
- `ChronicleApplication` implements `SingletonImageLoader.Factory` and installs an
  `OkHttpNetworkFetcherFactory`, so an image loader *is* configured.

That narrows it to something between the composable's `model` and the loader — most likely the
`ImageLoader`'s OkHttp client not carrying the Plex interceptor (so the request is rejected or
never built), or an exception being swallowed inside Coil.

## Why it matters

Mock mode exists so UI can be seen and screenshotted without credentials (cu-16), and it is the
only way an agent can check a screen on a device. A mode where **every image is blank** silently
weakens every visual check made through it — including all of cu-206's. Any future task comparing
screenshots is comparing images with no artwork in them and may not notice.

## Acceptance Criteria

- [ ] The root cause is identified — why Coil issues no request when `PlexConfig`'s own OkHttp call
      to the same URL succeeds
- [ ] Covers render in mock mode on home, library, details and the mini player
- [ ] Whatever swallowed the failure logs it instead, so the next occurrence is visible
- [ ] Verified on the tablet with a screenshot showing real cover art
