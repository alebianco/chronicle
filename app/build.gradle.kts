plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.ksp)
  alias(libs.plugins.ktorfit)
  alias(libs.plugins.hilt)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.aboutlibraries)
  alias(libs.plugins.pitest)
  alias(libs.plugins.detekt)
  // Must be applied to this subproject too, not only to the root: applying it at the root alone
  // produces `buildHealth` that succeeds while reporting "No project health reports found" -- a
  // green build with an empty report, which reads exactly like a clean bill of health.
  alias(libs.plugins.dependency.analysis)
  jacoco
}

// The Kotlin compiler options, in the current DSL. `kotlinOptions {}` was deprecated and the
// Ktorfit Gradle plugin escalates that deprecation to an error, so this had to move — the settings
// themselves are unchanged.
kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
  }
}

/**
 * Third-party dependency metadata, generated from the resolved dependency graph.
 *
 * This replaces `play-services-oss-licenses` and its Gradle plugin. That pair was a **Google Play
 * Services** dependency, and decision-1 puts sideload/F-Droid/homelab distribution first — F-Droid
 * does not accept a GMS dependency, so the tool nominally doing this job was itself a distribution
 * blocker. AboutLibraries is Apache-2.0 and needs no Play Services.
 *
 * **The plugin only — no AboutLibraries artifact is on the runtime classpath.** Two independent
 * reasons, either of which alone would be decisive:
 *
 * - `aboutlibraries-core` 14.0.0 and newer are compiled for **Java 21** (class file 65) while this
 *   project is Java 17 throughout. It loads on a device, where everything is dexed, but the JVM
 *   unit suite cannot even construct it: `UnsupportedClassVersionError`. The last Java-17 line is
 *   13.x, which is a version behind and adds `kotlinx-collections-immutable`. The *plugin* is Java
 *   17, so it runs on the Gradle daemon exactly as it should.
 * - `aboutlibraries-compose-m3` would pull Compose Multiplatform 1.12.0 and material3 1.9.0, a
 *   second Compose stack beside the BOM this project pins deliberately.
 *
 * So the plugin generates `res/raw/aboutlibraries.json` and the app parses it with the
 * kotlinx-serialization it already ships — which is also the fewest transitive dependencies of any
 * option considered, since it adds none at all.
 *
 * `offlineMode` is on, and `fetchRemoteLicense` deliberately off: fetching full licence text hits
 * the GitHub API at build time, which is rate-limited without a token and would make the build
 * depend on the network. Each entry carries its SPDX licence id and a link instead, which is what
 * compliance needs and what a build can promise.
 */
aboutLibraries {
  offlineMode = true

  collect {
    fetchRemoteLicense = false
    fetchRemoteFunding = false
  }

  library {
    // Two artifacts of the same library published for different platforms (`-android`, `-jvm`)
    // are one entry, so the list names libraries rather than repeating each with a suffix.
    duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
    duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.GROUP
  }
}

