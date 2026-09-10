---
id: cu-207
title: Cover art never loads in mock Plex mode
status: In Review
assignee:
  - '@claude'
created_date: '2026-09-07'
updated_date: '2026-09-10 06:57'
labels:
  - R1
  - debug
  - bug
milestone: m-2
dependencies: []
priority: medium
ordinal: 102000
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

## Implementation Notes

### The filed premise was wrong

This task said "Coil makes no request at all". It does — I had grepped the device log for `/photo/`,
which is the shape `PlexConfig.getBitmapFromServer` builds for the *notification*, and concluded
from its absence that nothing was asked for. The screens ask for the raw `thumb` path instead,
through `PlexConfig.toServerString(book.thumb)`, and those requests were in the log the whole time
under a different shape.

**Two independent bugs, and both had to go:**

1. **The mock server had no route for a raw thumb path.** `MockPlexServer` matched `/photo/` only,
   so `/library/metadata/{id}/thumb/{version}` fell through to `fixtureFor`, which knows nothing
   about images and returned an **empty 200** — a zero-byte body Coil cannot decode, with no error
   anywhere. That is why the notification's artwork worked while every cover in the UI was blank.
   A 404 would have been one glance in the log; a 200 with no body reads as a served request.

2. **No Compose call site declared a placeholder.** The View-era `bindImageRounded` set
   `placeholder(...)` *and* `error(...)`; the migration replaced it with six bare `AsyncImage`
   calls that set neither. So a cover that failed to load rendered as **nothing** — not a broken
   image, a hole. This half is a production bug, not a mock-mode one: a real server that is slow,
   offline, or holds a book with no artwork produced the same hole.

### What changed

- `MockPlexServer` routes `/library/metadata/{id}/(thumb|art)/` to the existing `imageResponse()`.
  Matched by **shape**, not by an id list — the version segment changes whenever Plex regenerates
  the image.
- `views/compose/CoverImage.kt` is the single artwork composable. All six call sites use it.
  `CoverImageTest` fails the build on a bare `AsyncImage` anywhere else, sabotage-verified.
- The model decision is a pure function (`coverModel`) with its own tests, because `AsyncImage` is
  asynchronous and Robolectric has no network — a rendering assertion cannot distinguish "asked for
  the right url" from "asked for nothing".

### The crash the unit tests could not see

The first cut used `painterResource(R.drawable.book_cover_missing_placeholder)`. That drawable is a
`<shape>`, and `painterResource` **throws** for one: *"Only VectorDrawables and rasterized asset
types are supported"*. It killed the app on the first frame rendering a coverless book, while every
unit test passed — they assert on source text, not on rendering.

The placeholder is a `Box` background in the same colour now, which cannot fail that way. A test
pins that specifically, with the crash as its stated reason.

### One criterion retired rather than met

"Whatever swallowed the failure logs it instead" assumed something caught an exception and
discarded it. Nothing did: an empty 200 is a *successful* response that decodes to no image, so
there was no failure to log. Coil's `onError` never fired. The durable fix is the placeholder —
an absent cover is now visible as an absent cover, which is the outcome that criterion wanted.

### Not verified

The **production** path still has no on-device check: mock mode is the only server reachable
without the owner's credentials, and the placeholder's real-server behaviour (a slow load, a book
with no artwork on the household's 196-book library) is unexercised. The logic is shared with the
mock path and unit-tested, but that is not the same as having seen it.

## Acceptance Criteria

- [x] The root cause is identified — see below. **The filed premise was wrong**: Coil *was*
      requesting, and the request was answered with an empty 200.
- [x] Covers render in mock mode on home and the mini player, verified by screenshot
- [x] A production placeholder covers all three absences, so a missing cover is never a hole
- [ ] **The swallowed-failure criterion is retired, not met** — see below
