package io.github.mattpvaughn.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dependencies this project deliberately removed, kept out.
 *
 * The pattern of `ServiceLocatorUsageTest`: a carve is only finished if something stops it growing
 * back. Four entries, all from the same programme — decision-24 moved the transport, and the
 * serializer followed once a parsing regression could no longer be confused with a transport one.
 *
 * **Fetch2** was abandoned upstream — last commit 2024-12-03, no release after 3.4.1, and served
 * from JitPack, which builds from source on demand and guarantees nothing about an artifact
 * staying resolvable. That is why a 436 KB copy of it was vendored into `libs/fetch2-mirror/`
 * before it was replaced. An import creeping back would silently reintroduce a dead dependency,
 * a build-time network risk, and the `Int`-only grouping API that forced book ids through an
 * irreversible hash.
 *
 * **OkHttp in `app/src/main`** is the subtler one, and is *not* about OkHttp being bad — Ktor runs
 * on the OkHttp *engine*, so the library is still in the APK and still moves every byte. What must
 * not come back is app code written directly against it: `Interceptor`, `Authenticator`,
 * `OkHttpClient`. OkHttp 5.0 dropped Kotlin Multiplatform support, so every one of those types is
 * a JVM-only commitment in code that would otherwise be portable, and the point of moving to Ktor
 * was to stop making them. The engine is configuration; the API is a coupling.
 *
 * Test sources are exempt from the OkHttp rule on purpose: `MockWebServer` fronts the mock Plex
 * fixtures — including the debug one `plex-session.sh` drives — and a test needing a real socket is
 * a legitimate use that costs the app nothing.
 */
class RetiredDependencyTest {
  private val mainSource = File(MAIN_SOURCE_ROOT)

  private fun filesImporting(pkg: String): List<String> =
    mainSource.walkTopDown()
      .filter { it.extension == "kt" }
      .filter { file ->
        file.readLines().any { line ->
          val trimmed = line.trimStart()
          !trimmed.startsWith("*") && !trimmed.startsWith("//") && trimmed.startsWith("import $pkg")
        }
      }
      .map { it.name }
      .sorted()
      .toList()

  /** Guards the guard: a wrong source root would scan nothing and pass. */
  @Test
  fun `the source root resolves`() {
    assertTrue(
      "expected Kotlin sources under $MAIN_SOURCE_ROOT",
      mainSource.walkTopDown().count { it.extension == "kt" } > 100,
    )
  }

  @Test
  fun `no source imports Fetch2`() {
    assertEquals(
      "Fetch2 is abandoned and was replaced by the Downloader seam (decision-24). " +
        "Implement Downloader instead of importing an engine directly.",
      emptyList<String>(),
      filesImporting("com.tonyodev"),
    )
  }

  @Test
  fun `no source writes against the OkHttp API`() {
    assertEquals(
      "Ktor is the HTTP stack (decision-24). OkHttp is still the *engine* underneath it, but " +
        "app code must not name its types: OkHttp 5.0 dropped Kotlin Multiplatform support, so " +
        "an Interceptor or Authenticator here is a JVM-only commitment. Use a Ktor plugin.",
      emptyList<String>(),
      filesImporting("okhttp3"),
    )
  }

  @Test
  fun `no source imports Moshi`() {
    assertEquals(
      "kotlinx-serialization replaced Moshi. Moshi is JVM-only and codegen-based, so it kept " +
        "every model Android-side no matter what happened to the transport — the last thing " +
        "pinning the data layer to the JVM after Ktor. Annotate with @Serializable and parse " +
        "through ChronicleJson, whose settings (ignoreUnknownKeys, encodeDefaults) are the " +
        "file-format guarantees SettingsBackup and SeriesIndexRulesFile depend on.",
      emptyList<String>(),
      filesImporting("com.squareup.moshi"),
    )
  }

  @Test
  fun `no source imports Retrofit`() {
    assertEquals(
      "Ktorfit replaced Retrofit (decision-24) — Retrofit 3.0 is not KMP-capable, so it pinned " +
        "the whole network layer to the JVM. Declare endpoints on the Ktorfit service instead.",
      emptyList<String>(),
      filesImporting("retrofit2"),
    )
  }

  private companion object {
    const val MAIN_SOURCE_ROOT = "src/main/java/io/github/mattpvaughn/chronicle"
  }
}
