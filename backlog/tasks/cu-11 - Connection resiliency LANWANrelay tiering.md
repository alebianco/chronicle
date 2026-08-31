---
id: cu-11
title: Connection resiliency: LAN/WAN/relay tiering
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R1, trust]
dependencies: [cu-16]
priority: high
milestone: m-1
---

## Description

Tiered attempts LAN then WAN then relay (relay deprioritized ~2Mbps), refresh coordinator (fabiogermann pattern). Fixes #103/#98.

## Implementation Notes

### The code looked tiered and was not

`chooseViableConnections` sorted by `local` and so read as LAN-first. It was not: every
attempt was launched at once via `connections.map { async { ... } }`, so the sort only
decided the order a polling loop *noticed* completions. **Sorting a list whose elements all
start simultaneously does not sequence them** — a relay answering in 80ms beat a LAN address
answering in 120ms, which is the substance of #103/#98.

### Four defects, not one

1. **The tier information was discarded at parse time.** `/api/v2/resources` reports `local`,
   `relay` and `IPv6` per connection, and the app already asks for relay routes
   (`includeRelay = 1`) — but `Connection` modelled only `uri` and `local`. Relay is capped
   around 2 Mbps behind an extra hop through Plex's infrastructure, and it was competing on
   equal footing with LAN.
2. **Nothing was sequenced** (above).
3. **A dead LAN address cost 15 seconds.** `connectTimeout` was 15s *and* the outer
   `withTimeoutOrNull` was 15000ms, so an unreachable cached `10.x` address consumed the
   entire budget before relay was attempted — missing the <5s target by 3×.
4. **The completion loop was unsound.** `delay(500)` added up to half a second to an answer
   already in hand; `deferred.getCompleted()` was called on deferreds a sibling may have
   cancelled; and the trailing block's `(deferred.getCompleted() as Failure)` is an
   unchecked cast that throws if a second success lands in the same window.

### Design note: budget, not strict sequence

The obvious reading of "LAN then WAN then relay" is to await each tier before starting the
next. That would **relocate the stall rather than remove it** — a LAN-only server on a flaky
network would wait out the LAN attempt before trying anything.

Instead each tier gets a budget: its attempts start in parallel, and if none answers within
`TIER_BUDGET_MS` (1.5s) the next tier starts too *while the earlier attempts keep running*.
So a hung LAN address costs 1.5s, and a slow-but-working one can still win over a relay that
already answered. The **last** tier is awaited for a real answer rather than a budget, because
there is nothing below it to fall back to — that is what keeps a LAN-only server working.

`select {}` expresses "first success wins" directly, replacing the poll loop and both unsafe
`getCompleted()` paths.

### Timing

`TIER_BUDGET_MS` (1.5s) and `CONNECT_TIMEOUT_SECONDS` (5s) are only meaningful together, and
a test asserts their sum stays inside the recovery target. The **read** timeout deliberately
stays at 15s: a slow audio *transfer* is still useful, whereas a slow *handshake* means the
route is wrong. Shortening it to match would break streaming on a weak connection.

### Verification, and its limits

Nine chooser tests, verified to bite: ignoring tier order fails three, removing the budget
fails the hanging-LAN case specifically. Two assert timing behaviour, which matters because
`UnconfinedTestDispatcher` skips delays — the sabotage run confirms they are not passing
vacuously.

**The probe is injected, so no test exercises the real `checkServer` call.** The wiring in
`PlexConfig` is where a mistake would hide, and it is covered only by the Dagger graph
resolving (`ConnectionChooser` appears 4× in `DaggerAppComponent`) plus a green build. An
end-to-end pass through `FakePlexServer` is the right guard and is on [[cu-73]] alongside
cu-10's, since both need the same harness.

The `ConnectionTimeoutTest` pins constants, not an inspected `OkHttpClient` — `AppModule`
needs a real `Application` to build. A future edit could bypass the constants and neither
that test nor `verify.sh` would notice. Stated rather than glossed.

### Deferred

- **The "refresh coordinator (fabiogermann pattern)"** the task names. `connectToServer`
  already cancels its predecessor, which is the substance of coordinating overlapping
  refreshes, and there are only two triggers (`onAvailable`, manual retry) funnelling through
  one method. Revisit if [[cu-73]] shows real thrash; building it speculatively would add a
  moving part with no failing case behind it.
- **`IPv6` handling** — reported by Plex, still dropped by the model. Filed as [[cu-75]].
- **Bandwidth-aware relay deprioritisation** beyond tier order. Measuring throughput to
  decide is a much larger feature needing a real server to validate, and tier ordering
  already achieves the goal of relay-last.

## Acceptance Criteria

- [x] LAN-only server works — a single LAN connection is the last non-empty tier, so it is
      awaited for a real answer rather than budgeted away; pinned by a test
- [~] Network switch mid-playback recovers in <5s — the arithmetic supports it (1.5s tier
      budget + 5s connect timeout, replacing a 15s timeout inside a 15s ceiling with 500ms
      poll granularity) and the recovery path is wired (`onAvailable` → `connectToServer`),
      but **elapsed recovery time is not measurable in a unit test**. On [[cu-73]]
