---
id: cu-216
title: "A third-party licences page, and drop the Play Services plugin that was meant to build it"
status: In Review
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - compliance
  - ui
milestone: m-3
dependencies: 
  - cu-210
priority: medium
---

## Description

Owner ask: generate an in-app third-party licences page for GPLv3 compliance.

**Auditing this found a problem worth fixing first.** The project already declares
`play-services-oss-licenses` **and applies its Gradle plugin** —
`app/build.gradle.kts:9` and `build.gradle.kts:14` — and **nothing uses it.** There is no licences
screen anywhere in the app.

That is not merely dead weight. It is a **Google Play Services** dependency, and **decision-1** puts
sideload/F-Droid/homelab distribution first. F-Droid does not accept GMS dependencies, so the tool
nominally intended for this job is itself a distribution blocker.

So this task does both halves: remove the GMS licences plugin and dependency, and build the page with
a libre tool instead.

## The tool

Either works and both are Apache-2.0:

- **AboutLibraries** — generates metadata at build time and ships Compose UI components, so the
  screen is close to free.
- **Licensee** — a Gradle plugin that validates and exports licence data, leaving the rendering to
  us. Lighter, more control, more work.

Prefer whichever adds fewer transitive dependencies; check both against the GPLv3 and decision-19
bars before choosing, and record the choice.

## What compliance actually needs

Worth being precise, because "a licences page" is vague:

- Every bundled third-party dependency named, with its licence and its full licence text or a link.
- **GPLv3's own obligations** are separate and already met elsewhere — `LICENSE` is in the repo and
  the source is public. This page is about the *dependencies*, not about Chronicle's own licence.
- The **licence headers and `LICENSE` file are on the never-touch list** without owner sign-off. This
  task adds a screen; it does not edit either.

## The thing to get right

**A generated page that silently misses a dependency is worse than none**, because it looks like
diligence. So the page must be generated from the resolved dependency graph rather than
hand-maintained, and the count it renders should be checked against the graph at least once.

## Acceptance Criteria

- [x] `play-services-oss-licenses`, its plugin and its catalogue entries removed; the F-Droid
      rationale (decision-1) recorded in the closing notes
- [x] A licences screen reachable from Settings, following the `*Screen` + `*Destination` convention
- [x] Content **generated from the resolved dependency graph**, not hand-written
- [x] The rendered dependency count reconciled against `releaseRuntimeClasspath` once, so an
      omission is ruled out rather than assumed
- [x] Licence text or a working link for each entry
- [x] Screenshotted on a device in **both orientations**
- [x] APK delta recorded — removing GMS should shrink it; the new tool may offset that
- [x] `LICENSE` and every licence header untouched
- [x] `./verify.sh` green

## Notes

Closing status **In Review**: it is a new user-visible screen and a compliance artefact, so both the
look and the completeness want the owner's eye.

## Closing notes

### The tool: AboutLibraries' Gradle plugin, and *only* the plugin

The task offered AboutLibraries or Licensee and asked for whichever adds fewer transitive
dependencies. The answer turned out to be a third option that adds **none at all**: apply the
AboutLibraries Gradle plugin, and parse its generated JSON with the kotlinx-serialization the app
already ships. Two findings forced it, and each alone would have.

- **`aboutlibraries-compose-m3` pulls a second Compose stack.** Its POM depends on Compose
  Multiplatform 1.12.0 and material3 1.9.0, beside the Compose BOM this project pins deliberately at
  the 2026.06.x line. That ruled out the ready-made UI, so the screen is our own Compose.
- **`aboutlibraries-core` 14.0.0+ is compiled for Java 21** (class file major 65) and this project is
  Java 17 throughout. It would load on a device, where everything is dexed, but the JVM unit suite
  cannot construct it at all — `UnsupportedClassVersionError`, which is how it was found: the
  Robolectric test for the reader failed while nothing else did. That would have made the reader the
  one part of this feature no unit test could reach. The last Java-17 line is 13.x, a version behind
  and carrying an extra transitive dependency (`kotlinx-collections-immutable`).

The *plugin* is Java 17 and runs on the Gradle daemon exactly as intended, so only the runtime
artifact was ever in question. The six fields the screen reads are declared in
`GeneratedCatalogJson.kt`; `ignoreUnknownKeys` is what makes that safe against a plugin upgrade.

Licensee was not chosen but would have been the fallback: it also leaves rendering to us, and the
only thing separating them once the runtime artifact was dropped is that AboutLibraries already
resolves the variant classpath and emits a merged raw resource.

### The F-Droid rationale (decision-1)

`play-services-oss-licenses` is a **Google Play Services** dependency, and decision-1 puts
sideload/F-Droid/homelab distribution first. F-Droid does not accept a GMS dependency, so the tool
nominally generating the app's own licences page could not ship where the app is meant to ship.
Removing it takes the GMS licences artifact off the release runtime classpath entirely;
`LicenseCatalogCountTest` asserts that against the resolved graph rather than against the build file,
because a transitive reintroduction would not show up in the latter. `RetiredDependencyTest` keeps
the import out of the source.

**The Cast SDK is unaffected.** It remains the single occupant of the decision-19 proprietary-SDK
exception, confined to `CastPlayerProvider`. The guard names `com.google.android.gms.oss`
specifically, never the group.

**Decision-19 now names a dependency that is gone**, and is deliberately left alone — a decision file
records what was decided at the time and amending it is the owner's call. `00-constitution.md` is
updated instead, since it is a reference doc. The removal was **not** a reversal of that reading: the
licences plugin passed the four-part test on its merits and failed a *different* one. The reusable
lesson is that the four-part test asks *may we depend on this* while distribution asks *where can the
result be installed*, and only the first was written down.

