plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  id("kotlin-parcelize")
  alias(libs.plugins.ksp)
  alias(libs.plugins.ktorfit)
  alias(libs.plugins.hilt)
  alias(libs.plugins.compose.compiler)
  id("com.google.android.gms.oss-licenses-plugin")
  alias(libs.plugins.pitest)
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

android {
  namespace = "io.github.mattpvaughn.chronicle"
  compileSdk = 36

  lint {
    // Fatal, so `verify.sh`'s lint stage means what it claims. With `abortOnError = false` the
    // stage could not fail: 18 Error-severity issues were passing through it, and neither id was
    // in the baseline, so they were unreviewed rather than knowingly accepted. Anything genuinely
    // accepted belongs in lint-baseline.xml, where it shows up in a diff.
    abortOnError = true
    baseline = file("lint-baseline.xml")
    checkReleaseBuilds = true
    checkAllWarnings = true
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
        create("instrumentedCheckGroup") {
          targetDevices.add(localDevices["api27"])
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

  testImplementation(platform(libs.compose.bom))
  testImplementation(libs.compose.ui.test.junit4)
  // Supplies the empty activity `createComposeRule` launches into — the Compose equivalent of
  // what `fragment-testing` provides, and required for the rule to work at all.
  debugImplementation(libs.compose.ui.test.manifest)

  implementation(libs.material)
  implementation(libs.timber)
  implementation(libs.fetch)
  implementation(libs.fetch.okhttp)
  implementation(libs.work)
  implementation(libs.result)
  implementation(libs.swiperefresh)
  implementation(libs.seismic)
  implementation(libs.browserx)
  implementation(libs.oss)
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
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.transition)
  implementation(libs.androidx.coordinatorlayout)
  implementation(libs.androidx.interpolator)
  implementation(libs.androidx.sqlite)
  // Declared explicitly: asLiveData/viewModelScope/ViewModel were previously
  // only reaching the classpath transitively through the Google-IAP billing
  // library, so removing that took them with it.
  implementation(libs.lifecycle.livedata.ktx)
  implementation(libs.lifecycle.runtime.ktx)
  implementation(libs.lifecycle.viewmodel.ktx)
  implementation(libs.annotation)
  implementation(libs.coroutines)

  implementation(libs.retrofit)
  implementation(libs.retrofit.converter)

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
  ksp(libs.ktorfit.ksp)
  implementation(libs.okhttp3)
  implementation(libs.okhttp3.logging)

  implementation(libs.moshi)
  // Codegen, not reflection. The old KAPT processor is gone; this is the KSP
  // one, which is what `@JsonClass(generateAdapter = true)` has been asking for ever since.
  ksp(libs.moshi.codegen)

  implementation(libs.coil)
  implementation(libs.coil.network.okhttp)

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
  testImplementation(libs.androidx.arch.core.testing)

  // Robolectric drives real SQLite in a JVM test, which lets the Room migration
  // suite run in the unit-test gate. Room's own MigrationTestHelper is
  // instrumented-only, and instrumented tests are quarantined.
  debugImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.retrofit)
  testImplementation(libs.retrofit.converter)
  testImplementation(libs.moshi)
  // Reflection, for tests that build adapters for types with no @JsonClass. Production is
  // codegen-only, so `moshi-kotlin` and the kotlin-reflect it drags in stay out of the APK.
  testImplementation(libs.moshi.kotlin.reflect)
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
    // Moshi codegen — every model uses `@JsonClass(generateAdapter = true)`. These are
    // generated `fromJson`/`toJson` bodies nobody writes or reviews — 7,882 instructions, 9.2% of
    // the measured codebase, sitting in the denominator. The *models* they serialize stay
    // measured, and the real-shape fixture tests still exercise the parsing through them;
    // what is excluded is the generated plumbing, exactly as the Dagger and Room entries above do.
    "**/*JsonAdapter*.*",
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

// Mutation testing. Deliberately **manual**: not in verify.sh, not in CI, and no score
// threshold. It answers a different question from the coverage ratchet — "would the tests notice if
// this code changed?" rather than "was this line executed?" — and it is far too slow for an inner
// loop.
//
//   ./gradlew pitestDebug     report: app/build/reports/pitest/index.html
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
  excludedTestClasses.set(
    listOf(
      // Robolectric — see (1) above. Named explicitly so a new Robolectric test that forgets this
      // list produces a confusing result rather than a silent lie.
      "io.github.mattpvaughn.chronicle.data.local.RoomSchemaTest",
      "io.github.mattpvaughn.chronicle.data.local.RoomMigrationTest",
      "io.github.mattpvaughn.chronicle.data.local.MigrationSupportTest",
      "io.github.mattpvaughn.chronicle.data.local.ChapterBackfillSqlTest",
      "io.github.mattpvaughn.chronicle.data.model.TrackSourceUriTest",
      "io.github.mattpvaughn.chronicle.features.player.ProgressUpdaterTest",
      "io.github.mattpvaughn.chronicle.features.library.ProgressIndicatorTest",
      "io.github.mattpvaughn.chronicle.views.ColorContrastTest",
      "io.github.mattpvaughn.chronicle.data.sources.plex.ReauthenticationTest*",
      "io.github.mattpvaughn.chronicle.features.bookdetails.AudiobookDetailsPlaybackTest",
      // Forgetting this did exactly what the note above predicts: PIT refused to
      // start with "7 tests did not pass without mutation", while ./verify.sh stayed green.
      "io.github.mattpvaughn.chronicle.util.PackageValidatorTest",
      // Robolectric because MediaControllerCompat.Callback's constructor needs Binder (DRAFT-72).
      "io.github.mattpvaughn.chronicle.features.player.NotificationStateMachineTest",
      "io.github.mattpvaughn.chronicle.features.player.ChapterSessionMetadataTest",
      "io.github.mattpvaughn.chronicle.features.player.PlayBookGuardsTest",
    ),
  )
}