android {
  namespace = "io.github.mattpvaughn.chronicle"
  compileSdk = 37
  // Pinned rather than defaulted. AGP 8.13.2's built-in default is 35.0.0, which does not track
  // compileSdk -- so "which build-tools this project needs" was invisible here and silently
  // changed with every AGP bump. Naming it keeps one version installed across projects.
  buildToolsVersion = "37.0.0"

  lint {
    // Fatal, so `verify.sh`'s lint stage means what it claims. With `abortOnError = false` the
    // stage could not fail: 18 Error-severity issues were passing through it, and neither id was
    // in the baseline, so they were unreviewed rather than knowingly accepted. Anything genuinely
    // accepted belongs in lint-baseline.xml, where it shows up in a diff.
    abortOnError = true
    baseline = file("lint-baseline.xml")
    checkReleaseBuilds = true
    checkAllWarnings = true
    // `InvalidPackage` fires on `ktor-utils-jvm`, which references `java.lang.management` from
    // `IntellijIdeaDebugDetector` — a desktop-only debug helper that is never reached on Android.
    // Disabled rather than baselined because the finding is inside a *dependency jar* and lint
    // records it against an absolute path in the Gradle cache, which would not resolve on another
    // machine or in CI. Scoped to this one id so every other Error-severity issue still fails the
    // build, which is the property the settings above exist to preserve.
    disable += "InvalidPackage"
  }

  defaultConfig {
    applicationId = "io.github.mattpvaughn.chronicle"
    minSdk = 27
    targetSdk = 36
    versionCode = 27
    versionName = "0.55.0"

    testInstrumentationRunner = "io.github.mattpvaughn.chronicle.application.ChronicleTestRunner"
  }

  buildTypes {
    debug {
      // Required for JaCoCo to emit execution data from unit tests.
      enableUnitTestCoverage = true

      // A debug build installs alongside a release one rather than replacing it. The owner's phone
      // carried upstream's signed v0.52.1, which a debug APK cannot upgrade (different signing
      // key), and uninstalling it to make room would have destroyed a working install to test a
      // throwaway build. Debug only: the release applicationId is untouched, and choosing the
      // fork's permanent id is a separate owner decision (branding is sign-off-only per CLAUDE.md).
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    buildConfig = true
    // ViewBinding is gone: there are no layouts left to generate bindings for.
    // decision-22's migration ran screen-by-screen through `ComposeView` with both enabled;
    // the navigation shell was the last thing holding XML, and Navigation Compose replaced it.
    compose = true
  }

  // The debug variant serves the Plex fixtures as assets so the app can be
  // driven on a device with no account. Pointed at the same directory the unit
  // tests use, so there is exactly one copy of each fixture to keep in sync.
  sourceSets {
    getByName("debug") {
      assets.srcDir("src/test/resources")
    }
  }

  testOptions {
    // Espresso refuses to click while window/transition animations are on — the device-side
    // setting, not a Gradle one. Managed Devices do not disable it for us.
    animationsDisabled = true

    unitTests {
      // Robolectric needs the merged android resources/manifest on the
      // unit-test classpath.
      isIncludeAndroidResources = true
    }

    // Gradle Managed Devices: the emulator is declared here and provisioned by Gradle, so
    // `./gradlew instrumentedCheckGroupDebugAndroidTest` is the same command on a laptop and on any
    // CI — no emulator-runner action, no forge lock-in (D12 rule 6).
    managedDevices {
      localDevices {
        // The minSdk floor. Catches a new API called without a version guard, which is a live risk
        // at minSdk 27 with Media3 — and the kind of break that only shows on an old device.
        create("api27") {
          device = "Pixel 2"
          apiLevel = 27
          // AOSP has no Play Services; nothing here needs them, and the images are smaller.
          systemImageSource = "aosp"
          // Stated rather than defaulted. On CI the setup task installed the API 27 image and then
          // died with "Cannot query the value of this property because it has no value available",
          // after warning that the ABI was unspecified. At API 27 the AOSP image is 32-bit x86 with
          // no arm64 variant, and AGP could not pick for us.
          //
          // AGP warns that the unspecified default is "x86" today and becomes "arm64-v8a" in 9.0,
          // so naming it here also survives cu-214 stage 3 rather than breaking on it.
          require64Bit = false
        }
        // A recent level, close to compileSdk 36. "aosp" rather than "aosp-atd": the plain image
        // is the one already installed and licensed on the owner's machine, so a local run needs
        // no download. ATD is smaller and faster in CI — switch there if the licence is accepted.
        create("api35") {
          // A phone, not a tablet: on the 2560x1600 tablet profile the system taskbar overlaps the
          // bottom nav, so Espresso refuses to click it ("covers at least 90 percent of the view's
          // area"). The same overlap bit `capture-screens.sh` — see the note in CLAUDE.md.
          device = "Pixel 6"
          apiLevel = 35
          systemImageSource = "aosp"
        }
      }
      groups {
        // The full group — both levels. Used locally, where the API 27 image is already installed.
        create("instrumentedCheckGroup") {
          targetDevices.add(localDevices["api27"])
          targetDevices.add(localDevices["api35"])
        }
        // What CI runs. api27 is deliberately absent: on a GitHub runner AGP installs the API 27
        // AOSP x86 image successfully and then fails `api27Setup` with "Cannot query the value of
        // this property because it has no value available", having warned that the device's ABI is
        // unspecified. It does not reproduce locally, because the image is already present and the
        // failing path never runs. `testedAbi`, which the warning names, exists on AGP 8.13.2's
        // implementation class but not on the DSL interface the build script compiles against, so
        // it cannot be set from here.
        //
        // api35 alone still gives the gate its whole point — the launch crash this exists to catch
        // is API-independent. Losing the minSdk floor on CI is a real gap, tracked rather than
        // hidden: see cu-222.
        create("ciCheckGroup") {
          targetDevices.add(localDevices["api35"])
        }
      }
    }
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.expandProjection", "true")
}

dependencies {
  // --- Compose -------------------------------------------------------------------
  // The BOM governs every Compose artifact's version, including the test ones, so they cannot
  // drift apart. `platform(...)` on each configuration that needs it.
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  implementation(libs.compose.activity)
  implementation(libs.compose.lifecycle.runtime)
  implementation(libs.compose.lifecycle.viewmodel)
  // @Preview rendering and the layout inspector. Debug-only: it pulls in tooling that must not
  // ship, and `ui-tooling-preview` above is the part release code actually needs.
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.coil.compose)

  testImplementation(libs.okio.fakefilesystem)

  testImplementation(platform(libs.compose.bom))
  testImplementation(libs.compose.ui.test.junit4)
  // Supplies the empty activity `createComposeRule` launches into — the Compose equivalent of
  // what `fragment-testing` provides, and required for the rule to work at all.
  debugImplementation(libs.compose.ui.test.manifest)

  implementation(libs.material)
  implementation(libs.timber)
  implementation(libs.work)
  implementation(libs.result)
  implementation(libs.swiperefresh)
  implementation(libs.seismic)
  implementation(libs.browserx)
  implementation(libs.appcompat)
  // Declared because the app imports them directly, not because it needs a newer version — each
  // is pinned at what it already resolved to transitively. Three transitive-only breakages
  // (`androidx.lifecycle`, `androidx.localbroadcastmanager` and `androidx.media`) are the pattern this
  // closes out; `DeclaredDependencyTest` keeps it closed.
  implementation(libs.androidx.activity)
  implementation(libs.androidx.core)
  implementation(libs.androidx.fragment)
  // FragmentScenario needs its empty host activity in the *debug* manifest, so this is
  // debugImplementation rather than testImplementation — Robolectric runs against the debug variant.
  debugImplementation(libs.androidx.fragment.testing)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.transition)
  implementation(libs.androidx.sqlite)
  // Declared explicitly: asLiveData/viewModelScope/ViewModel were previously
  // only reaching the classpath transitively through the Google-IAP billing
  // library, so removing that took them with it.
  implementation(libs.lifecycle.livedata.ktx)
  implementation(libs.lifecycle.runtime.ktx)
  implementation(libs.lifecycle.viewmodel.ktx)
  implementation(libs.annotation)
  implementation(libs.coroutines)

  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.logging)
  // Ktor is the HTTP stack per decision-24. Pinned at 3.2.1 with Ktorfit 2.6.5 because that is
  // the pair built against Kotlin 2.2.x: Ktorfit 2.7.5 requires kotlin-stdlib 2.4.0, which
  // upgrades the stdlib underneath KSP and makes unrelated classes resolve as
  // `error.NonExistentClass` — nine failures in Room and Hilt processing, none of them mentioning
  // Ktor. Raising either means raising Kotlin first, which is its own task.
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktorfit.lib)
  // Okio for the download and cache-reconciliation paths. Already arrives transitively
  // via Coil and Ktor, so this declares what is already there rather than adding weight.
  implementation(libs.datastore.preferences)
  implementation(libs.okio)
  ksp(libs.ktorfit.ksp)

  // kotlinx-serialization is the serializer, replacing Moshi. Moshi was JVM-only and
  // codegen-based, so it kept every model Android-side no matter what happened to the transport;
  // this is the last piece decision-24 deliberately left behind. The compiler plugin generates
  // the serializers at compile time — no reflection, no KSP processor, no runtime adapter lookup.
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.serialization.kotlinx.json)

  implementation(libs.coil)
  implementation(libs.coil.network.ktor3)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.dagger)
  ksp(libs.dagger.compiler)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.hilt.work)
  ksp(libs.hilt.work.compiler)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.navigation.compose)

  // Declared explicitly: 23 files import android.support.v4.media / androidx.media
  // (MediaSessionCompat, PlaybackStateCompat, MediaBrowserServiceCompat...), which
  // arrived only transitively via media3-session. Media3 is migrating callers off
  // that compat bridge, so the release that drops it would break playback wholesale
  // — the same failure mode that `androidx.lifecycle` and `androidx.localbroadcastmanager` hit.
  implementation(libs.media)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.session)
  implementation(libs.media3.datasource)
  implementation(libs.media3.cast)
  implementation(libs.mediarouter)

    /*
     * Local Tests
     */
  testImplementation(libs.dagger)
  testImplementation(libs.hilt.android.testing)
  kspTest(libs.hilt.compiler)
  kspTest(libs.dagger.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.mockk)
  testImplementation(libs.hamcrest)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(libs.androidx.arch.core.testing)

  // Robolectric drives real SQLite in a JVM test, which lets the Room migration
  // suite run in the unit-test gate. Room's own MigrationTestHelper is
  // instrumented-only, and instrumented tests are quarantined.
  debugImplementation(libs.okhttp3.mockwebserver)
  // Also debugImplementation: the `fail_sync` debug hook synthesises a real 400 through a
  // MockEngine, because Ktor's ResponseException wraps a live HttpResponse that cannot be built
  // by hand. Mirrors how mockwebserver is a debugImplementation for MockPlexServer.
  debugImplementation(libs.ktor.client.mock)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)

    /*
     * Instrumented Tests
     */
  androidTestImplementation(libs.dagger)
  kspAndroidTest(libs.dagger.compiler)

  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.mockk)
  androidTestImplementation(libs.coroutines.test)
  // Espresso's ViewMatchers reference org.hamcrest.Matchers at runtime, and it does not arrive
  // transitively. `hamcrest-all:1.3` is *not* enough on its own: it drags in hamcrest-library,
  // which Gradle resolves to 2.2 against a 1.3 core, and org.hamcrest.Matchers then lands in
  // neither merged dex — withId() dies with NoClassDefFoundError while the dependency looks
  // present in the resolved classpath. Pin the modern coordinates instead.
  androidTestImplementation(libs.hamcrest.modern)
  androidTestImplementation(libs.espresso.core)
  androidTestImplementation(libs.espresso.contrib)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.ext.junit)
  // The instrumented suite asserts on Compose semantics: the app has no View ids
  // left to match on, so `LoggedInLaunchTest` reads the bottom bar's tabs by content description.
  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit.ktx)
}

