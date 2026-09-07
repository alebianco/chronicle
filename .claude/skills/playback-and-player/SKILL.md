---
name: playback-and-player
description: Use when touching MediaPlayerService, MediaServiceConnection, ProgressUpdater, ExoPlayer/Media3, the sleep timer, playback speed, offsets and seeking, downloads/caching, or Android Auto. Covers progress flushing, offset frames, and per-second work costs.
---

# Playback and the player

Playback goes through `MediaServiceConnection` / `MediaPlayerService` — **never touch ExoPlayer
from UI**.

## Progress must be flushed on every pause path

`ProgressUpdater.startRegularProgressUpdates` is gated on `isPlaying`, so pausing silently ends the
per-second write: without an explicit flush the saved position is whatever the previous tick
captured, and no `PLEX_STATE_PAUSED` ever reaches the server.

Three paths flush today — `flushOutgoingBookProgress` on a book switch, `onSeekTo` on a
seek, and `onPause`. **A fourth pause route added later needs its own.** Same defect class
as advplyr/audiobookshelf-app#1847 and PaulWoitaschek/Voice#3351.

**Read the position from the player, never from the session.** `MediaSessionCompat`'s playback
state lags a frame, so `updateProgressWithoutParameters` on a pause or seek path reports the
*pre-action* position as still PLAYING — **worse than not flushing**, since it overwrites a good
position with a stale one. `PauseFlushesProgressTest` seeds a deliberately stale session position so
the regression fails loudly instead of passing by luck.

## Listening position is owned by the tracks, never the book (decision-16)

Plex stores no album-level `viewOffset` — only per-track — so `Audiobook.progress` is a **cache of
a derivation**.

- `merge` carries the local value and **never** adopts `network.progress`; only `syncAudiobook`,
  where the tracks are loaded, writes a fresh one.
- `getActiveTrack` is the **furthest started** track (`progress > 0` only). Using
  `max(lastViewedAt)` made position jump backwards between devices, and counting a timestamp as
  "started" made a book marked-as-read report itself half finished, because
  `markTracksInBookAsWatched` stamps every track.
- **Completion is a separate explicit fact** (`viewCount`), never inferred from position.

## An offset carries its frame in its type

`BookOffset`, `TrackOffset` and `TrackIndex` (`data/model/Offsets.kt`) are `@JvmInline` value
classes, so a book-frame value passed where a track-frame one belongs **fails to compile**.

Six bugs came from that mistake as plain `Long`s, and prose did not stop it:
`Chapter.bookStartTimeOffset` was *renamed to say the frame*
and carries a KDoc explaining it, and the frame was still guessed wrong twice afterwards. On a
single-track book — most of this library — the two are the **same number**, so every one of them
worked by accident.

- **One conversion, one home.** `inTrackOffsetOf` (in `ChapterSeekTarget.kt`) is the only
  book → track conversion; `chapterSeekTarget` delegates to it. Three sites used to inline
  `tracks.takeWhile { it.id != trackId }.sumOf { it.duration }`, which **sums every track when the
  id is absent** instead of reporting that it could not resolve one. Don't write a fourth.
- `getProgress()` is the canonical track → book sum and returns a `BookOffset`.
- **`TrackIndex` means "index into the *sorted* list"** — the order the player's playlist is built
  in, which is what `seekTo`'s `mediaItemIndex` addresses. `getActiveTrack()` sorts internally, and
  its result used to be looked up in the unsorted list; that agreed only because both callers
  happened to pass a DAO-ordered one.
- Room stores plain `INTEGER` via `OffsetConverters`, so **no migration**.
- `Audiobook.progress` and `ProgressUpdater` stay `Long` on purpose — they already keep the two
  frames as separate named locals.

## Per-book playback speed

`Audiobook.playbackSpeed` is `NO_SPEED_OVERRIDE` (`0f`) when the book follows the global
preference, and `effectiveSpeed(global)` is the **only** reader. `MIN_VALID_SPEED` is pinned equal
to the slider's floor by a test — if it drifted below, a legitimately chosen speed would read as
"no override".

`MediaPlayerService.invalidatePlaybackParams()` is the single writer of `PlaybackParameters`. It
collects `currentlyPlaying.book` mapped to `id to playbackSpeed` with `distinctUntilChanged`,
because `ProgressUpdater` republishes the book **once a second** during playback.

**The DB write alone does not propagate**: `ProgressUpdater`'s tick is gated on `isPlaying`, so a
change made **while paused** needs `CurrentlyPlaying.updateSpeedOverride` to reach the player.

Remember the merge rule — `playbackSpeed` is a local-only column and must be named in **both** arms
of `Audiobook.merge` (see the `room-and-persistence` skill).

## The sleep timer

**`ACTION_SLEEP_TIMER_CHANGE` is bidirectional, and the service must not answer itself.** Commands
travel *into* the timer on that action and its ticks travel *out* on the same one, so a service
that handles every broadcast it hears feeds the timer its own output. That was invisible while
`SleepTimer.update` reassigned a Long to itself; once the state carried a **mode**, the loop
rewrote an end-of-chapter timer as a zero-length countdown that expired on the next tick. The
service filters `SleepTimerAction.UPDATE`, which is **outbound-only**.

**Expiry and cancellation are different facts.** `cancel()` forgets the duration; `expire()` keeps
it in `SleepTimerState.Expired` so `onPlaybackResumed` can re-arm — to
`FixedDuration.originalMillis`, **not** the remaining time, because a timer always expires with
almost none left (a first cut restored a one-second timer). An expired timer keeps ticking on
purpose: that is how it notices playback resuming.

**End-of-chapter carries no deadline** — it stores the chapter id and compares each tick, so a seek
or a speed change cannot desync it, which a computed `(chapterDuration - chapterProgress) / speed`
countdown did both ways.

