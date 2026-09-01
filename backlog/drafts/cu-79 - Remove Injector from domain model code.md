---
id: cu-79
title: Remove Injector from domain/model code
status: Draft
labels: [R2, architecture, debt]
dependencies: [cu-71]
priority: medium
milestone: m-2
---

## Description

Split out of [[cu-71]], where it was an acceptance criterion that turned out to be
independent of the id retype and was deliberately left undone rather than bundled.

Three sites in the model layer reach into the DI graph from a data class, which makes
those models untestable without standing up `ChronicleApplication`:

- `data/model/Collection.kt:65` — `Injector.get().moshi()` inside `CollectionIdConverter`
- `data/model/MediaItemTrack.kt:130` — `Injector.get().prefsRepo().cachedMediaDir`
- `data/model/MediaItemTrack.kt:132` — `Injector.get().plexConfig().toServerString(media)`

The `plexConfig()` call is the worst of the three: a *domain* model reaching into a
*Plex-specific* config, which is precisely the coupling the `MediaSource` seam
([[cu-15]], decision-11) exists to remove.

## Acceptance Criteria

- [ ] No `Injector.` references remain under `data/model/`
- [ ] Room `@TypeConverter`s receive their Moshi adapter by construction (Room supports
      converter instances via `addTypeConverter`), not by service lookup
- [ ] `MediaItemTrack`'s path/URL helpers take what they need as parameters, or move to a
      repository — a data class should not know about `PlexConfig`
- [ ] Tests for the touched models construct them without any DI graph
- [ ] Verify loop green