// ---------------------------------------------------------------------------------------------
// detekt — complexity, potential bugs and coroutine misuse
// ---------------------------------------------------------------------------------------------
//
// The division of labour with ktlint is the reason this is worth having at all: **ktlint owns
// formatting, naming and style; detekt owns none of those.** Two linters with opinions about the
// same lines produce advice that contradicts itself, and no edit satisfies both. What detekt adds
// is the three things ktlint cannot see: how complex a function is, whether a construct is a latent
// defect, and whether coroutines are being used unstructured. `config/detekt/detekt.yml` disables
// the overlapping rule sets explicitly, with the reason recorded next to each.
//
// It lands **ratcheted**, like `lint-baseline.xml` and the coverage ratchet before it. A linter
// switched on against an existing codebase reports its whole backlog at once, and the honest
// outcome of a wall of findings blocking every build is that someone deletes the stage. Today's
// findings are frozen into `config/detekt/baseline-debug.xml`; only *new* ones fail. That baseline is
// visible debt — its size is recorded in `backlog/docs/reference/11-verify-loop.md` — to be worked
// down as its own effort, not inside the task that introduced the gate.
//
// **The gate is `detektDebug`, not `detekt`.** The bare `detekt` task runs without a classpath, and
// roughly half the potential-bugs set — `UnsafeCallOnNullableType`,
// `ElseCaseInsteadOfExhaustiveWhen`, `UnnecessarySafeCall`, `HasPlatformType` — needs type
// resolution to decide anything, and those 20 findings are all invisible without it. Without it
// those rules do not report a false negative; they report *nothing*, and a linter finding nothing
// is indistinguishable from a clean tree. `detektDebug` compiles the debug variant first and hands
// detekt the real classpath, so those rules actually run. `DetektRuleSetTest` pins that choice.
detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
  // Set as `baseline.xml`, read and written as **`baseline-debug.xml`**: the variant-aware tasks
  // insert the variant name before the extension so debug and release can hold different
  // baselines. Naming the file that already has the suffix would produce
  // `baseline-debug-debug.xml`, which nothing reads — the baseline would silently not apply and
  // the stage would fail on findings it was supposed to be ignoring. Regenerate with
  // `./gradlew :app:detektBaselineDebug`.
  baseline = rootProject.file("config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
  // 1.23.x otherwise defaults to the JVM target of whichever daemon happens to be running, so a
  // daemon on a different JDK would change which rules can resolve types — the same analysis
  // giving different answers on different machines.
  jvmTarget = "17"
  reports {
    xml.required.set(true)
    html.required.set(true)
    sarif.required.set(false)
    md.required.set(false)
    txt.required.set(false)
  }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
  jvmTarget = "17"
}

