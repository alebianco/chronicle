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
    // No JitPack. It was here only for Fetch2, which decision-24 replaced — along with the
    // 436 KB vendored mirror of it that lived in `libs/fetch2-mirror/`, added because JitPack
    // builds from source on demand and guarantees nothing about an artifact staying resolvable.
    // Every dependency now comes from google() or mavenCentral(), which is the point: nothing in
    // this build is served by a host that compiles it for us.
  }
}

rootProject.name = "Chronicle Audiobook Player"
include(":app")
