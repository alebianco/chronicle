---
id: cu-214
title: "The toolchain chain, in four staged steps"
status: In Review
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - debt
  - tooling
milestone: m-3
dependencies:
  - cu-210
  - cu-211
priority: high
---

## Description

Four version bumps that **must happen in this order**, because each unlocks the next. One ticket, but
**four separately committed and separately verified steps** — the sequencing is the safety, so it
lives in the acceptance criteria rather than being left to judgement.

decision-22 records the gate the last two clear:

> **Compose BOM held at the 2026.06.x line.** 2026.08.00 pulls Compose 1.12.0, whose
> `material-ripple-android` requires **compileSdk 37**; this project is on 36.
>
> **`lifecycle-*-compose` reuse the existing 2.10.0 ref.** 2.11.0 wants compileSdk 37 *and* AGP 9.1.

## Step 1 — Room 2.8.1 → 2.8.3

**Room's KMP support landed in 2.8.3.** cu-194 §5 asked for exactly this check — *"confirm against
the Room release notes before relying on it — it is the kind of version-dependent claim this repo has
been burned by"* — and the belief was wrong about the version, not just unverified. It is 2.8.3, not
the 2.7 line.

Beyond being current, it **settles SQLDelight**: cu-194 raises SQLDelight only in case Room could not
go multiplatform. At 2.8.3 it can, so SQLDelight is declined on the merits.

**Not Room 3.0.** `androidx.room3` is a deliberate breaking major, currently alpha, whose headline is
JS/WASM — against five databases, nineteen exported schemas and seven migration tests, with no second
target asked for by cu-182. Revisit when cu-182 names one *and* it is stable.

**The migration tests are the safety net, so they must run for real** — `--rerun-tasks`. Gradle's
up-to-date checks have already made a passing suite meaningless here twice (cu-204's stale coverage
report; sabotage verification generally). Room's codegen also moved to Kotlin output in the 2.8 line,
so check the generated `_Impl` classes still match what `test_release_build.sh` asserts and what the
JaCoCo exclusions catch.

## Step 2 — Kotlin 2.2.10 → 2.3.11, with KSP

**Kotlin 2.4 is a hard ceiling, not caution.** Measured: Kotlin 2.4.20 is published, but **KSP's
newest release is 2.3.11 — there is none for Kotlin 2.4**, and Room, Hilt, Moshi *and* Ktorfit all run
through KSP. Nothing here can outrun it.

That also fixes **Ktorfit at 2.6.5**, whose 2.7.5 needs stdlib 2.4.0. When that was attempted it
produced nine failures that never mentioned Ktor — four `[MissingType]: Element 'Audiobook'`, a Room
`BookDatabase` failure, four Hilt errors citing `error.NonExistentClass` for a class that resolved
fine. **Raising the stdlib under KSP makes unrelated types vanish**, so the symptom points nowhere
near the cause. Remember that if this step misbehaves the same way; bisect rather than guess.

Everything generating code moves together or not at all.

## Step 3 — compileSdk 37 + AGP 9.x

**The riskiest change in cu-210's programme.** AGP 9.4.0 is published, so the gate can be cleared.

AGP 8 → 9 is a major: it touches every build file and can change DSL, packaging, lint behaviour and
R8 defaults. It can break the build in ways no unit test observes — three of the four real defects
found while migrating to Ktor were invisible to 1,678 green tests, and a toolchain major is the same
shape of risk, larger.

Things known to matter:

- **`kotlinOptions` is already migrated** to `compilerOptions` (done during the Ktor work, because
  the Ktorfit plugin escalated that deprecation to an error). One fewer AGP 9 item.
- **`lint-baseline.xml` will move.** It is 6,097 lines and already notes it was created under a
  different variant. A shrinking baseline is good news; a growing one is something to read.
- **`InvalidPackage` is disabled** for `ktor-utils-jvm` (`java.lang.management` from a desktop-only
  debug helper). Confirm AGP 9 still needs that.