jacoco {
  toolVersion = "0.8.12"
}

// Robolectric runs tests through its own sandbox classloader; without these two
// settings the JaCoCo agent cannot attribute execution to those classes and
// anything covered only by a Robolectric test silently reports 0%, which would
// blind the coverage ratchet to real gains.
tasks.withType<Test>().configureEach {
  extensions.configure<JacocoTaskExtension> {
    isIncludeNoLocationClasses = true
    excludes = listOf("jdk.internal.*")
  }
}

// Generated code would otherwise dominate the coverage number and make it
// meaningless: databinding, Dagger factories/injectors, and Room _Impl classes
// are machine-written and not ours to test.
val coverageExclusions =
  listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BR.*",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/databinding/**",
    "**/android/databinding/**",
    "**/androidx/databinding/**",
    "**/*_MembersInjector*.*",
    "**/*_Factory*.*",
    "**/Dagger*Component*.*",
    "**/*Module_*Factory*.*",
    "**/*_Impl*.*",
    "**/*_Provide*Factory*.*",
    // Hilt's generated code, on the same reasoning as the Dagger entries above. The
    // `Hilt_*` base classes it inserts under each `@AndroidEntryPoint` are 147 instructions apiece
    // of lifecycle plumbing nobody writes — 1,789 across 74 classes, all of it in the denominator.
    "**/Hilt_*.*",
    "**/*_HiltModules*.*",
    "**/*_HiltComponents*.*",
    "**/hilt_aggregated_deps/**",
    "**/dagger/hilt/**",
    // Serializer codegen — every model carries `@Serializable`, and the compiler plugin emits a
    // `$$serializer` object beside each holding the generated `serialize`/`deserialize` bodies.
    // Nobody writes or reviews them, and they sat in the denominator as the equivalent Moshi
    // adapters did (7,882 instructions, 9.2% of the measured codebase, before the swap). The
    // *models* stay measured, and the real-shape fixture tests still exercise the parsing through
    // them; what is excluded is the generated plumbing, as the Dagger and Room entries above do.
    "**/*\$\$serializer*.*",
    // Ktorfit codegen. `_PlexMediaServiceImpl` and `_PlexLoginServiceImpl` are the generated
    // bodies for the 25 endpoint annotations — 2,292 instructions of URL building and header
    // plumbing nobody writes or reviews.
    //
    // Worth stating why this is not a coverage loss. Retrofit created its services as **runtime
    // proxies**, so there was no bytecode for JaCoCo to measure at all; Ktorfit generates real
    // classes, so the same endpoints suddenly appeared in the denominator and dropped
    // `data/sources/plex` by 7.7 points without a single test changing. Excluding them restores
    // the like-for-like comparison.
    //
    // Note the existing `**/*_Impl*.*` entry (Room) does not catch these: Ktorfit puts the
    // underscore at the *start* of the name, so the pattern has to be its own.
    "**/_*Impl*.*",
  )

