package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the parsing-rules tester offers the user, and the numbers it reports. */
class SeriesIndexDiagnosticsTest {
  private fun book(titleSort: String) = EMPTY_AUDIOBOOK.copy(id = titleSort.ifEmpty { "blank" }, titleSort = titleSort)

  /** The audnexus shape, which dominates real libraries (111 of 139). */
  private val parses = "The Age of Madness, Book 3 - The Wisdom of Crowds"

  @Test
  fun `only titles that parse to no position are offered`() {
    val samples =
      SeriesIndexDiagnostics.unparsedTitleSorts(
        listOf(book(parses), book("A Standalone Novel")),
      )

    assertEquals(listOf("A Standalone Novel"), samples)
  }

  /** Nothing for a rule to match, so offering it would pad the list uselessly. */
  @Test
  fun `books with no titleSort are not offered`() {
    val samples = SeriesIndexDiagnostics.unparsedTitleSorts(listOf(book(""), book("   ")))

    assertEquals(emptyList<String>(), samples)
  }

  /**
   * A uniformly tagged series contributes the same *shape* many times; one example is enough to
   * test a rule against, and 20 copies would crowd out the shapes that differ.
   */
  @Test
  fun `duplicate titles are collapsed`() {
    val samples =
      SeriesIndexDiagnostics.unparsedTitleSorts(
        listOf(book("Same Shape"), book("Same Shape"), book("Other Shape")),
      )

    assertEquals(listOf("Same Shape", "Other Shape"), samples)
  }

  @Test
  fun `the sample is capped`() {
    val many = (1..100).map { book("Unparseable Title $it") }

    assertEquals(SeriesIndexDiagnostics.SAMPLE_LIMIT, SeriesIndexDiagnostics.unparsedTitleSorts(many).size)
    assertEquals(3, SeriesIndexDiagnostics.unparsedTitleSorts(many, limit = 3).size)
  }

  @Test
  fun `the summary counts parsed and unparsed separately from missing titleSorts`() {
    val summary =
      SeriesIndexDiagnostics.summarise(
        listOf(book(parses), book("A Standalone Novel"), book("")),
      )

    assertEquals(3, summary.total)
    assertEquals(2, summary.withTitleSort)
    assertEquals(1, summary.parsed)
    assertEquals(1, summary.unparsed)
  }

  /**
   * `unparsed` must not be presented as a defect count. A standalone novel has no series position
   * to find, so a library can be perfectly tagged and still report a large number here — 58 of 196
   * on the owner's own library.
   */
  @Test
  fun `a library of standalones is entirely unparsed and that is not an error`() {
    val summary = SeriesIndexDiagnostics.summarise((1..5).map { book("Standalone $it") })

    assertEquals(5, summary.unparsed)
    assertEquals(0, summary.parsed)
    assertTrue("the total still accounts for every book", summary.withTitleSort == summary.unparsed)
  }

  @Test
  fun `an empty library summarises to zeroes`() {
    val summary = SeriesIndexDiagnostics.summarise(emptyList())

    assertEquals(0, summary.total)
    assertEquals(0, summary.unparsed)
    assertFalse(summary.parsed > 0)
  }
}