- **R8 and the release build.** `test_release_build.sh` asserts reflection-dependent classes survive,
  and those assertions exist because this has broken before.
- **`minSdk` stays 27** (decision-3). Nothing here licenses raising it.

**No other library version moves in this step.**

## Step 4 — Compose BOM + lifecycle 2.11

The payoff. And **a Compose BOM bump is a UI change**, which this codebase learned the hard way — the
migration recorded four defect classes only a device showed, all invisible to a green Compose suite:
a `_white` drawable with a black fill needing an explicit `tint`; a `ComposeView` clipped by a View
parent; `Icon` flattening a two-colour drawable so a play button shipped as a bare circle; and
Material3's `labelLarge` not uppercasing, so `textAllCaps` section titles silently lost their casing
— caught only by comparing against a screenshot taken *before* the change.

That last one is the method to repeat: **screenshot before, screenshot after, compare.** A Material3
minor can move type scales, ripple and default paddings with no test noticing. `ChronicleThemeTest`
pins the palette; type and spacing are not pinned.

## Acceptance Criteria

**Step 1 — Room, committed alone**
- [x] Room 2.8.3 across `room-runtime`, `room-ktx`, `room-compiler` — one `room` version ref, so
      all three move together
- [x] All **nineteen** exported schemas unchanged — regenerated with `kspDebugKotlin
      --rerun-tasks` and byte-identical to the committed copies (`git diff --quiet app/schemas/`)
- [x] The migration tests pass with `--rerun-tasks`, not from cache — **26 of them**, not the
      seven this ticket claimed: 4 in `RoomMigrationTest` and 22 in `RoomSchemaTest`. 42 Gradle
      tasks executed, none up to date
- [x] `./test_release_build.sh` finds every reflection-dependent class — 9,145 classes in dex, all
      survived R8. Its later install step fails, but that is pre-existing (see below)
- [x] SQLDelight recorded as declined in cu-194, now citing a 2.8.3 that is actually in the build
      rather than a release note

**Step 2 — Kotlin + KSP, committed alone**
- [x] Kotlin and KSP bumped together — **Kotlin 2.3.21, KSP 2.3.11**. Not "the 2.3.11 line" as
      this ticket assumed: KSP dropped the `<kotlin>-<ksp>` scheme for bare versions, and Kotlin
      2.3.11 does not exist (404 on Maven Central)