tasks.register<JacocoReport>("jacocoTestReport") {
  dependsOn("testDebugUnitTest")
  group = "verification"
  description = "Generates JaCoCo coverage report for the debug unit tests."

  reports {
    xml.required.set(true)
    html.required.set(true)
  }

  // The **ASM-transformed** classes, not `tmp/kotlin-classes/debug`.
  //
  // Hilt rewrites `@AndroidEntryPoint` classes through an ASM transform, and the tests execute
  // *those*. Reporting against the untransformed output makes JaCoCo unable to match its execution
  // data — it logs "Execution data for class ... does not match" and **silently discards that
  // class's coverage**, which read as a 2.24% aggregate regression across the five rewritten
  // classes rather than as a broken report.
  classDirectories.setFrom(
    files(
      fileTree(layout.buildDirectory.dir("intermediates/classes/debug/transformDebugClassesWithAsm/dirs")) {
        exclude(coverageExclusions)
      },
      fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
        exclude(coverageExclusions)
      },
    ),
  )
  sourceDirectories.setFrom(files("$projectDir/src/main/java"))
  executionData.setFrom(
    fileTree(layout.buildDirectory) {
      include("**/*.exec", "**/*.ec")
    },
  )
}

// Mutation testing. **Opt-in**: not in `verify.sh`'s default gate and not in `--quick`. It answers
// a different question from the coverage ratchet — "would the tests notice if this code changed?"
// rather than "was this line executed?" — and that answer is worth minutes, not seconds.
//
//   ./gradlew pitestDebug     report: app/build/reports/pitest/debug/index.html
//   ./verify.sh --mutation    the same run, reported and never fatal
//
// The allowlist is the whole design. Two reasons it is not a wildcard:
//
//  1. **Robolectric tests must not be in scope.** PIT + Robolectric is broken and unfixed
//     (koral--/gradle-pitest-plugin#80, open since 2022). It fails *silently*, reporting false
//     SURVIVED/NO_COVERAGE — so pointing PIT at RoomSchemaTest would report our sabotage-verified
//     migration tests as worthless. Every class listed here is covered by plain-JVM tests only.
//  2. **Generated code must not be mutated.** Room `_Impl`, Dagger factories and ViewBinding classes
//     would produce thousands of meaningless mutants. An allowlist avoids needing exclusions.
//
// Note the `Kt` suffixes: most of the logic worth mutating lives in top-level functions, which
// Kotlin compiles into `<FileName>Kt`. Listing only the class names would silently mutate nothing.

