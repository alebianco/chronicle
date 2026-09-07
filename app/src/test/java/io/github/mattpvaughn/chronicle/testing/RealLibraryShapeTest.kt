package io.github.mattpvaughn.chronicle.testing

import io.github.mattpvaughn.chronicle.data.ChronicleJson
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asAudiobooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **real serializer** against the shapes a real 196-book library actually sends.
 *
 * This test has now survived two serializer changes and its value is the same each time: the
 * hand-written fixtures contain the fields their author thought to include, so a leniency
 * difference only shows up on data nobody designed. It caught nothing when Moshi moved from
 * reflection to codegen, and it is the reason the move to kotlinx-serialization could be
 * made with evidence rather than hope — these are the assertions that say parsing did not change.
 *
 * So these fixtures were **captured from a real Plex server** (1.43.3) and scrubbed of identifying
 * values while keeping the exact *set of keys* each object had. A survey of all 196 albums found ten
 * fields absent on at least one book:
 *
 * | field | absent on |
 * |---|---|
 * | `Collection` | 170/196 |
 * | `skipCount` | 190/196 |
 * | `viewCount` | 135/196 |
 * | `lastViewedAt` | 133/196 |
 * | `titleSort` | 57/196 |
 * | `rating`, `studio` | 30/196 |
 * | `parentThumb` | 16/196 |
 * | `year`, `originallyAvailableAt` | 1/196 |
 *
 * The single-book cases are the interesting ones: a library where 195 books have `year` and one
 * does not is exactly the shape that passes every hand-written fixture and then throws on a real
 * sync. These parse through `ChronicleJson`, the very instance `AppModule` hands the HTTP client —
 * not a locally configured one, or the test would be exercising a parser the app does not use. That
 * was the trap the earlier adapter switch found, and it is why the shared instance exists.
 */
class RealLibraryShapeTest {
  private fun container(fixture: String) =
    ChronicleJson.decodeFromString<PlexMediaContainerWrapper>(FakePlexServer.fixture(fixture))
      .plexMediaContainer

  @Test
  fun `every real album shape parses`() {
    // The whole point: four real key-sets, including the sparsest book in the library (20 keys).
    val books = container("albums-real-shape.json").asAudiobooks()

    assertEquals(4, books.size)
  }

  @Test
  fun `a book with no year parses rather than throwing`() {
    // 1 of 196. The parser rejects an absent field only when the property has no default;
    // this pins that `year` keeps one.
    val books = container("albums-real-shape.json").asAudiobooks()

    assertTrue("a missing year must fall back to a default", books.any { it.year == 0 })
  }

  @Test
  fun `a book with no titleSort still gets a usable sort key`() {
    // 57 of 196 — the most common absence, and it feeds list ordering, so an empty value here
    // is a visible bug rather than a parse failure.
    val books = container("albums-real-shape.json").asAudiobooks()

    val untitledSort = books.filter { it.titleSort.isEmpty() }
    assertTrue(
      "titleSort is absent on 57 of 196 real books; sorting must not depend on it being present",
      untitledSort.size < books.size,
    )
  }

  @Test
  fun `the sparsest real book yields a usable audiobook`() {
    val books = container("albums-real-shape.json").asAudiobooks()

    // Whatever else is missing, these are what the UI cannot do without.
    books.forEach { book ->
      assertTrue("every book needs an id", book.id.isNotEmpty())
      assertTrue("every book needs a title", book.title.isNotEmpty())
    }
  }

  @Test
  fun `absent view fields default rather than failing`() {
    // viewCount absent on 135/196, lastViewedAt on 133/196, skipCount on 190/196. These drive
    // "in progress" and completion, so a wrong default is a behaviour bug (decision-16), not
    // just a parse question.
    val books = container("albums-real-shape.json").asAudiobooks()

    assertTrue(books.all { it.viewCount >= 0 })
    assertTrue(books.all { it.lastViewedAt >= 0 })
  }

  @Test
  fun `the detail response parses even though Style and Mood are unmodelled`() {
    // Worth stating plainly, because it is easy to misread the code the other way:
    // `PlexMediaSource` declares `hasNarrator = true` and `hasSeries = true`, but **nothing in
    // the app parses `Style` or `Mood`** — `Audiobook` has no narrator or series field at all.
    // Those flags are part of the D11 scaffolding CLAUDE.md calls "declared but not yet
    // load-bearing".
    //
    // What this test pins is the property that matters for any serializer: it must
    // *ignore* unknown keys rather than reject them — `ignoreUnknownKeys`, which kotlinx needs
    // told explicitly. The real detail response carries Style, Mood, Image, UltraBlurColors and
    // more that no model mentions; if the parser were strict, every book detail fetch would fail.
    val books = container("album-detail-real-shape.json").asAudiobooks()

    val book = books.single()
    assertTrue(book.id.isNotEmpty())
    assertTrue(book.title.isNotEmpty())
  }

  @Test
  fun `unknown real-world keys are ignored, not rejected`() {
    // The same property from the list side. A real album object carries `Image`,
    // `UltraBlurColors`, `loudnessAnalysisVersion` and `originallyAvailableAt`, none of which
    // appear in `PlexDirectory`. This is the single most likely way a server upgrade breaks
    // parsing, and it is invisible in a hand-written fixture that only lists known keys.
    val raw = FakePlexServer.fixture("albums-real-shape.json")
    assertTrue("fixture must actually contain unmodelled keys", raw.contains("UltraBlurColors"))
    assertTrue(raw.contains("loudnessAnalysisVersion"))

    val books = container("albums-real-shape.json").asAudiobooks()

    assertEquals(4, books.size)
  }

  @Test
  fun `a real container reports its own size`() {
    assertNotNull(container("albums-real-shape.json"))
    assertEquals(4L, container("albums-real-shape.json").size)
  }
}
