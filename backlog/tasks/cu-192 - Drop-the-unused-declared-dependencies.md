---
id: cu-192
title: Drop the unused declared dependencies
status: Done
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - debt
milestone: m-2
dependencies: []
priority: low
ordinal: 68000
---

## Description

Found during the 2026-09-06 backlog and docs cleanup, and independently recorded by
[[decision-22]] while measuring the Compose POC:

> **`PlayerView` appears nowhere in the app, and `androidx.media3.ui` is imported by no Kotlin
> file.** The player screen is TextViews, ImageViews, a Slider and a RecyclerView — an audiobook
> player has no video surface. `media3-ui` is a declared dependency that the code does not use.

Verified 2026-09-06: `implementation(libs.media3.ui)` at `app/build.gradle.kts:229`, declared in
`gradle/libs.versions.toml:86`, and **zero** `androidx.media3.ui` imports under `app/src`.

Same shape as cu-167 (`kotlin-reflect` / `moshi-kotlin`), which measured a 218 KB APK saving from
dropping one unused artifact. This one is smaller but free.

## Two more found the same way (2026-09-06)

A follow-up sweep of every `[libraries]` alias against real imports found two others:

- **`work-testing`** (`libs.versions.toml:95`, `app/build.gradle.kts:260`) — **zero imports of
  `androidx.work.testing` anywhere.** The only mention of `TestListenableWorkerBuilder` in the tree
  is inside a *comment* in `WorkerDispatcherTest`. `ChronicleWorkerFactory.kt:35-37` documents this
  in the source itself. **Do not simply delete it** — cu-179 is the task that finally uses it, so
  the right outcome is "keep, and cu-179 makes it real" or "drop and re-add in cu-179". Decide
  deliberately rather than by sweep.
- **`facebook-infer-annotation`** (`libs.versions.toml:143`, `app/build.gradle.kts:199`,
  `compileOnly`) — zero `com.facebook.infer` imports. Almost certainly inherited from upstream.
  `compileOnly`, so it is not in the APK and the win is catalogue hygiene, not size.

**Checked and *not* unused:** `hamcrest-modern` looks unused (no `org.hamcrest` import in the three
`androidTest` files) but is a deliberate cu-54 fix — Espresso's `ViewMatchers` reference
`org.hamcrest.Matchers` *at runtime*, and `hamcrest-all:1.3` alone resolves the wrong version.
`app/build.gradle.kts:271-276` explains it. **Leave it alone**; a sweep keyed on imports would
wrongly flag it, which is the trap this note exists to prevent.

## The thing to get right

`media3-ui` is the artifact whose absence R8 would not warn about, so **measure rather than
assume**: record the `releaseRuntimeClasspath` diff and the APK size before and after, as cu-167
did. Run `./test_release_build.sh` — Media3 classes are reflection-adjacent and the ProGuard rules
are deliberately narrow (cu-45).

Note this is a *dependency* removal, not a capability decision: Google Cast (cu-168) uses
`media3-cast`, not `media3-ui`, and the notification is drawn by the system from
`NotificationCompat`. Confirm neither is affected before removing.

## Acceptance Criteria

- [x] `media3-ui` removed from `app/build.gradle.kts` and the version catalogue
- [x] `facebook-infer-annotation` removed (or its purpose recorded if it turns out to have one)
- [x] `work-testing` decided deliberately in light of cu-179, not swept
- [x] `hamcrest-modern` left in place — verified as a runtime-only Espresso need (cu-54)
- [x] `./verify.sh` green, including the release-compile stage
- [x] `./test_release_build.sh` passes its dex assertions
- [x] APK size delta recorded in the closing notes
- [x] Confirmed that Cast (cu-168) and the media notification are unaffected

## Closing notes

Three dependencies removed, plus the now-orphaned `inferAnnotation` version entry.

**Measured, not assumed** — release APK, before and after:

| | bytes |
|---|---:|
| before | 7,605,018 |
| after | 7,307,809 |
| **delta** | **-297,209 (-290.2 KiB, -3.91%)** |

Bigger than cu-167's 218 KB. The `releaseRuntimeClasspath` diff is exactly one artifact leaving,
which is the clean result the task asked for:

```
< androidx.media3:media3-ui:1.11.0
```

**`work-testing`: dropped.** The task said decide it deliberately in light of cu-179 rather than by
sweep — so: cu-179 has shipped (In Review) *and* was subsequently superseded by Hilt's
`HiltWorkerFactory`, which replaced the hand-written `ChronicleWorkerFactory`. After all of that
there are still **zero** `androidx.work.testing` imports. The task's own alternative ("drop and
re-add in cu-179") is now the only one left standing, since cu-179 is not coming back. It is a
one-line re-add in the catalogue whenever a worker actually gains a unit test.

Note `WorkerDispatcherTest`'s KDoc is now partly stale — it argues the `WorkerFactory` plumbing
"would buy nothing today", which Hilt has since made moot. Left alone: it is a comment in a passing
guard test, not behaviour, and rewriting it belongs with whoever writes the first worker unit test.

**`hamcrest-modern` left in place and re-verified**, exactly as the task warned: it is
`androidTestImplementation`, needed because Espresso's `ViewMatchers` reference
`org.hamcrest.Matchers` at *runtime*. The 24 `org.hamcrest` source imports resolve through
`libs.hamcrest` in `testImplementation`, so an import-keyed sweep would have wrongly flagged it.

**Cast and the notification confirmed unaffected.** Cast lives entirely in `CastPlayerProvider.kt`
against `androidx.media3.cast` — a different artifact, still declared. Notifications are drawn from
`NotificationCompat` in `NotificationBuilder.kt` / `NotificationRebuild.kt`. Neither references
`androidx.media3.ui`, and zero files in the tree do.

### One pre-existing failure fixed to get here

`./test_release_build.sh` was **already failing before this change**, and not for a dependency
reason: it asserted `injection.components.DaggerAppComponent` survived R8, but the Hilt migration
deleted `injection/components/` outright, so that class exists in no build at all. The guard was
reporting a phantom strip.

Replaced with `injection.ChronicleEntryPoint`, which carries the same hazard the original was
guarding — reached via `EntryPointAccessors` by interface, so R8 sees no direct call and stripping
it fails at runtime. Confirmed present in the shipped dex.

Sabotage-verified rather than assumed: adding a genuinely absent class to the `REQUIRED` list still
produces `❌ stripped or renamed by R8`, so the guard has not been softened into always passing.

Step 3 of that script still fails, and is **out of scope here**: it `adb install`s the *unsigned*
release APK, which the platform rejects with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`. That is a
scripting gap around signing, untouched because signing config needs owner sign-off. The dex
assertions this task depends on all pass.

`./verify.sh` green, 8 stages.