/**
 * Every unit-test class annotated `@RunWith(RobolectricTestRunner::class)`, as JVM binary names.
 *
 * **Derived, never maintained.** This was once a hand-written list whose own comment predicted that
 * forgetting an entry would produce a silent lie. The prediction was right and the comment changed
 * nothing: 62 Robolectric classes had accumulated against 14 listed, one listed class
 * (`ChapterBackfillSqlTest`) no longer existed at all, and `pitestDebug` had been failing outright
 * — "130 tests did not pass without mutation" — for long enough that nobody noticed. A list that
 * must be updated by hand demonstrably was not, so the shape was wrong, not the entries.
 *
 * Nested classes are emitted as `Outer${'$'}Inner`, which PIT matches against the binary name.
 * `ReauthenticationTest.AgainstTheRealImplementation` is exactly that case — a Robolectric class
 * inside a plain-JVM outer class — and the old list papered over it with a trailing wildcard that
 * also swallowed the outer class PIT could legitimately have used.
 *
 * Scanning sources rather than compiled classes is deliberate: the value is needed at configuration
 * time, and reading `build/` would make the exclusion silently *empty* whenever PIT is configured
 * before the test classes exist — the same failure mode in a new costume.
 */
fun robolectricTestClasses(): List<String> {
  val testRoot = file("src/test/java")
  if (!testRoot.isDirectory) return emptyList()
  val runWith = Regex("""@RunWith\(\s*RobolectricTestRunner::class\s*\)""")
  val classDecl = Regex("""^(\s*)(?:(?:internal|private|abstract|open|sealed|data)\s+)*class\s+(\w+)""")

  return testRoot
    .walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .flatMap { source ->
      val lines = source.readLines()
      val pkg =
        lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
          ?: return@flatMap emptySequence<String>()
      // The enclosing class is tracked by indentation. That is enough structure for test sources
      // and avoids parsing Kotlin for a build-configuration value.
      val enclosing = ArrayDeque<Pair<Int, String>>()
      val found = mutableListOf<String>()
      var pendingRobolectric = false
      for (line in lines) {
        if (runWith.containsMatchIn(line)) {
          pendingRobolectric = true
          continue
        }
        val match = classDecl.find(line) ?: continue
        val indent = match.groupValues[1].length
        val name = match.groupValues[2]
        while (enclosing.isNotEmpty() && enclosing.last().first >= indent) enclosing.removeLast()
        val binaryName = (enclosing.map { it.second } + name).joinToString("${'$'}")
        enclosing.addLast(indent to name)
        if (pendingRobolectric) {
          found += "$pkg.$binaryName"
          pendingRobolectric = false
        }
      }
      found.asSequence()
    }
    .sorted()
    .toList()
}

