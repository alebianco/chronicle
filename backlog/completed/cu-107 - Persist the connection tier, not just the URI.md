---
id: cu-107
title: Persist the connection tier, not just the URI
status: Done
assignee: []
created_date: '2026-09-02'
updated_date: '2026-09-02 09:30'
labels:
  - R1
  - trust
  - bug
dependencies: []
priority: high
milestone: m-1
ordinal: 4500
---

## Description

Found by the [[cu-73]] live pass on 2026-09-02, session 2. **cu-11's connection tiering is inert
on every launch after the first**, because the `local` and `relay` flags are destroyed when the
chosen server is written to `SharedPreferences`.

### Evidence from the device

The server's real `/api/v2/resources` reports the flags correctly:

```json
"connections":[
  {"protocol":"https","address":"192.168.1.54","uri":"https://192-168-1-54.<hash>.plex.direct:32400","local":true,"relay":false,"IPv6":false},
  {"protocol":"https","address":"87.17.202.231","uri":"https://87-17-202-231.<hash>.plex.direct:32400","local":false,"relay":false,"IPv6":false}
]
```

But the chooser saw no LAN tier at all:

```
ConnectionChooser$choose: Trying 2 DIRECT connection(s)
ConnectionChooser$choose: Connection failed: https://192-168-1-54.<hash>.plex.direct:32400
ConnectionChooser$choose: Chose DIRECT connection: https://87-17-202-231.<hash>.plex.direct:32400
```

Two connections, both `DIRECT`, when one of them is `local: true`.

### Mechanism

`SharedPreferencesPlexPrefsRepo` stores connections as bare URI strings in two string sets, and
both halves of the round trip lose the flags.

**On write** — `putConnections` puts the *same complete list* into both keys. They are named
LOCAL and REMOTE but hold identical contents, so the partition never happens:

```kotlin
private fun putConnections(connections: List<Connection>) {
  prefs.edit()
    .putStringSet(PREFS_LOCAL_SERVER_CONNECTIONS_KEY,
      connections.map { connection -> connection.uri }.toSet())
    .putStringSet(PREFS_REMOTE_SERVER_CONNECTIONS_KEY,
      connections.map { connection -> connection.uri }.toSet())   // same expression
    .commit()
}
```

**On read** — `getServerConnections` unions the two sets and rebuilds each entry with the
single-argument constructor, so `local` and `relay` both take their `false` defaults:

```kotlin
val combinedList = (localServers union remoteServers).toList()
return combinedList.map { Connection(it) }   // local = false, relay = false
```

`Connection.tier` then classifies everything as `DIRECT`, and `ConnectionChooser` sees exactly one
tier. The `union` is also a no-op, since both sets are identical by construction.

### Consequences

- **A LAN address gets no preference.** It is raced against WAN in one tier rather than tried
  first, so which route wins is a matter of whichever handshake completes first.
- **A relay route gets no penalty.** `relay` reads as `false` for every stored connection, so the
  cap cu-11 exists to avoid is invisible — the "relay no longer wins races it should not be in"
  property does not hold across a restart.
- **The last-tier rule loses its meaning.** `ConnectionChooser` awaits a real answer on the last
  tier so a LAN-only server is never given up on. With one synthetic tier that rule now applies to
  the WAN address too.
- It is silent. Nothing logs that flags were dropped, and the app still connects — just possibly
  by the wrong route.

Note the tiering logic itself is **correct**; `ConnectionTier`, `Connection.tier` and
`ConnectionChooser` need no change. Only the persistence layer is wrong, which is why cu-11's unit
tests all pass: they construct `Connection` objects directly and never round-trip through prefs.

## Acceptance Criteria

- [x] `Connection` survives a `SharedPreferences` round trip with `local`, `relay` and `protocol`
      intact
- [x] A test asserts the round trip explicitly, including a relay connection — the property that
      currently fails
- [x] `ConnectionChooser` sees a `LAN` tier on a real server after a cold start, confirmed from
      the log (`Trying N LAN connection(s)`), not assumed
- [x] Existing installs migrate without being signed out: a stored set of bare URIs must still
      load. Prefer re-deriving flags from the next `/resources` refresh over guessing from the
      URI shape