- [x] Every KSP processor still generates, confirmed by **counting the output** before and after,
      not by a green compile: Room `_Impl` 10 → 10, Ktorfit impls 2 → 2, generated Kotlin 12 → 12,
      generated Java 222 → 222, Hilt/Dagger classes 259 → 259. (No Moshi adapters to check — cu-217
      removed that processor, so this ticket's mention of them is now stale.)
- [x] Ktorfit **can** move past 2.6.5 and has: **2.7.5**. It was pinned because 2.7.5 needed
      stdlib 2.4.0, which is exactly what this bump supplies. Included here rather than deferred,
      because it is the same KSP-driven constraint this step exists to lift
- [x] The KSP ceiling written down below and in the tech-stack reference — **verified by HTTP, not
      assumed**: no KSP publishes for 2.4.0, 2.4.10 or 2.4.20 (all 404)

**Step 3 — compileSdk 37 + AGP 9, committed alone and device-verified**
- [x] `compileSdk = 37`, `minSdk` still 27 — **on AGP 8.13.2**. AGP 9 is skipped; see below
- [x] **Device-verified** on the tablet: installed, launched (`LOGGED_IN_FULLY`, `Loaded books: 3`),
      a download completed (3 tracks × 2,880,044 bytes), playback started from the downloaded files
      (`Media uri is: file:///…/2001.wav`), no `FATAL EXCEPTION` anywhere in the session
- [x] Both orientations exercised on the player, no crash. (The tablet is landscape-native and
      reports the same geometry either way, so the two screenshots look alike — the rotation was
      still driven, it simply has nothing different to show)
- [x] `lint-baseline.xml` reviewed, **not regenerated**. Lint passes and reports **278 of its 546
      entries as no longer found** — a shrinking baseline, mostly XML resources the Compose
      migration deleted (`UnusedIds` 93, `UnusedResources` 91). Cleaning it up is real work and a
      separate concern from this bump, so the file is left alone
- [x] `InvalidPackage` re-justified and kept: it fires inside `ktor-utils-jvm` and lint records it
      against an absolute Gradle-cache path that would not resolve on another machine or in CI, so
      baselining it is not an option. Still scoped to that one id
- [x] **No library version moved at all** — not even AGP or the wrapper. This turned out to be
      one line: `compileSdk = 36` → `37`

**Step 4 — Compose BOM + lifecycle — SKIPPED, blocked on AGP 9.1**
- [~] Both moved, versions recorded — **skipped.** Compose 1.12.0 and lifecycle 2.11.0 refuse to
      resolve below AGP 9.1.0, enforced by `checkDebugAarMetadata`, so there is nothing to move
      until AGP 9 is viable
- [~] Before-and-after screenshots — skipped, nothing changed to screenshot
- [~] Ripple, type scale and section-title casing — skipped, same reason
- [~] `ChronicleThemeTest` and the Compose screen suites green — they are green, but on the
      *unchanged* Compose, so this proves nothing about the bump and is not claimed

**Dependabot will surface these when they become takeable.** cu-212 pins the Compose BOM and
`lifecycle-*` with unblock conditions, so a weekly PR appears once the constraint lifts rather than
anyone having to remember. The pins' recorded reason needs correcting first — see below.

**Throughout**
- [x] `./verify.sh` green after **each** step, not only at the end — and CI green after steps 1
      and 2, both jobs including the instrumented suite on a real emulator
- [x] **decision-22 amended by the owner (2026-09-08).** Its two "held" notes cited compileSdk 37;
      the measured constraint is AGP 9.1.0, and compileSdk 37 landed without lifting either hold.
      Re-measured before amending, by bumping each ref against the current tree:
      `checkDebugAarMetadata` reports *"requires Android Gradle plugin 9.1.0 or higher. This build
      currently uses Android Gradle plugin 8.13.2"* for **eleven** Compose artifacts and **two**
      lifecycle ones, and mentions compileSdk nowhere. `navigation-compose` 2.10.0 was found held by
      the same gate and its comment was wrong in the same way. Corrected in decision-22, both
      `libs.versions.toml` comments, `10-tech-stack.md` and cu-194's live constraint list

## Step 1 result (2026-09-07)

**Landed, and it was uneventful — which is the good outcome for a dependency bump.** `room 2.8.1 ->
2.8.3`, one version ref covering runtime, ktx and compiler.

The schema check was the one that mattered, since a diff would have meant the bump changed generated
SQL. Regenerated under `--rerun-tasks` and **all nineteen are byte-identical** to the committed
copies. Room's codegen moved to Kotlin output in the 2.8 line, which was the reason to suspect drift;
it produced none here.

Two corrections to this ticket's own text, both in the safer direction:

- **It says "the seven migration tests". There are 26** — 4 in `RoomMigrationTest` and 22 in
  `RoomSchemaTest`. All green with 42 Gradle tasks executed and none up to date.
- **`./test_release_build.sh` fails at its install step, and did so before this change too.** Proved
  by stashing the bump and re-running: identical failure on 2.8.1. The APK it builds is
  `app-release-unsigned.apk`, and an unsigned APK can never install
  (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`). The script exits 0 when no device is attached, so this
  only surfaces when one is — which is why it has gone unnoticed. **Filed as cu-224.** The criterion
  this step needed passed at step 2b: 9,145 classes in dex, every reflection-dependent one survived
  R8.

## Step 2 result (2026-09-07)

**Kotlin 2.2.10 → 2.3.21, KSP 2.2.10-2.0.2 → 2.3.11, Ktorfit 2.6.5 → 2.7.5.**

Two corrections to this ticket's own text, both found by querying Maven Central rather than trusting
the note:

- **"The 2.3.11 line" conflated two version schemes.** KSP used to be `<kotlin>-<ksp>`; it is now
  bare, so KSP 2.3.11 is not "KSP for Kotlin 2.3.11" — **Kotlin 2.3.11 does not exist**. The newest
  Kotlin that does is 2.3.21, and KSP 2.3.11 runs against it.
- **The Kotlin 2.4 ceiling is real and now verified by HTTP.** `symbol-processing-gradle-plugin`
  returns 404 for 2.4.0, 2.4.10 and 2.4.20, while Kotlin itself publishes all three. Room, Hilt,
  Dagger and Ktorfit all run through KSP, so nothing here can outrun it. That is the reason this
  step stops at 2.3.21 and not caution.

**Ktorfit came unpinned as a direct consequence.** 2.7.5 was blocked on stdlib 2.4.0 — when it was
last attempted it produced nine errors that never mentioned Ktor (`[MissingType]: Element
'Audiobook'`, Hilt citing `error.NonExistentClass`). On 2.3.21 it builds clean and still generates
both service impls. It is in this commit because it is the same constraint, lifted by the same bump.

**The generated-output count is the evidence, not the green build.** A processor that silently stops
running leaves a compile that still succeeds until something reflective fails at runtime — the same
shape as the launch crash. Room 10 → 10 `_Impl`, Ktorfit 2 → 2, Java 222 → 222, Hilt/Dagger 259 →
259, all identical.

`verify.sh` green (8 stages); release build green with 9,219 classes in dex and all 20
`@Serializable` models surviving R8.

## Step 3 attempt — blocked on the Pitest plugin (2026-09-07)

**Stopped deliberately, one blocker from done.** The work is stashed as
`cu-214 step 3 WIP: AGP 9.4.0, compileSdk 37, blocked on pitest`; the branch is clean at step 2.

Everything AGP 9 needed was found and fixed, in this order — each error only appears once the
previous is cleared, so this list is the actual migration path:

| Blocker | Resolution |
|---|---|
| `platforms;android-37` not installed | It is published as **`android-37.0`/`37.1`**, not a bare `android-37`. Installed 37.0 |
| AGP 9.4.0 requires Gradle ≥ 9.6.0 | Wrapper 9.5.1 → **9.7.1** (in this step's scope) |
| `kotlin.android` plugin is now an error | **AGP 9 has built-in Kotlin.** Removed from both build files; the Kotlin version still comes from the catalog |
| Hilt 2.57.2: "Android BaseExtension not found" | **Needs 2.60.1.** This breaks the step's "no other library moves" rule — see below |
| `assets.srcDir` deprecated to an error | `assets.directories.add(...)` |
| **`pl.droidsonroids.pitest` reads `applicationVariants`** | **No fix available.** AGP 9 removed that API |

### The blocker

```
Could not get unknown property 'applicationVariants'
  at pl.droidsonroids.gradle.pitest.PitestPlugin$_apply_closure2$_closure14.doCall(PitestPlugin.groovy:127)
```

**0.2.27 is the newest release and we are already on it** — an earlier reading of "0.2.9 is newest"
was `tail` sorting lexically, not numerically. The plugin is *actively maintained* (last push
2026-09-07, the same day), so AGP 9 support is plausibly coming rather than abandoned.

Removing the plugin proves it is the only thing left: with it out, configuration proceeds past every
other error.

### Second attempt: Pitest skipped as instructed, blocked further on

The owner chose "keep PIT, drop the Android wrapper". Doing that got AGP 9 **configuring** — the
plugin is unapplied with its configuration kept dormant in `app/build.gradle.kts`, and
`verify.sh --mutation` now says why rather than dying on a missing task. `writePitestScope` and
`PitestScopeTest` still run, so cu-213's derivation guard survives intact.

Two further blockers then appeared, and the second is the one that stops this:

- **Ktorfit 2.7.5 had to go back to 2.6.5.** Step 2 raised it believing the Kotlin bump lifted its
  stdlib-2.4.0 requirement. Under AGP 8 that held. Under AGP 9 it does not: **AGP 9's built-in Kotlin
  compiles at metadata version 2.2.0 regardless of the catalog's `kotlin = 2.3.21`**, so stdlib 2.4.0
  is rejected — reported as `Unresolved reference 'mutableListOf'` in generated Room code, which
  points nowhere near the cause. A comment already in the build file predicted this exact trap; step
  2's conclusion that Ktorfit was free was wrong, it was merely not yet failing.
- **`kotlin-parcelize` silently stops working.** It applies without error and the annotation does not
  resolve: `Unresolved reference 'Parcelize'`, and the one model using it fails to implement
  `Parcelable`. `org.jetbrains.kotlin.plugin.parcelize` behaves the same;
  `com.android.kotlin.parcelize` does not exist. Same shape as Pitest — a plugin that applies and
  no-ops under AGP 9's built-in Kotlin.

**Stopped here on this ticket's own instruction:** *"If step 3 needs changes beyond build files — a
source change forced by a DSL removal, say — stop and split it out."* Only one file uses
`@Parcelize`, so the workaround is small, but it is app source and it is a different piece of work.

Stashed as `cu-214 step 3 WIP #2: AGP 9.4.0 + Gradle 9.7.1 + compileSdk 37 + Hilt 2.60.1, pitest
dormant, blocked on kotlin-parcelize`.

### What AGP 9 costs, now that it is measured

Five separate incompatibilities, three of them silent (a plugin that applies and does nothing is
worse than one that fails):

| | Kind |
|---|---|
| `kotlin.android` plugin | hard error, fix documented by AGP |
| Hilt < 2.60.1 | hard error |
| `assets.srcDir` | deprecated to error |
| Pitest / `applicationVariants` | **no fix available** |
| `kotlin-parcelize` | **applies, silently does nothing** |
| Ktorfit 2.7.5 / stdlib 2.4.0 | must revert; AGP 9 pins Kotlin metadata at 2.2.0 |

That last row is the one worth arguing about before continuing: **AGP 9's built-in Kotlin appears to
cap the language version below what the catalog asks for.** If that is right, AGP 9 costs the Kotlin
2.3.21 that step 2 just landed, which inverts the reason for doing this at all.

### Two things that need the owner

**1. This step cannot keep its own "no other library moves" rule.** Hilt must go 2.57.2 → 2.60.1 for
AGP 9 to configure at all. That rule exists to keep a toolchain major from turning into a dependency
sweep, and this is one forced bump rather than a sweep — but it is a rule this ticket wrote, so it
should be broken deliberately, not quietly.

**2. Pitest has no AGP 9 release.** Options, none of which an agent should choose:

- **Wait.** The plugin is maintained and AGP 9.4.0 is recent; this may resolve itself. Costs nothing,
  and steps 1–2 already stand on their own.
- **Drop the Android Pitest plugin, keep PIT.** cu-213 wired `verify.sh --mutation` as opt-in and
  never fatal, so losing it costs a report nobody blocks on. The plain `info.solidsoft.pitest` plugin
  has no `applicationVariants` dependency but is not Android-aware.
- **Drop mutation testing.** Honest but wasteful — cu-213 was landed one session ago and its
  derivation guard is the part with lasting value.

## LANDED — AGP 9.4.0, with both gates intact (2026-09-08)

Steps 3 and 4 are **done**, which this ticket had recorded as skipped. The owner asked whether
there was another way round the pitest blocker, wanting AGP 9 for Circuit. There was.

**Three problems, each fixed from the build file** rather than waiting on an upstream release:

1. **`applicationVariants` removed** → `android.newDsl=false` restores the pre-9 variant API the
   pitest plugin is built on. Found in detekt's own AGP 9 alpha notes.
2. **Unit-test configurations missing** → `android.builtInKotlin=false` plus
   `beforeVariants { enableUnitTest = true }`; AGP 9 makes unit-test components opt-in per variant.
3. **`sourceDirs` empty** → set on the task from **inside `afterEvaluate`**. The plugin wires its
   tasks in its own `afterEvaluate`, so a plain `configureEach` is silently overwritten.

A fourth followed: `RealTitleSortCorpusTest` failed "without mutation" because the plugin adds test
resources from `intermediates/java_res/...`, a layout that moved in AGP 9, so its corpus file never
reached the minion classpath.

**The dangerous shape, worth remembering.** Problem 3 made PIT exit with "Missing required
option(s) [sourceDirs]", print its help text, and return **zero**. `BUILD SUCCESSFUL`, no report, no
mutations — a gate silently checking nothing. It was caught only by looking for the report, not by
the exit code.

### Results

| | |
|---|---|
| `./verify.sh --mutation` | **green, 11 stages** |
| mutation score | **200 killed / 483**, against AGP 8's 197/483 |
| `detektDebug` | still exists **with type resolution** — sabotage-verified: a `!!` on a nullable type was caught by `UnsafeCallOnNullableType`. detekt stays at **1.23.8**; the 2.x alpha turned out not to be needed |
| device | player and library correct in **both orientations**, tab navigation working |

**Step 4 landed with it**: Compose BOM 2026.08.00, lifecycle 2.11.0, navigation-compose 2.10.0,
Coil 3.6.2, androidx.core 1.19.0. The stale holds in decision-22, the `android-ui` skill and
`chronicle-compose-adopted` are corrected in the same change, since two of the three auto-load.

### What this costs, stated plainly

`newDsl=false` and `builtInKotlin=false` are a **deferral, not a fix**. AGP warns the legacy variant
API is removed in **AGP 10**, so this buys time for the pitest plugin to catch up. Documented in
`gradle.properties` with that framing.

Also noted: the dependency-analysis plugin warns it is only tested to AGP 9.3.1. It still produces a
real report — checked, not assumed.

**cu-231 (Circuit) is unblocked**, which was the point.

## Full AGP 9.4.0 walk-through — 2026-09-08, it works; two gates are the cost

Carried further than the note below, on the owner's instruction to update everything and re-enable
Pitest. **Nothing from this is committed** — the tree is on AGP 8.13.2 and green at 11 stages.

### The migration itself succeeds

On AGP 9.4.0 + Gradle 9.7.1, with parcelize removed ([[cu-233]]), `kotlin.android` dropped, Hilt at
2.60.1 and Pitest unapplied:

- `:app:assembleDebug` — **succeeds**
- `:app:testDebugUnitTest` — **entire suite green**
- Device-verified on the tablet: library and player render correctly, chapter list, two-colour play
  button, slider, section-title casing all intact. **None of the four Compose-bump defect classes
  this ticket warned about appeared.**

And it unlocks the versions step 4 was blocked on, all four building and testing green together:
**Compose BOM 2026.08.00** (Compose 1.12.0), **lifecycle 2.11.0**, **Coil 3.6.2**, **androidx.core
1.19.0**.

So step 4 is not blocked by anything except step 3, and step 3 is not blocked by app source.

### The cost is two quality gates, and they differ

**Pitest: no path.** The droidsonroids plugin uses `applicationVariants`, removed in AGP 9. Latest
is **v0.2.27 (March 2026)**, and there is **no pre-release and no AGP 9 work in flight** — checked
against the Gradle plugin portal, Maven Central and the upstream repository's releases and issues.
The PIT *engine* is a separate artifact and is now current at 1.30.0; that is not the blocker.

**detekt: there is a path, and it is an alpha.** `detektDebug` does not exist under AGP 9 with
detekt 1.23.8, and the bare `detekt` task analyses **without type resolution** — this ticket's own
measurement is 31 findings against 120, and a rule that cannot resolve a type reports nothing rather
than a false negative.

**detekt 2.0.0-alpha.6 fixes it.** Measured: it is built against AGP 9.3.1, and under AGP 9 it
restores the full variant-aware task set — `detektDebug`, described by the plugin itself as *"Run
detekt analysis for debug classes **with type resolution**"*. It is a rewrite, so migrating costs:

- plugin id `io.gitlab.arturbosch.detekt` → **`dev.detekt`**
- task class `io.gitlab.arturbosch.detekt.Detekt` → **`dev.detekt.gradle.Detekt`**
- the reports DSL changed (`xml`/`html`/`md`/`txt` no longer resolve as before)
- **`build: maxIssues: 0` is gone** from the config schema. Behaviour is preserved without it —
  measured: 2.x fails the build on any finding by default — but the key must be deleted or the run
  aborts with "Property 'build' is misspelled or does not exist"
- the baseline is not picked up as-is and needs regenerating

### What this leaves the owner

The question is no longer "does AGP 9 work" — it does, on device. It is **what mutation testing is
worth**, since AGP 9 and Pitest are mutually exclusive until upstream moves:

- **Stay on AGP 8** — keeps Pitest and detekt 1.23.8; forgoes Compose 1.12/Coil/core, and keeps
  [[cu-231]] (Circuit) gated.
- **Take AGP 9** — everything updates and Circuit unblocks; Pitest goes dormant (cu-213 already made
  `verify.sh --mutation` opt-in and never fatal), and detekt moves to a 2.x alpha to keep its teeth.

Not decided here. Recorded so the decision is made on measurements rather than on the older,
gloomier reading.

## Re-measured on AGP 9.4.0 — 2026-09-08, two of the five blockers are gone

Prompted by the owner asking whether 9.4 might solve what 9.1 did not. **The earlier run was already
on 9.4.0**, so that is not the difference; what changed is that two of the five were re-tested rather
than assumed, and both fall.

`verify.sh` green on AGP 8.13.2 after the spike; nothing below is committed.

### `kotlin-parcelize` — **not a blocker.** It is dead code.

`PlexUser` is the only `Parcelable` in the codebase, and **nothing ever parcels it**: no `putExtra`,
no `Bundle`, no nav argument, and no other `: Parcelable` in `app/src/main`. It is residue from the
Fragment era the Compose migration removed.

Deleting the annotation, the import and the `kotlin-parcelize` plugin leaves **`verify.sh` green at
all 10 stages on AGP 8.13.2** — so this is a cleanup worth doing on its own merits, independent of
any AGP decision. What was recorded as *"applies, silently does nothing"* is really *"applies to
something nothing uses"*.

### Ktorfit 2.7.5 / Kotlin metadata — **no longer reproduces**

This was the row flagged as *"the one worth arguing about … if that is right, AGP 9 costs the Kotlin
2.3.21 that step 2 just landed"*. It does not: with parcelize removed, `:app:compileDebugKotlin`
**succeeds on AGP 9.4.0 with Ktorfit at 2.7.5 and `kotlin = 2.3.21` unchanged**, and Ktorfit's KSP
codegen runs — `_PlexLoginServiceImpl.kt` is generated for both variants.

Neither Ktorfit nor KSP has published anything since (2.7.5 and 2.3.11 are still latest), so the
earlier failure was most likely a consequence of the parcelize/plugin state rather than a metadata
cap. **The reason for doing this is therefore not inverted.**

### What still blocks, re-confirmed by walking it

| | Status on 9.4.0 |
|---|---|
| `kotlin.android` plugin | hard error, one-line fix (drop the alias; AGP 9 has it built in) |
| Hilt < 2.60.1 | hard error, one-line bump — still needs owner sign-off, it breaks this step's own "no other library moves" rule |
| Pitest / `applicationVariants` | still no AGP 9 release; unapplying it clears configuration, per the owner's earlier "keep PIT, drop the Android wrapper" |
| **detekt** | **new, not previously recorded** — `:app:detektDebug` does not exist under AGP 9, so `verify.sh` stage fails. detekt's Android variant tasks need whatever its AGP 9 support is |
| `assets.srcDir` | not re-reached |

So the chain now gets **all the way to a successful `compileDebugKotlin`** — further than either
earlier attempt — and stops at the verify gate on detekt rather than on anything in app source.

### Where that leaves the decision

The count is no longer "five, three silent". It is **three mechanical build-file changes plus one
open question (detekt's AGP 9 story)**, with Pitest already settled by a prior owner decision. That
is a materially cheaper migration than the one this ticket declined.

It matters beyond this ticket: **cu-231 (Circuit) is gated on AGP 9.1.0+**, so the cost of AGP 9 is
now the cost of the Circuit adoption the owner vetoed into existence in [[decision-26]].

**Not resumed on this ticket.** It is closed, the work is a fresh unit, and the Hilt bump and detekt
question both need the owner. Recorded here because this is where the AGP 9 evidence lives.

## AGP 9 is skipped, and what that actually costs (2026-09-07)

**compileSdk 37 landed on AGP 8.13.2. It never needed AGP 9.** That was the question worth asking
before spending more on the migration, and the answer was one line of build file plus a device pass.

**decision-22's recorded reason is wrong, and this is the correction that matters** — it is what the
next person reads. It says the Compose BOM and `lifecycle-*` are held because they need
**compileSdk 37**. Measured on AGP 8.13.2 *with compileSdk 37 in place*:

```
Dependency 'androidx.compose.material:material-ripple-android:1.12.0'
  requires Android Gradle plugin 9.1.0 or higher.
Dependency 'androidx.lifecycle:lifecycle-viewmodel-compose-android:2.11.0'
  requires Android Gradle plugin 9.1.0 or higher.
```

The constraint is **AGP 9.1.0**, enforced by `checkDebugAarMetadata`, and no SDK level changes it.
The Dependabot pins have been corrected to say so; **decision-22 itself has not been touched**, since
amending a decision record is the owner's call.

### So AGP 9 buys exactly one thing: step 4

Everything else in this chain is already landed and needed none of it — Room 2.8.3, Kotlin 2.3.21,
KSP 2.3.11, compileSdk 37. Against that single gain, AGP 9 costs five measured incompatibilities,
three of them silent, plus the open question of whether its built-in Kotlin caps metadata at 2.2.0 —
which would trade away the Kotlin 2.3.21 this chain just landed.

**Trading a real Kotlin bump for a Compose refresh is a bad trade, so AGP 9 is skipped rather than
forced.** Two plugins have to catch up first (`pl.droidsonroids.pitest`, `kotlin-parcelize`), and
both are third-party timelines.

**Dependabot is the mechanism for picking this up later.** The pins carry their real reason now, so
a PR appears when the constraint lifts instead of depending on anyone remembering.

## Notes

Closing status **In Review**: two of four steps landed as written, the third landed in a reduced form
that this ticket did not anticipate, and the fourth is skipped on a third-party constraint. That
shape is a judgement about scope, not a fact a test settles.

**Two things want the owner specifically:**

1. **decision-22 records the wrong reason** for the Compose/lifecycle hold — compileSdk 37, when the
   real constraint is AGP 9.1.0. Measured, not inferred. Amending a decision record is owner-only, so
   it is flagged rather than edited.
2. **Hilt 2.57.2 → 2.60.1 was needed for AGP 9** and is *not* in what landed, because AGP 9 was
   skipped. Recorded so the next AGP 9 attempt does not rediscover it.

device evidence, the other is a judgement about how screens look — and step 3 edits decision-22.

**If step 3 needs changes beyond build files** — a source change forced by a DSL removal, say — stop
and split it out. A toolchain bump that starts editing app code is two pieces of work wearing one
hat.

Depends on cu-211 deliberately: the launch-smoke test is the device-level safety net that makes the
AGP 9 step safe to attempt.
