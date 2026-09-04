---
id: cu-75
title: Model the IPv6 connection flag
status: To Do
assignee: []
created_date: '2026-08-31'
labels: [R2, architecture]
dependencies: [cu-11]
priority: low
milestone: m-2
---

> **Draft id note.** Filed as `DRAFT-75` so the Backlog.md drafts view can see it —
> the tool keys drafts on the `DRAFT-` id prefix, not the directory or the status field.
> On promotion it becomes a `cu-` task again. Existing references to **cu-75** mean this file.

## Description

Split out of [[cu-11]], which modelled `relay` and `protocol` but left `IPv6` on the floor.

`/api/v2/resources` reports `IPv6` per connection alongside `local` and `relay`
([Plexopedia](https://www.plexopedia.com/plex-media-server/api-plextv/resources/)). The
`Connection` model still drops it, so the app cannot tell an IPv6 route from an IPv4 one.

### Why this was not done in cu-11

**There is no failing case pointing at it.** cu-11 fixed `relay` because relay routes were
demonstrably being raced against LAN and losing users bandwidth. For IPv6 the argument is
speculative: it *might* matter on a network where the IPv6 literal is reachable and the IPv4
one is not, or where a broken IPv6 path hangs while IPv4 would have worked. Adding a fourth
tier — or a filter — on that basis would be guesswork, and the wrong guess makes connection
selection worse on the networks that currently work.

### What would justify picking it up

Any one of:

- A real network where the app fails to connect and a manual IPv6/IPv4 probe explains it.
- Evidence that a dead IPv6 route consumes a tier budget that IPv4 would have answered
  inside — visible as the LAN tier timing out while a LAN address is genuinely reachable.
- [[cu-73]]'s live pass reporting IPv6 connections in the real `/resources` response at all;
  if the household's server never advertises one, this stays closed.

### If it is picked up

The likely shape is a *filter*, not a tier: prefer IPv4 within each tier and fall back to
IPv6, rather than adding `LAN_IPV6`/`DIRECT_IPV6` tiers that would double the enum and
complicate the budget logic for no measured gain.

## Research, 2026-09-05 — the third trigger checked, and it says stay closed

The draft names three things that would justify picking this up. The cheapest to check is the
third — *"[[cu-73]]'s live pass reporting IPv6 connections in the real `/resources` response at all;
if the household's server never advertises one, this stays closed."*

**Checked against the live server. It never advertises one.**

Read out of the app's own response log on a real launch against **ANTARES**, rather than from a
fixture — the hand-written `resources.json` has `IPv6` absent entirely and `address` null, so it
could not have answered this (the cu-24 fixture trap):

| | |
|---|---|
| connections advertised | 3 |
| `"IPv6": true` | **0** |
| `"IPv6": false` | 3 |
| IPv6-literal addresses | **0** — all three are IPv4 (`192.`, `172.`, `87.`) |

So on the only network this app is judged against (principle 5, the owner's household), the flag is
constant-false and modelling it would change nothing. The other two triggers remain untested and
both need a *failing* network, which cannot be manufactured honestly.

**Left `To Do`, not closed.** The evidence is about one server at one point in time; a router or ISP
change could make it advertise IPv6 tomorrow, and the check above is cheap to repeat. What is
recorded is that the question was asked and answered *for now* — so the next person does not spend
the afternoon re-deriving it.

To re-check: launch a debug build against the real server and
`adb logcat -d | grep -oE '"IPv6":[a-z]+'`. Two lines, no credential handling.

## Acceptance Criteria

- [ ] `Connection` carries `iPv6`, parsed from the real response shape (check the JSON key
      casing against a live capture — the XML attribute is `IPv6`, and Moshi is
      case-sensitive)
- [ ] A concrete failing network documented before any preference logic is written
- [ ] Preference implemented as an intra-tier filter unless the evidence says otherwise
- [ ] `ConnectionChooserTest` extended; existing tier tests unchanged