- [x] The two identically-populated string-set keys are either partitioned properly or replaced by
      one serialized representation — not left as dead names that imply a distinction they do not
      make
- [x] `PREFS_LOCAL_SERVER_CONNECTIONS_KEY` / `PREFS_REMOTE_SERVER_CONNECTIONS_KEY` are covered by
      whatever replaces them, so a stale key cannot resurrect the old shape

## Implementation Notes

Fixed 2026-09-02, verified on the same tablet that found it.

### The change

`Connection` is now serialized whole into a single `server_connections_v2` key with Moshi, which
matches how this class already stores `PlexUser`. The two legacy keys are **read for migration and
then removed on the next write**, so a stale copy cannot resurrect the flagless shape after a
downgrade-and-upgrade.

`mergeServerRefresh` now dedupes with `distinctBy { it.uri }` rather than `distinct()` — see below.

### The migration, and why it does not guess

A legacy install still loads: `getServerConnections` falls back to the old keys and rebuilds bare
URIs with `local`/`relay` false, i.e. **exactly the old behaviour**, corrected on the next
`/resources` refresh. The flags are *not* inferred from the `192-168-`/`10-` shape of a
`plex.direct` hostname even though they could be — a wrong guess would re-introduce the very
mis-tiering this task removes, and the refresh happens on every launch anyway.

This mattered more than it looks: an empty connection list makes `server` read back as **null**,
which presents as "no server chosen" and sends the user through the chooser again. A fix that
logged everyone out on upgrade would have been worse than the bug. Three tests cover that path,
and all three fail if the fallback is removed.

An empty *stored* list (`[]`) deliberately does **not** fall back — that is a successful parse
meaning "this server has no connections", a different fact from "nothing stored yet", and falling
back would resurrect connections a caller had replaced with none.

### A second defect, found while verifying the first

On the launch right after the migration the log read `Trying 3 DIRECT connection(s)` for a server
with **two** connections, and the LAN address was probed twice per selection round.

`mergeServerRefresh` deduped with `distinct()`, which compares whole `Connection` objects. The
cached copy carried no flags (it had just come from the legacy keys) while the fetched copy carried
the real ones, so the two were unequal and both survived — putting one address in two tiers. Now
deduped by URI, with fetched-first ordering making the refresh authoritative about a connection's
tier. That ordering was already there for freshness; it now does double duty.

Benign in effect (a redundant probe, and the right tier still won) but real waste, and it would
have misled anyone reading the log later.

### Device verification

Three consecutive launches on the tablet, against the live server:

1. **First launch, legacy data** — `Loaded 2 connection(s) from the pre-cu-107 keys`, account not
   signed out, app connected. Still one `DIRECT` tier, correctly, since legacy flags are
   unrecoverable.
2. **Second launch** — `Trying 1 LAN connection(s)` **and** `Trying 3 DIRECT connection(s)`. The
   LAN tier exists again; the 3 is the duplicate described above.
3. **Third launch, after the dedupe fix** — `Trying 1 LAN connection(s)` and `Trying 1 DIRECT
   connection(s)`, matching the server's two connections exactly. The stale duplicate
   self-healed, since the deduped list is what gets written back.

`Chose DIRECT` is still the outcome **on this network only**, because
`192-168-1-54.<hash>.plex.direct` does not resolve here — confirmed independently with `dig`, and
recorded in [[cu-73]] session 2. The tiering is now correct and observable; the LAN *probe* needs a
network whose resolver returns private answers for `plex.direct`. That half of cu-11 remains
unverified end-to-end for that reason, and is still open on cu-73.

### Note for future work

The real `SharedPreferencesPlexPrefsRepo` had **no test at all** before this — every other test
injects the in-memory `FakePlexPrefsRepo`. That is precisely why cu-11's tiering could be inert
while all of cu-11's own tests passed: they construct `Connection` objects directly and never
cross the persistence boundary. `PlexPrefsConnectionRoundTripTest` now exercises the real class
against a real `SharedPreferences`; **the same blind spot may cover `user`, `library` and the
token accessors**, which are still only tested through the fake.

Coverage 27.84% -> 28.16%.
