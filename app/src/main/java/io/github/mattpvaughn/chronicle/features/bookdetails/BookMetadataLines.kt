package io.github.mattpvaughn.chronicle.features.bookdetails

import io.github.mattpvaughn.chronicle.data.model.Audiobook

/**
 * What the book-details screen says about a book beyond its title (cu-145).
 *
 * Pure over [Audiobook] so the wording and — more importantly — the *absence* rules are testable
 * without a screen. The absence rules are the substance here: cu-24 populates narrator and series
 * only for books the user has opened, and cu-146's parser leaves `seriesIndex` unknown for any
 * title whose tagging carries no number, so **most books will have some of these missing** until
 * cu-143 seeds the index. A blank "Narrated by" line reads as "narrated by nobody", which is a
 * wrong claim rather than a missing one.
 */
object BookMetadataLines {
  /**
   * The narrator line, or null when unknown.
   *
   * Returns the bare names rather than a "Narrated by …" sentence: the label belongs in the
   * layout, so it can be styled separately and so a translation can reorder the two.
   */
  fun narrator(book: Audiobook): String? = book.narrator.trim().takeIf { it.isNotEmpty() }

  /**
   * The series line, or null when unknown.
   *
   * Three shapes rather than two, because the position is unknown far more often than the series
   * name is: Plex has no numeric series field and cu-146 reads the position out of `titleSort`, so
   * a book tagged with a series but no number is the common case, not an edge one.
   *
   * - series and position → `Mistborn, Book 2`
   * - series only         → `Mistborn`
   * - neither             → null
   *
   * A fractional position keeps its fraction (`Book 1.5` — a novella genuinely sits there) but a
   * whole number never shows a trailing `.0`.
   */
  fun series(book: Audiobook): String? {
    val name = book.series.trim().takeIf { it.isNotEmpty() } ?: return null
    val position = seriesPosition(book) ?: return name
    return "$name, Book $position"
  }

  /**
   * The book's position as a human number, or null when unknown.
   *
   * [Audiobook.seriesIndex] is stored in hundredths (cu-146), so 200 is book 2 and 150 the novella
   * between books 1 and 2. `NO_SERIES_INDEX` (0) means the tagging carried no number.
   */
  fun seriesPosition(book: Audiobook): String? {
    if (book.seriesIndex == Audiobook.NO_SERIES_INDEX) return null
    val whole = book.seriesIndex / Audiobook.SERIES_INDEX_SCALE
    val fraction = book.seriesIndex % Audiobook.SERIES_INDEX_SCALE
    if (fraction == 0) return whole.toString()
    // Trim a trailing zero so 150 reads "1.5" rather than "1.50".
    return "$whole.${(fraction / 10).takeIf { fraction % 10 == 0 } ?: fraction}"
  }

  /** Whether tapping the series line can lead anywhere — it needs a name to browse by. */
  fun hasBrowsableSeries(book: Audiobook): Boolean = series(book) != null
}
