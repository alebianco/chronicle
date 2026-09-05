# Fetch2 mirror

A local Maven repository holding the three Fetch2 artifacts the download layer depends on.
Wired up in `settings.gradle.kts`, listed **before** JitPack so this copy wins.

## Why this exists (cu-166)

Fetch2 is **abandoned**: last commit 2024-12-03, 3.4.1 is the newest release, and one of its open
issues is titled *"This repository seems to be out of maintenance."* It is also served from
**JitPack**, which builds from source on demand and offers no guarantee an artifact stays
resolvable — so an upstream repo deletion, a tag rewrite or a JitPack outage would break the build
outright, with no path to a green build until someone reverse-engineers the downloads layer.

We are **not** migrating away. cu-12 evaluated Media3's `DownloadManager` and rejected it for a
reason that still holds: its `SimpleCache` uses an opaque on-disk layout, which would break
`MoveSyncLocationWorker` and orphan every download already on a user's device. Android's platform
`DownloadManager` cannot attach per-request auth headers cleanly, which Plex requires.

## Contents

Apache-2.0. `fetch2` and `fetch2okhttp` are what `app/build.gradle.kts` declares; `fetch2core` is
their shared transitive dependency and is vendored because it comes from the same unavailable
source. Every other transitive dependency (kotlin-stdlib, room-runtime, core-ktx, okhttp) resolves
from Google or Maven Central and is deliberately **not** mirrored.

| Artifact | SHA-256 of the `.aar` |
|---|---|
| `fetch2-3.4.1.aar` | `fb1ce9f8e59e96c6af0e2433957306f847dad1879edeb7d70cfa46c5bd7f2358` |
| `fetch2core-3.4.1.aar` | `e6169e11cccc8eb105ecab2aad1c3f2c2605ab972bf65fb8ee12921253f824b1` |
| `fetch2okhttp-3.4.1.aar` | `ad5e483e039e9268ab97f7d63a9266b305f221216fdb4c5ba8d28b2b24af8784` |

## Verifying it actually works

The mirror is only worth having if the build uses it. Both directions were checked by moving the
Gradle cache aside (`~/.gradle/caches/modules-2/files-2.1/com.github.tonyofrancis.Fetch`):

- JitPack commented out of `settings.gradle.kts` → resolves **from here**.
- This directory moved away as well → resolution **FAILS**, which is what proves the copy above is
  load-bearing rather than shadowed by the cache.
