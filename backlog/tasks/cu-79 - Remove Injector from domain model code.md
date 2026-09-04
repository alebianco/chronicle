---
id: cu-79
title: Remove Injector from domain/model code
status: Done
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

- [x] No `Injector.` references remain under `data/model/`
- [ ] Room `@TypeConverter`s receive their Moshi adapter by construction (Room supports
      converter instances via `addTypeConverter`), not by service lookup
- [x] `MediaItemTrack`'s path/URL helpers take what they need as parameters, or move to a
      repository — a data class should not know about `PlexConfig`
- [x] Tests for the touched models construct them without any DI graph
- [x] Verify loop green

## Implementation Notes

All three sites are gone; `data/model/` names `Injector` nowhere, and a guard keeps it that way.

**`MediaItemTrack.getTrackSource` takes its two dependencies as parameters.** `toMediaMetadata`
already took `plexConfig` that way, so this follows an established shape rather than inventing one.
The dependency then propagates exactly one level, to `AudiobookMediaSessionCallback`, which already
injects a `PrefsRepo` — so nothing new had to be plumbed. The compiler found every caller, which is
the argument for the change: a service lookup finds nobody.

The `plexConfig` call was the worst of the three and the reason this mattered beyond testability —
a *domain* model reaching into a *Plex-specific* config is the coupling the `MediaSource` seam
exists to remove (cu-15, decision-11).

**The Room converter took a different route, and the criterion is worth correcting.** The task
suggested `addTypeConverter`. That would work, but it is not needed: the application's Moshi is a
bare `Moshi.Builder().build()` with no custom adapters, and the payload is a `List<String>`, so the
converter builds its own instance. Room instantiates a `@TypeConverters(::class)` converter
reflectively, so the alternative means adding `addTypeConverter` plumbing to every database that
uses it — real cost for no behavioural difference. The assumption is recorded in the KDoc and
pinned by a test asserting the **exact** stored text, so if the shared Moshi ever gains an adapter
that writes a string list differently, that test fails rather than the data silently changing shape.

**The guard is a source check** (`no model reaches into the DI graph`), because the defect is
*reachability*, not behaviour: one `Injector.get()` anywhere under `data/model/` makes that model
unconstructable without `ChronicleApplication`, and every test in this file would have to become an
instrumented one. Nothing that exercises the models can notice it coming back. Sabotage-verified.

**Verification**

- `./verify.sh --format` green, 7 stages. **1153 unit tests**, 0 failures.
- The new tests never mention `Injector` and never stand up the application — which is the point;
  before this they could not have been written that way.
- One of them asserts two different cached directories give two different results for the same
  track, which was impossible while the model read a single global.
