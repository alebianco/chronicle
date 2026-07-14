---
id: cu-15
title: Ride-along refactor: neutral domain + MediaSource seam + dispatchers
status: To Do
assignee: []
created_date: '2026-07-13'
labels: [R1, architecture]
dependencies: []
priority: high
milestone: m-1
---

## Description

Backend-neutral IDs/progress, repository interfaces, MediaSource scaffolding resurrected per decision-11 (Plex sole impl, capability flags), plus the coroutine-hygiene cluster this refactor is the natural home for: dispatcher injection (H5), GlobalScope removal (C4), InternalCoroutinesApi removal (C5), delicate-API suppression/justification (H6), and deleting the LocalMediaSource `TODO()` landmines as the seam is resurrected (C6). First test suite lands here.

Plans: [`C4`](../docs/analysis/C4-globalscope-removal-plan.md), [`C5`](../docs/analysis/C5-internal-coroutines-api-removal-plan.md), [`C6`](../docs/analysis/C6-localmediasource-decision-plan.md), [`H5`](../docs/analysis/H5-dispatcher-injection-plan.md), [`H6`](../docs/analysis/H6-delicate-api-usage-plan.md). Legacy ref: PRODUCT_BACKLOG R1 item 12 (dissolved 2026-07-13).

## Acceptance Criteria

- [ ] Domain models free of ratingKey/viewOffset/Injector leaks (backend-neutral id:String, progressMs)
- [ ] Repository interfaces extracted; MediaSource seam wired into DI with Plex as sole impl + capability flags
- [ ] No GlobalScope; InternalCoroutinesApi opt-ins removed; delicate-API uses justified with @OptIn + comment (H6)
- [ ] Dispatchers injected via a DispatcherProvider (H5), test dispatcher provider available
- [ ] LocalMediaSource dead `TODO()` stub removed (C6 resolved: resurrect the seam, drop the stub)
- [ ] Tests cover tasks 9-12