/**
 * The exclusion handed to PIT, derived once so the guard cannot be shown a different list.
 *
 * Both `excludedTestClasses` and `writePitestScope` read *this* value rather than calling the
 * derivation again. Calling it twice would let the configured exclusion and the published one drift
 * apart, and the guard would then pass against a list PIT never saw — the same class of silent lie
 * the derivation exists to prevent, one level up.
 */
val pitestRobolectricExclusion: List<String> = robolectricTestClasses()

pitest {
  pitestVersion.set(libs.versions.pitestTool)
  // No junit5PluginVersion: this project is on JUnit 4.13.2. Setting it made the coverage
  // minion die with NoClassDefFoundError on PreconditionViolationException, reported only as
  // "Minion exited abnormally (UNKNOWN_ERROR)" until verbose was enabled.
  mutators.set(listOf("DEFAULTS"))
  // Suppresses Intrinsics.checkNotNull* mutants, which are compiler-generated null checks rather
  // than behaviour anyone wrote.
  avoidCallsTo.set(listOf("kotlin.jvm.internal"))
  threads.set(4)
  timestampedReports.set(false)
  // XML alongside HTML so surviving mutants can be listed mechanically rather than scraped.
  outputFormats.set(listOf("HTML", "XML"))
  targetClasses.set(
    listOf(
      // Listening position and completion (decision-16)
      "io.github.mattpvaughn.chronicle.data.model.MediaItemTrackKt",
      "io.github.mattpvaughn.chronicle.data.model.MediaItemTrack",
      "io.github.mattpvaughn.chronicle.data.model.MediaItemTrack${'$'}Companion",
      "io.github.mattpvaughn.chronicle.data.model.AudiobookKt",
      "io.github.mattpvaughn.chronicle.data.model.Audiobook${'$'}Companion",
      // Chapters
      "io.github.mattpvaughn.chronicle.data.model.ChapterKt",
      "io.github.mattpvaughn.chronicle.data.model.ChapterAssemblyKt",
      "io.github.mattpvaughn.chronicle.data.model.ChapterListConverter",
      // Downloads
      "io.github.mattpvaughn.chronicle.features.download.CacheScanOutcomeKt",
      "io.github.mattpvaughn.chronicle.features.download.DownloadGroupIdKt",
      "io.github.mattpvaughn.chronicle.features.download.ResumePlan",
      // Auth
      "io.github.mattpvaughn.chronicle.data.sources.plex.PlexTokenAuthenticator",
      "io.github.mattpvaughn.chronicle.data.sources.plex.AccountAuthState",
      // Progress reporting
      "io.github.mattpvaughn.chronicle.data.sources.plex.ProgressReporter",
      // Repositories and the cache reconciliation, covered from the second testing pass on
      "io.github.mattpvaughn.chronicle.features.download.CacheReconciliationKt",
      "io.github.mattpvaughn.chronicle.data.local.TrackRepository",
      "io.github.mattpvaughn.chronicle.data.local.BookRepository",
      "io.github.mattpvaughn.chronicle.application.MainActivityViewModel",
    ),
  )
  // Only real test classes. `io.github.mattpvaughn.chronicle.*` matched 969 classes — every
  // class on the test *classpath*, not the test sources — and the coverage minion died
  // (UNKNOWN_ERROR) trying to run them all.
  targetTests.set(listOf("io.github.mattpvaughn.chronicle.*Test"))
  // Robolectric — see (1) above. Derived from the sources on every configuration, so a new
  // Robolectric test cannot fall out of scope by being forgotten. `PitestScopeTest` fails if this
  // derivation stops matching the test tree.
  excludedTestClasses.set(pitestRobolectricExclusion)
}

