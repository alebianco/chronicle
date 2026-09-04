package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The model layer builds and works without a DI graph (cu-79).
 *
 * Three sites reached into `Injector` from a data class: a Room type converter fetching Moshi, and
 * `MediaItemTrack.getTrackSource` fetching both the cached-media directory and — worse — a
 * *Plex-specific* `PlexConfig` from inside a *domain* model, which is precisely the coupling the
 * `MediaSource` seam exists to remove (cu-15, decision-11).
 *
 * These tests do not mention `Injector` and never stand up `ChronicleApplication`, which is the
 * whole assertion: before this, none of them could have been written this way.
 *
 * Robolectric is here only for `Uri.fromFile`, which the cached path goes through deliberately: a
 * bare path gives `scheme = null` and ExoPlayer refuses it (cu-83). Nothing needs a DI graph —
 * that is the point — but the framework's own Uri parser is not a stub.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsWithoutDiTest {
  private fun track(
    id: String = "2001",
    cached: Boolean = false,
    media: String = "/library/parts/1/file.mp3",
  ) = MediaItemTrack(id = id, parentKey = "1001", cached = cached, media = media)

  // ---- the Room type converter ----

  /**
   * The stored form, pinned.
   *
   * The converter builds its own `Moshi` now rather than borrowing the application's. That is safe
   * only while the shared instance has no adapter that would write a string list differently — so
   * this asserts the *exact* stored text, which is what would change if that assumption broke.
   */
  @Test
  fun `child ids serialize to a plain JSON array`() {
    val converter = CollectionIdConverter()

    assertEquals("""["1001","1002"]""", converter.fromList(listOf("1001", "1002")))
  }

  @Test
  fun `child ids round-trip`() {
    val converter = CollectionIdConverter()
    val ids = listOf("1001", "abc-def", "1003")

    assertEquals(ids, converter.toList(converter.fromList(ids)))
  }

  @Test
  fun `an empty list round-trips`() {
    val converter = CollectionIdConverter()

    assertEquals(emptyList<String>(), converter.toList(converter.fromList(emptyList())))
  }

  /** A non-numeric id survives, which is the point of the cu-71 retype. */
  @Test
  fun `a non-numeric id round-trips`() {
    val converter = CollectionIdConverter()

    assertEquals(listOf("abc"), converter.toList(converter.fromList(listOf("abc"))))
  }

  // ---- the track source ----

  /**
   * A cached track resolves to a local file, with its scheme.
   *
   * `file://` is load-bearing: a bare path gives `scheme = null` and ExoPlayer refuses it as an
   * unsupported format, on downloaded books only (cu-83).
   */
  @Test
  fun `a cached track resolves to a local file uri`() {
    val dir = File("/storage/emulated/0/Android/data/chronicle/files")
    val plexConfig = mockk<PlexConfig>(relaxed = true)

    val source = track(cached = true).getTrackSource(dir, plexConfig)

    assertTrue("expected a file:// uri, got $source", source.startsWith("file://"))
    assertTrue("expected the cached filename, got $source", source.endsWith("2001.mp3"))
  }

  @Test
  fun `an uncached track resolves through the server`() {
    val plexConfig =
      mockk<PlexConfig> {
        every { toServerString(any()) } returns "https://server/library/parts/1/file.mp3"
      }

    val source = track(cached = false).getTrackSource(File("/unused"), plexConfig)

    assertEquals("https://server/library/parts/1/file.mp3", source)
  }

  /**
   * The directory is the caller's, not one the model went and found.
   *
   * The behavioural half of the change: two different directories must give two different results
   * for the same track, which was impossible while the model read a single global.
   */
  @Test
  fun `the cached directory comes from the caller`() {
    val plexConfig = mockk<PlexConfig>(relaxed = true)
    val cached = track(cached = true)

    val internal = cached.getTrackSource(File("/data/internal"), plexConfig)
    val sdCard = cached.getTrackSource(File("/storage/sdcard"), plexConfig)

    assertTrue(internal.contains("/data/internal"))
    assertTrue(sdCard.contains("/storage/sdcard"))
  }

  /**
   * The model layer stays free of the DI graph.
   *
   * A source guard, because the defect is *reachability*, not behaviour: a single
   * `Injector.get()` anywhere under `data/model/` makes that model unconstructable in a test
   * without standing up `ChronicleApplication`, and every test above would have to become an
   * instrumented one. Nothing that exercises the models can notice it being reintroduced.
   */
  @Test
  fun `no model reaches into the DI graph`() {
    val offenders =
      File("src/main/java/io/github/mattpvaughn/chronicle/data/model")
        .walkTopDown()
        .filter { it.extension == "kt" }
        .filter { it.readText().contains("Injector.") }
        .map { it.name }
        .toList()

    assertEquals(
      "a model reached into Injector again — take the dependency as a parameter instead, the way " +
        "getTrackSource and toMediaMetadata do (cu-79)",
      emptyList<String>(),
      offenders,
    )
  }
}
