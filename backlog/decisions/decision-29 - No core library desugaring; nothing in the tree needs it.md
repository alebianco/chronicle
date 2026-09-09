---
id: decision-29
title: "No core library desugaring; nothing in the tree needs it"
date: '2026-09-09'
status: accepted
supersedes: []
amends: []
---

## Decision

**`isCoreLibraryDesugaringEnabled` stays off.** Measured, not assumed: enabling it changes the
release APK by **zero bytes** and has no measurable build-time effect, because nothing in the
codebase uses an API it backports.

## What it is, and what it would do

Core library desugaring rewrites calls to newer `java.*` APIs so they run on older Android. It is
what lets a minSdk-27 app use `java.time`, `java.util.stream`, `Optional` and `java.util.function`,
which were added to Android at API 26 or later.

It is not free in principle: the rewriting adds a backport implementation to the dex, and AGP's
docs warn it grows the APK. That is the received wisdom this decision checks.

## Measured, 2026-09-09

**Nothing uses a desugarable API.** A grep across `app/src` — main, debug, release *and* test — for
`java.time`, `java.util.stream`, `java.util.Optional` and `java.util.function` returns **nothing**.
Date handling is 13 `System.currentTimeMillis` calls and one `java.util.Date`, none of which
desugaring touches.

So the backport has nothing to backport, and R8 strips what is never called:

| | release APK |
|---|---|
| without desugaring | 7,044,756 bytes |
| with desugaring (`desugar_jdk_libs:2.1.5`) | **7,044,756 bytes** |

**Byte-identical.** Verified the library was genuinely on the classpath
(`./gradlew :app:dependencies --configuration coreLibraryDesugaring` resolves it) and that the build
was a real one (`--rerun-tasks`, `BUILD SUCCESSFUL`), so this is not a stale artifact.

Build time, `assembleRelease --rerun-tasks`, two runs each: **87 s / 95 s** with desugaring,
**141 s / 79 s** without. The run-to-run variance is larger than any difference between the two
configurations, so the honest reading is **no measurable effect** rather than a small win either
way.

## Why record a no-op

Two reasons, both from this project's own precedent:

- **It answers the question before it is asked again.** "Should we enable desugaring?" is a
  reasonable thing to wonder at minSdk 27, and the answer is cheap to give once and expensive to
  re-derive. Same shape as cu-228, whose dependency removal changed the APK by zero bytes — the
  measurement *was* the finding.
- **The premise in the question was wrong**, and that is worth stating. Desugaring does not trade
  APK size for build speed here; it does neither, because the feature is inert against this
  codebase.

## What would change this

- **Adopting `java.time`.** The most likely trigger: if bookmark timestamps, sleep-timer arithmetic
  or backup metadata move off `System.currentTimeMillis` and onto `Instant`/`Duration`, desugaring
  becomes required rather than optional at minSdk 27, and the size question becomes real.
- **A dependency that uses those APIs internally.** Nothing in the tree does today, but a library
  bump could introduce one — the build fails loudly in that case, so it does not need watching.
- **Raising minSdk to 26+.** Would not help: the project is already at 27, above the level where
  most of these APIs landed. That is *why* the feature is inert here.

## What this does not decide

- **`java.time` is not discouraged.** If a change wants it, enabling desugaring is a two-line
  addition and this decision should be revisited rather than treated as a ban.
- **Nothing about minSdk.** A separate evaluation on 2026-09-09 found no reason to raise it; this is
  independent of that and would hold at 27 or 28 alike.
