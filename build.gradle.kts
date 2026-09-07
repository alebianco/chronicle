plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false

  alias(libs.plugins.ktlint)
  alias(libs.plugins.detekt)
}

allprojects {
  apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

buildscript {
  dependencies {
    classpath(libs.oss.plugin)

    // The OSS-licences plugin drags in a JavaPoet old enough that Hilt's Gradle plugin fails in
    // `hiltAggregateDepsDebug` with `NoSuchMethodError: ClassName.canonicalName()`. Both are on
    // the same buildscript classpath, so the newer one has to win explicitly.
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
