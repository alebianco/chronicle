package io.github.mattpvaughn.chronicle.features.bookdetails

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a book's detail screen says about its narrator and series (cu-145).
 *
 * The absence cases outnumber the present ones on purpose. cu-24 populates narrator and series only
 * for books the user has opened, and cu-146 leaves the position unknown whenever the tagging
 * carries no number — so "nothing known" and "series but no number" are the *common* states until
 * cu-143 seeds the index, not edge cases.
 */
class BookMetadataLinesTest {
  private fun book(
    narrator: String = "",
    series: String = "",
    seriesIndex: Int = Audiobook.NO_SERIES_INDEX,
  ) = Audiobook(
    id = "1",
    source = 1L,
    title = "A Book",
    narrator = narrator,
    series = series,
    seriesIndex = seriesIndex,
  )

  // ---- narrator ----

  @Test
  fun `a known narrator is reported`() {
    assertEquals("Michael Kramer", BookMetadataLines.narrator(book(narrator = "Michael Kramer")))
  }

  @Test
  fun `several narrators are reported as stored`() {
    val full = book(narrator = "Kate Reading, Michael Kramer")

    assertEquals("Kate Reading, Michael Kramer", BookMetadataLines.narrator(full))
  }

  /** Absent, not blank — a "Narrated by" line with nothing after it claims nobody read the book. */
  @Test
  fun `an unknown narrator is null, not empty`() {
    assertNull(BookMetadataLines.narrator(book()))
  }

  @Test
  fun `a whitespace-only narrator counts as unknown`() {
    assertNull(BookMetadataLines.narrator(book(narrator = "   ")))
  }

  // ---- series ----

  @Test
  fun `a series with a position reads as name and book number`() {
    val b = book(series = "Mistborn", seriesIndex = 2 * Audiobook.SERIES_INDEX_SCALE)

    assertEquals("Mistborn, Book 2", BookMetadataLines.series(b))
  }

  /**
   * The common case, not an edge one.
   *
   * Plex has no numeric series field; the position is parsed out of `titleSort` and is absent
   * whenever the tagger wrote none. A book tagged with a series but no number must still say which
   * series it belongs to.
   */
  @Test
  fun `a series with no position reads as the name alone`() {
    assertEquals("Mistborn", BookMetadataLines.series(book(series = "Mistborn")))
  }

  @Test
  fun `no series at all is null`() {
    assertNull(BookMetadataLines.series(book()))
  }

  @Test
  fun `a position without a series name is not reported`() {
    // A number with nothing to number is meaningless on its own.
    assertNull(BookMetadataLines.series(book(seriesIndex = 3 * Audiobook.SERIES_INDEX_SCALE)))
  }

  @Test
  fun `a double-digit position reads correctly`() {
    val b = book(series = "Discworld", seriesIndex = 10 * Audiobook.SERIES_INDEX_SCALE)

    assertEquals("Discworld, Book 10", BookMetadataLines.series(b))
  }

  // ---- fractional positions ----

  /** A novella between two books genuinely sits at 1.5 — cu-146 stores it in hundredths. */
  @Test
  fun `a novella keeps its fractional position`() {
    val b = book(series = "Mistborn", seriesIndex = 150)

    assertEquals("Mistborn, Book 1.5", BookMetadataLines.series(b))
  }

  @Test
  fun `a whole position shows no trailing decimal`() {
    val b = book(series = "Mistborn", seriesIndex = 200)

    assertEquals("Mistborn, Book 2", BookMetadataLines.series(b))
  }

  @Test
  fun `a two-decimal position keeps both digits`() {
    val b = book(series = "Mistborn", seriesIndex = 125)

    assertEquals("Mistborn, Book 1.25", BookMetadataLines.series(b))
  }

  // ---- position on its own ----

  @Test
  fun `an unknown position is null`() {
    assertNull(BookMetadataLines.seriesPosition(book(series = "Mistborn")))
  }

  @Test
  fun `a known position is a plain number`() {
    assertEquals("4", BookMetadataLines.seriesPosition(book(seriesIndex = 400)))
  }

  // ---- browsability ----

  @Test
  fun `a book with a series can be browsed to it`() {
    assertTrue(BookMetadataLines.hasBrowsableSeries(book(series = "Mistborn")))
  }

  @Test
  fun `a book with no series cannot`() {
    assertFalse(BookMetadataLines.hasBrowsableSeries(book()))
  }

  /** The state most books are in today: nothing known, so nothing claimed. */
  @Test
  fun `a book with neither reports neither`() {
    val b = book()

    assertNull(BookMetadataLines.narrator(b))
    assertNull(BookMetadataLines.series(b))
    assertNull(BookMetadataLines.seriesPosition(b))
  }
}