// Publishes the exclusion the build **actually configured**, so a plain-JVM test can check it
// against the test tree. `PitestScopeTest` reads this file; without it the guard would have to
// re-implement the derivation and would then be asserting against itself, passing happily while
// the build's real configuration drifted — which is the failure this whole task exists to remove.
val writePitestScope by
  tasks.registering {
    val output = layout.buildDirectory.file("pitest-scope/robolectric-test-classes.txt")
    val configured = pitestRobolectricExclusion
    outputs.file(output)
    inputs.property("robolectricTestClasses", configured)
    doLast {
      val file = output.get().asFile
      file.parentFile.mkdirs()
      file.writeText(configured.joinToString("\n", postfix = "\n"))
    }
  }

tasks.withType<Test>().configureEach {
  dependsOn(writePitestScope)

  // The unit suite runs against **debug**, but the licences page is a claim about what *ships* — so
  // `LicenseCatalogCountTest` reconciles against the catalogue generated from
  // `releaseRuntimeClasspath`. Without this dependency the generated file would simply be absent on
  // a clean checkout and the guard would have nothing to compare against; it fails loudly on a
  // missing file rather than skipping, because a reconciliation that silently does not run is the
  // very omission it exists to catch, one level up.
  dependsOn("prepareLibraryDefinitionsRelease")
}
