---
id: cu-107
title: Persist the connection tier, not just the URI
status: To Do
assignee: []
created_date: '2026-09-02'
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

- [ ] `Connection` survives a `SharedPreferences` round trip with `local`, `relay` and `protocol`
      intact
- [ ] A test asserts the round trip explicitly, including a relay connection — the property that
      currently fails
- [ ] `ConnectionChooser` sees a `LAN` tier on a real server after a cold start, confirmed from
      the log (`Trying N LAN connection(s)`), not assumed
- [ ] Existing installs migrate without being signed out: a stored set of bare URIs must still
      load. Prefer re-deriving flags from the next `/resources` refresh over guessing from the
      URI shape
- [ ] The two identically-populated string-set keys are either partitioned properly or replaced by
      one serialized representation — not left as dead names that imply a distinction they do not
      make
- [ ] `PREFS_LOCAL_SERVER_CONNECTIONS_KEY` / `PREFS_REMOTE_SERVER_CONNECTIONS_KEY` are covered by
      whatever replaces them, so a stale key cannot resurrect the old shape

## Notes

Serializing `List<Connection>` with Moshi into a single string key is the obvious fix and matches
how the rest of the repo stores structured prefs. Watch two things: `Connection` is a
`@JsonClass(generateAdapter = true)` type so the adapter is free, and the read path must tolerate
the **old** bare-URI format so an existing install is not logged out on upgrade — a null/parse
failure here returns `null` from `server`, which reads as "no server chosen".

Whether a stored bare URI should be *guessed* as LAN from its `192-168-`/`10-` shape is a
deliberate decision, not an obvious yes: `plex.direct` hostnames encode the address, so it is
parseable, but a wrong guess re-introduces exactly the mis-tiering this task fixes. Re-deriving
from the next `/resources` call is safer and happens on every launch anyway
(`mergeServerRefresh`).

Related: [[cu-11]] (the tiering this restores), [[cu-73]] (the pass that found it),
[[draft-75]] (the `IPv6` flag, also discarded at parse time — the real response above confirms
`IPv6` is present and always `false` on this server, which is the datum draft-75 was waiting for).
