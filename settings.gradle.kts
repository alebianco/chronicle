pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  // repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // Vendored Fetch2 (cu-166). Upstream is abandoned — last commit 2024-12-03, and it is served
    // from JitPack, which builds from source on demand and guarantees nothing about an artifact
    // staying resolvable. Listed before jitpack so the local copy wins; jitpack stays as a
    // fallback for a version bump that will probably never come.
    maven(url = uri("$rootDir/libs/fetch2-mirror"))
    maven(url = "https://jitpack.io")
  }
}

rootProject.name = "Chronicle Audiobook Player"
include(":app")
