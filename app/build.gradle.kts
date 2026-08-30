plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  id("kotlin-parcelize")
  alias(libs.plugins.ksp)
  id("com.google.android.gms.oss-licenses-plugin")
  jacoco
}

android {
  namespace = "io.github.mattpvaughn.chronicle"
  compileSdk = 34

  lint {
    abortOnError = false
    baseline = file("lint-baseline.xml")
    checkReleaseBuilds = true
    checkAllWarnings = true
  }

  defaultConfig {
    applicationId = "io.github.mattpvaughn.chronicle"
    minSdk = 27
    targetSdk = 34
    versionCode = 27
    versionName = "0.55.0"

    testInstrumentationRunner = "io.github.mattpvaughn.chronicle.application.ChronicleTestRunner"
  }

  buildTypes {
    debug {
      // Required for JaCoCo to emit execution data from unit tests.
      enableUnitTestCoverage = true
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
  kotlinOptions {
    jvmTarget = "17"

    freeCompilerArgs += "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
  }
  buildFeatures {
    viewBinding = true
    buildConfig = true
  }

  // The debug variant serves the cu-16 Plex fixtures as assets so the app can be
  // driven on a device with no account. Pointed at the same directory the unit
  // tests use, so there is exactly one copy of each fixture to keep in sync.
  sourceSets {
    getByName("debug") {
      assets.srcDir("src/test/resources")
    }
  }

  testOptions {
    unitTests {
      // Robolectric needs the merged android resources/manifest on the
      // unit-test classpath.
      isIncludeAndroidResources = true
    }
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
  arg("room.expandProjection", "true")
}

dependencies {
  implementation(libs.material)
  implementation(libs.timber)
  implementation(libs.fetch)
  implementation(libs.work)
  implementation(libs.result)
  implementation(libs.swiperefresh)
  implementation(libs.seismic)
  implementation(libs.browserx)
  implementation(libs.oss)
  implementation(libs.appcompat)
  // Declared explicitly: asLiveData/viewModelScope/ViewModel were previously
  // only reaching the classpath transitively through the Google-IAP billing
  // library, so removing that (cu-60) took them with it.
  implementation(libs.lifecycle.livedata.ktx)
  implementation(libs.lifecycle.runtime.ktx)
  implementation(libs.lifecycle.viewmodel.ktx)
  implementation(libs.annotation)
  implementation(libs.coroutines)
  compileOnly(libs.facebook.infer.annotation)

  implementation(libs.retrofit)
  implementation(libs.retrofit.converter)

  implementation(libs.okhttp3)
  implementation(libs.okhttp3.logging)

  implementation(libs.moshi)
  // Removed moshi-codegen KAPT processor - deprecated for Kotlin 2.x
  // Moshi will use reflection-based adapters instead

  implementation(libs.coil)
  implementation(libs.coil.network.okhttp)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.dagger)
  ksp(libs.dagger.compiler)

  implementation(libs.media3.exoplayer)
  implementation(libs.media3.ui)
  implementation(libs.media3.session)
  implementation(libs.media3.datasource)
  implementation(libs.media3.cast)

    /*
     * Local Tests
     */
  testImplementation(libs.dagger)
  kspTest(libs.dagger.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.mockk)
  testImplementation(libs.hamcrest)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.androidx.arch.core.testing)

  // Robolectric drives real SQLite in a JVM test, which lets the Room migration
  // suite run in the unit-test gate. Room's own MigrationTestHelper is
  // instrumented-only, and instrumented tests are quarantined (cu-54).
  debugImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.retrofit)
  testImplementation(libs.retrofit.converter)
  testImplementation(libs.moshi)
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
  androidTestImplementation(libs.espresso.core)
  androidTestImplementation(libs.espresso.contrib)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.ext.junit.ktx)
}

// Instrumented tests are QUARANTINED, not merely disabled: the sources under
// app/src/androidTest no longer compile. They target an `OnboardingActivity` and
// string resources that ceased to exist when onboarding was refactored into
// Fragments (LoginFragment/ChooseServerFragment/ChooseUserFragment/
// ChooseLibraryFragment) in 9e89270. Removing this block yields ~15 unresolved
// references, not a passing suite.
//
// They are kept in-tree as the raw material for the rewrite (see backlog task
// cu-54, "Rebuild instrumented tests on Gradle Managed Devices"). Until that
// lands, THERE IS NO INSTRUMENTED COVERAGE — do not claim any.
tasks.matching { it.name.contains("DebugAndroidTest") && !it.name.contains("Lint") }.configureEach {
  enabled = false
}

jacoco {
  toolVersion = "0.8.12"
}

// Robolectric runs tests through its own sandbox classloader; without these two
// settings the JaCoCo agent cannot attribute execution to those classes and
// anything covered only by a Robolectric test silently reports 0%, which would
// blind the cu-3 coverage ratchet to real gains.
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
  )

tasks.register<JacocoReport>("jacocoTestReport") {
  dependsOn("testDebugUnitTest")
  group = "verification"
  description = "Generates JaCoCo coverage report for the debug unit tests."

  reports {
    xml.required.set(true)
    html.required.set(true)
  }

  classDirectories.setFrom(
    files(
      fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
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
