---
id: cu-121
title: Position adoption verified working, not a bug
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-03'
labels: [R1, sync, verified-not-a-bug]
dependencies: []
priority: high
milestone: m-1
---

## Description

> **⚠️ FULLY RETRACTED, 2026-09-03. There is no bug. Adoption works in both directions, verified
> on two real devices.** This file is kept for the method lesson, not the defect.
>
> **The re-test, once the phone was logged back in and its stale certificate cleared:**
>
> | step | tablet | phone |
> |---|---|---|
> | before | `286557` (newer ts) | `14768` |
> | phone opens the book | — | **`284551`** — matches the server's `viewOffset` exactly |
> | on screen | — | **`04:44/11:11:47`** |
>
> `Integrating network track` **fired** — the network-wins branch of `merge`, the very line whose
> absence originally looked like proof of a bug. It was absent before because the phone's local row
> was genuinely newer, so taking the `else` branch was correct.
>
> **The converse also holds.** With the phone then played to `318426` (newer than the tablet's
> `286557`), re-opening the book left it at `318426` — local progress is not clobbered by a stale
> server value.
>
> So both halves of the cu-14 round trip are correct, and the two cu-73 items that depend on them
> are verified rather than blocked.
>
> ---
>
> **Original retraction note (the measurement error), kept because the lesson recurs:**
>
> The claim "the phone's stored position stayed at 189 ms" was read with
> `sqlite3` against a **copy of the main database file only**, without its `-wal`. Room runs in WAL
> mode, so recent writes live in the write-ahead log and are invisible to that read. Re-reading the
> same device *with* the WAL showed **14768 ms**, with a `lastViewedAt` **newer** than the tablet's
> — i.e. the phone's own playback, not a failed adoption.
>
> `MediaItemTrack.merge` was then unit-tested against the exact live numbers
> (`viewOffset 236955` / `lastViewedAt 1788384988` vs local `189` / `1788384769136`) and **behaves
> correctly**: it adopts the newer network value, refuses a stale one, honours `forceUseNetwork`,
> and keeps the local `cached` flag. `plexTimestampToMillis` converts seconds to millis correctly,
> so the comparison is sound. Five tests, in `PositionAdoptionTest`, all passing against unmodified
> production code.
>
> `loadTracksForAudiobook`, `syncTracksInBook` and `mergeNetworkTracks` were also read line by line
> and each merges and persists correctly.
>
> **A clean re-test could not be completed**, because the phone is no longer a valid test device:
> the password change killed its account token, and it still holds the *pre-restart* `plex.direct`
> certificate hash (`32080aae…` vs the server's current `d8f64ea2…`), so it shows
> "Can't connect to server" — the same TLS mismatch as [[DRAFT-125]]. It must be re-logged-in before
> any two-device test means anything.
>
> **What remains genuinely unknown:** whether adoption works end to end on a device that *has not*
> played the book itself. The original run cannot answer it either way. The lesson worth keeping is
> the method one, below.
>
> **Method rule for anyone re-testing:** always pull `track_db`, `track_db-wal` **and**
> `track_db-shm` together, or use `run-as … sqlite3` on the device (which opens the WAL). A
> main-file-only read silently reports stale data and will fabricate a bug.

### Original report (kept for the network evidence, which stands)

Found during the cu-73 live pass (session 4), with **two real devices against ANTARES**: a
Phh-Treble tablet (API 32) and a Galaxy A33 (Android 16 / API 36), both on the LAN, both on the
current debug build.

**Device B does not adopt device A's listening position, even though the server hands it the
correct value.** This is the core cu-14 round trip and the north-star "zero interventions" case —
it is the reason to have a server at all.

### Measured, end to end

Book **Ender's Game** (`151444`, 107 tracks), track `151445`. Both devices started at 0.

1. Tablet played to **244973 ms** (4:05) and reported it:
   `GET /:/timeline?ratingKey=151445&time=236955&…&state=playing` → **`200 OK`**, and the server
   echoed `{"MediaContainer":{…,"viewOffset":166771}}` on the way up. Write side is fine.
2. Phone opened the **book details screen** (the path that calls `syncAudiobook`; see the
   method note below) and fetched
   `GET /library/metadata/151444/children` → **`200 OK`**.
3. That response contained, for the right track:

   ```json
   {"ratingKey":"151445","parentRatingKey":"151444","title":"Ender's Game - Chapter 01",
    "index":1,"viewOffset":236955,"lastViewedAt":1788384988, …}
   ```

4. **The phone's stored position stayed at 189 ms.** `book|189|0`,
   `track|151445|1|189|1788384769136`. Unchanged, before and after.

So the phone received `viewOffset: 236955` with a newer `lastViewedAt` and kept its own 189 ms.

### Where it is not

Ruled out during the session, each by measurement rather than reading:

- **Not the write path.** The tablet's report reached the server, `200 OK`, and the server echoed
  the offset back.
- **Not the LAN/WAN routing.** The phone initially sat on the WAN tier because it lacked the
  Private DNS workaround (the router's `homenet.telecomitalia.it` search domain resolves
  `*.plex.direct` to `127.0.0.1` — session 3's hijack, reproduced on a second device). After
  setting `private_dns_mode=hostname` / `one.one.one.one` the phone chose
  `Chose LAN connection: https://192-168-1-54.….plex.direct:32400` — **and the position still was
  not adopted.** Same failure on both tiers.
- **Not a units bug in the timestamp.** `plexTimestampToMillis` correctly scales the server's
  seconds (`1788384988`) to ms (`1788384988000`), which *is* greater than the local
  `1788384769136`. So `merge`'s `network.lastViewedAt > local.lastViewedAt` should be **true** and
  should have taken the network copy. (I initially suspected a seconds/ms mismatch here and was
  wrong — the conversion is right, which makes the behaviour stranger, not simpler.)

### The one hard clue — also explained by the retraction

*(This was the strongest evidence for a bug. It is consistent with there being none: if the phone's
local row was already newer, `merge` correctly takes the `else` branch and the "Integrating" line
correctly never fires.)*

**`Timber.i("Integrating network track: …")` never fires** — 0 occurrences in a full captured
phone log across the whole details-screen load. That line sits in `MediaItemTrack.merge`'s
*network-wins* branch. So either:

- `merge` is being called and taking the `else` branch despite the comparison favouring network
  (i.e. the values reaching it are not the ones in the JSON), or
- **`merge` is never called on this path at all**, and the fetched tracks are discarded or written
  without merging.

`BookRepository.syncAudiobook` takes `tracks` as a *parameter* and does chapters plus the derived
book progress; it does not fetch or merge tracks. The merge belongs to
`TrackRepository.loadTracksForAudiobook(bookId, forceUseNetwork = false)`, which
`AudiobookDetailsViewModel:301` calls. **Start there**, and check whether the local row wins on a
path where `forceUseNetwork` is false.

Note `AudiobookDetailsViewModel:629` calls `syncAudiobook(audiobook, updatedTracks, true)` — a
`forceUseNetwork = true` variant exists on the explicit-refresh path. If that one works and the
open path does not, the fix is likely about which of the two the ordinary "open the book" flow
takes.

### Method note, because it cost time and will again

`--el play_book <id>` **does not exercise this**. It goes through `playFromMediaId` /
`AudiobookMediaSessionCallback` and never opens the details screen, so no `/children` request is
made at all — the phone's whole network activity on that path was `/identity`,
`/api/v2/resources` and a `/:/timeline` **write**. My first two runs "failed" for that reason and
were not evidence of anything. **Tap the book in the UI** (or otherwise reach
`AudiobookDetailsViewModel`) when testing sync adoption.

## Acceptance Criteria

- [ ] **Re-test first, on a device that has not played the book**, with both devices logged in and
      the phone's stale certificate cleared. The original run does not establish a bug.
- [ ] Device B adopts device A's position after opening the book, with both on the same server
- [x] A test that would have caught this: a track whose *network* copy has a greater
      `lastViewedAt` and a different `viewOffset` must end up with the network progress.
      **Added: `PositionAdoptionTest`, 5 tests, using the exact live numbers. They pass against
      unmodified production code**, which is itself the evidence that `merge` is not the fault.
- [ ] Re-check the converse afterwards — local progress must still not be clobbered by a *stale*
      server value ([[cu-73]]'s next item). Fixing adoption naively could break that.
- [ ] Verified on two real devices, not one device plus a fixture

## Related

- [[cu-73]] — found here; four convergence items depend on this working
- [[cu-14]] — the sync-drift work this is the round trip for
- [[cu-90]] — position ownership; decision-16 is the governing rule


## A follow-on observation — RESOLVED 2026-09-03, it was a test artefact

> **Settled by re-running the setup with real playback.** Reports from genuine listening come back
> `"playbackState":"progress"` with real `viewOffset` values echoed — 7 of them. The `"ignore"`
> responses below came from the artificial fixture: progress written straight into Room, playback
> started via `play_book`, so Plex had no session to attribute the report to.
>
> **Neither Plex nor Chronicle was misbehaving.** The suspicion was correct and filing it as a bug
> would have been wrong. The rule this leaves behind: **do not build sync fixtures by editing the
> database.** Plex only records progress for playback it believes it is serving, so a hand-made
> position produces reports it silently discards — and the app then looks broken when it is not.

### The original observation, kept for the trail

While testing the [[cu-90]] convergence items on the same rig, a **second, different** behaviour
turned up and is recorded here rather than filed as a defect, because it is not yet understood.

Setup: the tablet's track 3 (`151447`) progress was set directly in the database and then played
via `--el play_book`, so it reported `time=37773` for that track. The server answered **`200`** —
but with:

```json
{"MediaContainer":{"size":0,"playbackState":"ignore"}}
```

no `viewOffset` echoed. On the phone, `/children` then returned **no `viewOffset`, `lastViewedAt`
or `viewCount`** for `151447` at all, and none for the book `151444` either. Re-fetching a minute
later gave the same, so it is not a write-settling race, and `merge` correctly did nothing.

**What was ruled out:** `playQueueItemId=-1` is *not* the discriminator — the accepted reports from
session 4 sent the same placeholder, and 28 of them echoed a `viewOffset`. A `playQueues` call was
made in both cases.

**The likeliest remaining explanation**, untested: the test setup is artificial. Progress was
written straight into Room and playback started with `play_book`, so Plex may be declining to
attribute a report it has no corresponding session for. If so this is an artefact of how the
fixture was built, not something a real listener would hit — which is exactly why it is **not**
being filed as a bug on that evidence.

**How to settle it:** repeat the cu-90 setup by *actually listening* on the tablet into a later
track through the UI, with no direct database editing, and see whether the report is accepted.
That is a five-minute check with both devices to hand and would either close the two cu-90 items or
produce a real, well-founded defect report.

The first-track round trip — which used ordinary playback — works in both directions and is
verified above; nothing here casts doubt on that.
