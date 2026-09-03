package io.github.mattpvaughn.chronicle.data.sources.plex.model

import com.squareup.moshi.Moshi
import io.github.mattpvaughn.chronicle.testing.FakePlexServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrator and series out of `Style`/`Mood` (cu-24).
 *
 * Pinned against the fixtures **captured from a real Plex 1.43.3 server**, not hand-written ones.
 * That distinction matters here more than anywhere: `plexGenres` had no `@Json(name = "Genre")` for
 * the life of the project and every test passed, because the hand-written fixtures were written to
 * match the code rather than the wire. A test that invents its own JSON cannot catch that class of
 * bug.
 */
class AudnexusTagsTest {
  private val moshi = Moshi.Builder().build()

  private fun container(fixture: String) =
    moshi.adapter(PlexMediaContainerWrapper::class.java)
      .fromJson(FakePlexServer.fixture(fixture))!!
      .plexMediaContainer

  private fun realDetailBook() = container("album-detail-real-shape.json").metadata.single()

  // ---- against real captured data ----

  @Test
  fun `a real detail response yields its narrator`() {
    assertEquals(listOf("Fixture Narrator"), realDetailBook().narrators())
  }

  @Test
  fun `a real detail response yields its series, prefix stripped`() {
    assertEquals("Fixture Series", realDetailBook().seriesName())
  }

  /**
   * The constraint the whole feature is shaped around: the **listing** carries neither tag, so a
   * facet index cannot be built from a library refresh. If a future Plex version starts sending
   * them, this test failing is the signal that the design can be simplified.
   */
  @Test
  fun `the library listing carries neither tag`() {
    val listed = container("albums-real-shape.json").metadata

    assertTrue("the fixture must have books, or this proves nothing", listed.isNotEmpty())
    assertTrue(
      "Style/Mood are detail-only on Plex 1.43.3; if this fails, the facet index can be built " +
        "from a refresh instead of from per-book syncs",
      listed.all { it.narrators().isEmpty() && it.seriesName().isEmpty() },
    )
  }

  /**
   * The regression guard for the `Genre` name. Asserted against captured data because that is the
   * only place the real key appears — the hand-written fixtures still say `plexGenres`.
   */
  @Test
  fun `genre parses from the key Plex actually sends`() {
    val json =
      """
      {"MediaContainer":{"size":1,"Metadata":[
        {"ratingKey":"1","type":"album","title":"X",
         "Genre":[{"tag":"Fantasy"},{"tag":"Adventure"}]}
      ]}}
      """.trimIndent()

    val book =
      moshi.adapter(PlexMediaContainerWrapper::class.java).fromJson(json)!!
        .plexMediaContainer.metadata.single()

    assertEquals(
      "Plex sends `Genre`; a missing @Json name made this silently empty until cu-24",
      listOf("Fantasy", "Adventure"),
      book.plexGenres.map { it.tag },
    )
  }

  // ---- the prefix convention ----

  @Test
  fun `the series prefix is stripped in the forms taggers use`() {
    assertEquals("Mistborn", stripSeriesPrefix("Series: Mistborn"))
    assertEquals("Mistborn", stripSeriesPrefix("series: Mistborn"))
    assertEquals("Mistborn", stripSeriesPrefix("SERIES: Mistborn"))
    assertEquals("Mistborn", stripSeriesPrefix("Series - Mistborn"))
    assertEquals("Mistborn", stripSeriesPrefix("Series Mistborn"))
    assertEquals("Mistborn", stripSeriesPrefix("  Series:   Mistborn  "))
  }

  /** A tag with no prefix is a series name in its own right. */
  @Test
  fun `a tag without a prefix is taken as-is`() {
    assertEquals("Mistborn", stripSeriesPrefix("Mistborn"))
  }

  /**
   * Stripping must not swallow a name that merely begins with those letters, or "Serious Business"
   * becomes "ous Business".
   */
  @Test
  fun `a name that starts like the prefix survives`() {
    assertEquals("Serious Business", stripSeriesPrefix("Serious Business"))
  }

  /** A prefix with nothing after it is not a series. */
  @Test
  fun `a bare prefix yields no series`() {
    assertEquals("", stripSeriesPrefix("Series:"))
    assertEquals("", stripSeriesPrefix("Series: "))
  }

  // ---- shapes a hand-tagged library actually has ----

  @Test
  fun `several narrators are all kept`() {
    val book =
      PlexDirectory(
        plexStyles = listOf(PlexTag("Kate Reading"), PlexTag("Michael Kramer")),
      )

    assertEquals(listOf("Kate Reading", "Michael Kramer"), book.narrators())
  }

  @Test
  fun `blank and duplicate narrator tags are dropped`() {
    val book =
      PlexDirectory(
        plexStyles = listOf(PlexTag("Rob Inglis"), PlexTag("  "), PlexTag("Rob Inglis")),
      )

    assertEquals(listOf("Rob Inglis"), book.narrators())
  }

  @Test
  fun `no tags means no narrator and no series`() {
    val book = PlexDirectory()

    assertTrue(book.narrators().isEmpty())
    assertEquals("", book.seriesName())
  }

  /**
   * Several moods: the first usable one wins. A book belongs to one series in this convention, and
   * picking arbitrarily from a set would make the facet list reshuffle between syncs.
   */
  @Test
  fun `the first usable mood tag becomes the series`() {
    val book =
      PlexDirectory(plexMoods = listOf(PlexTag("Series:"), PlexTag("Series: Mistborn"), PlexTag("Series: Other")))

    assertEquals("Mistborn", book.seriesName())
  }
}