Decisions live in `SleepTimerLogic` (pure, no Android types); `SimpleSleepTimer` owns the state and
plumbing. **`isTicking` is tracked separately from the state**: `BEGIN` is `update(duration)` then
`start(true)`, and `update` already leaves the state `Running`, so a guard that asks the state
whether it is active makes every `BEGIN` a silent no-op.

## Embedded cover art is never decoded

An audiobook is one very large file with a single APIC/`covr` frame, and ExoPlayer's default copies
it into a heap byte array per media item — upstream's `OutOfMemoryError` in
`MediaMetadata.maybeSetArtworkData` (mattttvaughn/chronicle#83, #16, both still open).

`artworkFreeExtractorsFactory` disables it for mp3 and mp4; artwork comes from Plex via
`getBitmapFromServer(book.thumb)`.

It is a **top-level function, not a member of `ServiceModule`**, because the provider needs a live
`Service` and cannot be reached from a unit test at all — a test that rebuilt the same flags would
pass while the player was built with different ones. `EmbeddedArtworkTest` runs a real MP3 with a
real PNG cover through the production function, plus a second test asserting the fixture still
carries a frame under stock flags so the first cannot pass vacuously.

## Per-second work is expensive

`ProgressUpdater` writes once a second during playback and **Room invalidates per table**, so every
query on `Audiobook` or `MediaItemTrack` re-emits at tick rate.

The measured damage was not computation but **re-rendering**: 1405 `View.measure` calls in 20 s,
88% janky frames, dropped taps. Four causes, all the same shape — a constraint-graph rebuild for a
constant aspect ratio, a slider refresh for an invisible sheet, a DB read to resolve a track that
had not changed, and an image reload for identical artwork.

- Guard on **visibility** (`isShown`) and on **value changed**.
- A `RecyclerView` row legitimately rebinds every second, because the playing book's `progress` is
  in `areContentsTheSame` — so a rebind must be cheap.
- **Profile, do not read.** Four rounds of inspection produced plausible wrong answers;
  `am profile start --sampling` named it at once.
- **A performance fix verified against the easy fixture is not verified.** The
  single-track, 3-chapter fixture showed 1 jiffy/6 s and looked fixed; the 3-track, 8-chapter one
  put it back to 431 jiffies/12 s and exposed the real dominant cause. Measure against the **worst
  realistic input**.

## Ask synchronous state, not a published mirror

`MediaServiceConnection.connectIfIdle` once tested `isConnected.value`, which `onConnected`
published with `postValue` while clearing `isConnecting` immediately — so both read idle while the
browser was CONNECTED, and `MediaBrowserCompat.connect()` **throws** rather than ignoring a
redundant call.

`postValue` is gone tree-wide and `PostValueUsageTest` fails the build on a new one, so
that exact shape cannot return. The general lesson survives: **ask the collaborator's own
synchronous state**. `connectIfIdle` tests `mediaBrowser.isConnected` for that reason — the
browser's state also moves *during* `connect()`, before any callback of ours runs.

## Downloads and caching

- **A downloaded track's URI needs its `file://` scheme.** `"/path/x.mp3".toUri()` gives
  `scheme = null` and ExoPlayer will not treat it as a local file — it surfaces as an
  unsupported-format error **on downloaded books only**. Use `Uri.fromFile`, never
  `"file://" + path`, which skips percent-encoding.
- **A cache scan that cannot read its directory must change nothing.** `listFiles()`
  returns null for a missing or unreadable directory, and coalescing that to an empty list
  un-cached whole libraries. `cachedMediaDir` also returns the *stored* path even when unmounted,
  so an absent SD card reads as unavailable rather than silently resolving to a different,
  readable directory.
- **Changing the sync location does not strand partials.** Fetch2 downloads **in place**
  and resumes over HTTP Range, so a partial is named `<trackId>.<ext>` exactly like a finished
  file — there is no `.part`/`.tmp` suffix. `MoveSyncLocationWorker` selects with
  `MediaItemTrack.cachedFilePattern` and moves both. Correct, but load-bearing: **give partials a
  distinguishing suffix and they start being orphaned**, because the prune only scans the
  *active* `cachedMediaDir`. `SyncLocationMoveTest` pins it. Verified on two real volumes in both
  directions — worth doing both, since `Files.move` may fall back to copy+delete across
  filesystems.

## Android Auto

**Testable without a car, but not through Gradle Managed Devices.** AGP refuses:
*"TV and Auto devices are presently not supported with Gradle Managed Devices."* The config
resolves and the task is generated, then `<device>Setup` fails — **do not add an
`android-automotive` `systemImageSource`.** A manual AVD works and boots in ~10s (route in the
`chronicle-auto-emulator` memory). `getprop ro.build.characteristics` must read `automotive`.

`AutoBrowseTreeTest` binds a real `MediaBrowserCompat` and walks the tree — the only way to reach
`onGetRoot`/`onLoadChildren`, which need a bound service and a real `Result`.

**Build and drive the browser on the main thread**: its constructor creates a `Handler`, so
building it on the instrumentation thread throws *"Can't create handler inside thread…"*, and a
`subscribe` from there delivers to a looper that never runs.

It found a live crash on its first run — `mediaController.metadata` is `@Nullable` but
platform-typed to Kotlin, so `.id` on it compiled and killed the process in `onDestroy` for any
client that bound and released without playing, **which is what Auto does when it browses**.

**Mock mode seeds the login, not a refresh**, so a freshly-provisioned emulator browses an empty
library. That is correct — a test asserting books exist is testing provisioning.

Android Auto stays **outside Compose permanently**: the car host draws it from `MediaBrowser` items.
