plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false

  alias(libs.plugins.ktlint)
  alias(libs.plugins.detekt)
  alias(libs.plugins.aboutlibraries) apply false
}

allprojects {
  apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

buildscript {
  dependencies {
    // Hilt's Gradle plugin fails in `hiltAggregateDepsDebug` with
    // `NoSuchMethodError: ClassName.canonicalName()` against an old JavaPoet on the same
    // buildscript classpath, so the newer one has to win explicitly.
    //
    // The comment that stood here until the licences page was rebuilt blamed the
    // `play-services-oss-licenses` plugin for dragging that old JavaPoet in. It does not:
    // removing that plugin left the conflict exactly where it was. `./gradlew buildEnvironment`
    // puts `com.squareup:javapoet:1.10.0` under **AGP's own `com.android.tools.build:gradle`**,
    // which is not going anywhere. The pin is kept and the stated cause corrected — a wrong
    // explanation beside a load-bearing line is the half that invites the next reader to delete
    // it.
    classpath(libs.javapoet)
  }
}

ktlint {
  android.set(true)
}

// detekt is declared here only so `:app` can `alias` it — it is *configured* in
// `app/build.gradle.kts`, where the sources and the compiled classpath are. Half its rules need
// type resolution, and type resolution needs the variant's classpath, which only the module that
// owns the Android variants has.

tasks.register<Copy>("installGitHook") {
  from(rootProject.file("pre-commit"))
  into(rootProject.file(".git/hooks"))
}

// Ensure the app preBuild depends on the git hook installer. Use matching/configureEach to avoid
// deprecated fileCollection/spec usage that can appear with getByPath on newer Gradle.
tasks.matching { it.path == ":app:preBuild" }.configureEach {
  dependsOn(rootProject.tasks.named("installGitHook"))
}
