---
id: cu-75
title: Model the IPv6 connection flag
status: Draft
assignee: []
created_date: '2026-08-31'
labels: [R2, architecture]
dependencies: [cu-11]
priority: low
---

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

## Acceptance Criteria

- [ ] `Connection` carries `iPv6`, parsed from the real response shape (check the JSON key
      casing against a live capture — the XML attribute is `IPv6`, and Moshi is
      case-sensitive)
- [ ] A concrete failing network documented before any preference logic is written
- [ ] Preference implemented as an intra-tier filter unless the evidence says otherwise
- [ ] `ConnectionChooserTest` extended; existing tier tests unchanged