### Reconciliation: 212 dependencies, checked against the graph

`LicenseCatalogCountTest` compares the rendered list against the `aboutlibraries.json` the plugin
writes from **`releaseRuntimeClasspath`** — the variant that ships, not debug, which carries test and
tooling artifacts. It is asserted as a **set difference in both directions**, not as two counts:
equal counts would also hold if the projection dropped one dependency and duplicated another.

Three deliberate choices in that test:

- It parses the JSON with plain kotlinx-serialization rather than through the app's own reader. Using
  the same reader would let a parser that dropped everything produce an empty catalogue *and* an
  empty reference, and the counts would agree.
- `tasks.withType<Test> { dependsOn("prepareLibraryDefinitionsRelease") }` in `app/build.gradle.kts`
  guarantees the file exists, and the test fails loudly on a missing one rather than skipping.
- A dependency declaring **no** licence is kept and rendered with a "no license declared" marker,
  never dropped. Dropping it would shorten the page *and* keep the count agreeing with it, so nothing
  would look wrong.

At the time of writing: **212 libraries on the release classpath, every one carrying a licence with a
URL**, and `play-services-oss-licenses` absent. The debug build shows 222 — the extra ten are
debug-only tooling, which is why the release variant is the one reconciled.

### Licence links rather than embedded text

The criterion allows text *or* a working link. This build takes the link: embedding full text needs
the plugin's `fetchRemoteLicense`, which hits the GitHub API at build time, is rate-limited without a
token, and would make the build depend on the network. `offlineMode = true` and
`fetchRemoteLicense = false` are set explicitly. `LicenseCatalogCountTest` pins that every licence
named carries a URL, so the chosen half is actually honoured.

### APK delta: **−61,994 bytes (−0.85%)**

Release APK, measured by building both sides in this worktree:

| | bytes |
|---|---:|
| before (HEAD) | 7,286,028 |
| after | 7,224,034 |
| **delta** | **−61,994** |

Removing GMS shrank it and the new tool did not offset that, because the new tool ships **no runtime
artifact at all** — only a generated JSON resource. The debug APK is not a useful comparison here.

### Device verification

Installed on the tablet (`HVA067JE`) and screenshotted in **both orientations**. The build under test
was proven by md5: `25bb6e50c6f147a9d308147cf4e37e28`, matching `app-debug.apk` byte for byte on
device.

That proof earned its keep. A first pass showed the **old** `OssLicensesMenuActivity` — an empty
"Debug License Info" screen — from an APK that provably did not contain that class. Another agent
sharing this device had reinstalled over the build between the install and the check: `pm path`
returned a different install directory and a different md5. Reinstalling and re-checking gave the
real screen. **A device is shared state; hash the installed APK immediately before the observation,
not once at install time.**

Both orientations render the intro, the GPLv3 note about Chronicle's own licence, the count, and rows
carrying name, coordinate, version and an underlined licence link.

### One pre-existing defect observed, deliberately not fixed here

**The `ChronicleScaffold` top bar is invisible on this screen — no title, no back arrow.** The
account-revoked Snackbar (`MainActivity.AccountRevokedNotice`, decision-17) is an app-wide indefinite
overlay that sits exactly where the top app bar draws. Confirmed pre-existing by opening the Series
Index Tester, which uses the identical scaffold and loses its toolbar the same way under the same
banner. It is not in this task's scope and fixing it belongs in its own ticket, but it is worth
filing: on the licences screen the only way out is the system back button.

### One wrong comment corrected

The root `build.gradle.kts` pinned JavaPoet with a comment blaming the OSS-licences plugin for
dragging in an old one. Removing that plugin left the conflict exactly where it was:
`./gradlew buildEnvironment` puts `com.squareup:javapoet:1.10.0` under **AGP's own
`com.android.tools.build:gradle`**. The pin is kept and the stated cause corrected — a wrong
explanation beside a load-bearing line is the half that invites the next reader to delete it.

### Coverage

Every ratchet moved up: overall 54.48% → 54.85%, `navigation` 87.46% → 96.15% (the new
`DestinationTest` cases cover routes that were never exercised), and the new `licenses` package
enters at 80.10%.

### Sabotage-verified guards

- `LicenseCatalogCountTest` — filtered `androidx.room` out of the projection; the set-difference and
  count tests failed. Restored.
- `LicenseCatalogCountTest` — pointed `GENERATED_CATALOG` at a non-existent path; **all six** tests
  failed rather than passing vacuously. Restored.
- `RetiredDependencyTest` — the GMS import can no longer be written (the artifact is gone, so it does
  not compile), so the *matcher* was verified instead by pointing it at a package the source really
  does import; it failed. Restored.
- `DestinationTest` — gave `Licenses` the same route string as `Browse`; the distinct-route test
  failed. Restored.

### Product choices flagged for the owner

Nothing here was a settled product decision, but three are judgement calls a reviewer may want to
overturn — which is part of why this closes as `In Review`:

- **The screen's wording.** Every string describes the generated list rather than asserting
  completeness, since wording that promised more than the code can prove would be exactly the
  "looks like diligence" failure. The title is "Open source licenses"; the Settings row keeps its
  existing "Licenses" label.
- **Ordering: by display name, case-insensitively, tie-broken on the coordinate.** The tiebreak is
  not cosmetic — several groups publish a library called "Core", and without it their order moves
  under an unrelated dependency bump.
- **Links open in the system browser**, matching how the Settings screen already opens its GitHub
  link. A `CustomTabsIntent` was considered and left alone: two link-opening conventions in one
  feature is worse than either.
