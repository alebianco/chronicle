---
id: DRAFT-121
title: A second device does not adopt the server position
status: Draft
assignee: []
created_date: '2026-09-02'
labels: [R1, sync, bug, trust]
dependencies: []
priority: high
milestone: m-1
---

## Description

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

### The one hard clue

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

- [ ] Device B adopts device A's position after opening the book, with both on the same server
- [ ] Determine which of the two possibilities above is true — `merge` not called, or called and
      losing — and say which in the fix
- [ ] A test that would have caught this: a track whose *network* copy has a greater
      `lastViewedAt` and a different `viewOffset` must end up with the network progress after
      `loadTracksForAudiobook`. The existing `merge` unit tests pass while this is broken, so the
      gap is above `merge`, in the repository path.
- [ ] Re-check the converse afterwards — local progress must still not be clobbered by a *stale*
      server value ([[cu-73]]'s next item). Fixing adoption naively could break that.
- [ ] Verified on two real devices, not one device plus a fixture

## Related

- [[cu-73]] — found here; four convergence items depend on this working
- [[cu-14]] — the sync-drift work this is the round trip for
- [[cu-90]] — position ownership; decision-16 is the governing rule
