package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.SERIES_INDEX_SCALE
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.seriesIndexFromTitleSort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading a book's series position out of `titleSort` (cu-146).
 *
 * There is **no numeric series field in Plex** — album `index` is 1 on essentially every audiobook
 * — so this string is the only carrier, written by whichever tagger the user ran. The cases below
 * are the formats those taggers actually produce, not invented ones:
 *
 * - **Audnexus** (auto-generated, so the commonest by construction) writes
 *   `"<Series>, Book <n> - <Title>"`, verified in its `update_tools.py`.
 * - **seanap's guide** prescribes `%Series% %Series-part% - %Title%`.
 * - Audiobookshelf-shaped trees leave a bare leading `"01 - Title"`.
 *
 * The parser this replaces was anchored to the **end** of the string and therefore read 1 of 8
 * real formats. It passed its tests because the only fixture exercising it happened to end with
 * the number — the cu-24 trap (a fixture written to match the code proves nothing) in a new field.
 */
class SeriesIndexParserTest {
  /** Reads the parser's hundredths back as a human-facing number, for legible assertions. */
  private fun positionOf(titleSort: String): Double = seriesIndexFromTitleSort(titleSort).toDouble() / SERIES_INDEX_SCALE

  private fun assertPosition(
    expected: Double,
    titleSort: String,
  ) = assertEquals("titleSort=$titleSort", expected, positionOf(titleSort), 0.0001)

  private fun assertUnknown(titleSort: String) =
    assertEquals("titleSort=$titleSort should be unknown", 0, seriesIndexFromTitleSort(titleSort))

  // ---- the two dominant taggers ----

  @Test
  fun `reads the Audnexus form`() {
    assertPosition(2.0, "Mistborn, Book 2 - The Well of Ascension")
  }

  @Test
  fun `reads the Audnexus form with no title after it`() {
    // The shape captured from a real server.
    assertPosition(2.0, "Fixture Series, Book 2")
  }

  @Test
  fun `reads the seanap form`() {
    assertPosition(1.0, "Expanse 1 - Leviathan Wakes")
  }

  @Test
  fun `reads the seanap form when hand-padded`() {
    assertPosition(1.0, "Jack Reacher 01 - Killing Floor")
  }

  // ---- other real shapes ----

  @Test
  fun `reads a bare leading number`() {
    assertPosition(1.0, "01 - Book Title")
  }

  @Test
  fun `reads a leading number followed by a dot`() {
    assertPosition(1.0, "1. Wizards First Rule")
  }

  @Test
  fun `reads a label-first form`() {
    assertPosition(5.0, "Book 5: Sourcery: Discworld")
  }

  @Test
  fun `reads a label in the middle, past a leading year`() {
    assertPosition(1.0, "1994 - Book 1 - Wizards First Rule")
  }

  @Test
  fun `reads a hash form`() {
    assertPosition(2.0, "Book Title (Mistborn #2)")
  }

  @Test
  fun `reads a hash form with no brackets`() {
    assertPosition(4.0, "Stormlight Archive #4")
  }

  @Test
  fun `reads a Vol label`() {
    assertPosition(2.0, "Mistborn Vol. 2 - Well of Ascension")
  }

  // ---- forms the previous parser accepted, which must keep working ----
  //
  // Un-anchoring the regex silently dropped two of these; BookFacetsTest caught it. A rewrite that
  // gains eight formats and loses two is not an improvement.

  @Test
  fun `reads an abbreviated Bk label`() {
    assertPosition(2.0, "Mistborn, Bk 2")
  }

  @Test
  fun `reads a trailing bare number after a comma`() {
    assertPosition(2.0, "Mistborn, 2")
  }

  @Test
  fun `reads a two-digit position after a label`() {
    assertPosition(10.0, "Mistborn, Book 10")
  }

  @Test
  fun `a leading number with no separator is not a position`() {
    assertUnknown("2001: A Space Odyssey")
  }

  // ---- padding and decimals ----

  @Test
  fun `zero padding does not change the position`() {
    assertEquals(seriesIndexFromTitleSort("Series 2 - T"), seriesIndexFromTitleSort("Series 02 - T"))
    assertEquals(seriesIndexFromTitleSort("Series 2 - T"), seriesIndexFromTitleSort("Series 002 - T"))
  }

  /**
   * A novella between two books is a real position, not a rounding error.
   *
   * Audnexus' own volume regex admits `1.5`, so an `Int` field truncating it to 1 would collide
   * with book one. Hundredths keep it exact while staying an integer compare.
   */
  @Test
  fun `reads a decimal position`() {
    assertPosition(1.5, "Mistborn, Book 1.5 - Secret History")
  }

  @Test
  fun `a decimal position sorts between its neighbours`() {
    val one = seriesIndexFromTitleSort("Mistborn, Book 1 - The Final Empire")
    val novella = seriesIndexFromTitleSort("Mistborn, Book 1.5 - Secret History")
    val two = seriesIndexFromTitleSort("Mistborn, Book 2 - The Well of Ascension")

    assertEquals(true, one < novella)
    assertEquals(true, novella < two)
  }

  /** An omnibus sorts where its first book does, because it contains that book. */
  @Test
  fun `reads a range as its first book`() {
    assertPosition(1.0, "Mistborn, Book 1-3 - The Trilogy")
  }

  // ---- what must NOT be read ----

  @Test
  fun `a standalone book has no position`() {
    assertUnknown("Standalone Book")
  }

  @Test
  fun `a title starting with a number is not a position`() {
    // The number is part of the name, and no separator follows it.
    assertUnknown("101 Dalmatians")
  }

  @Test
  fun `a year in brackets is not a position`() {
    assertUnknown("Foundation (1951)")
  }

  @Test
  fun `an ordinary sort title has no position`() {
    assertUnknown("Hobbit, The")
    assertUnknown("Fixture Book, Sorted")
  }

  @Test
  fun `an empty titleSort has no position`() {
    assertUnknown("")
  }

  /**
   * A series whose own *name* contains a number must not have it read as the position.
   *
   * This was the stated reason the old parser anchored to the end. Un-anchoring keeps the
   * protection by preferring the labelled forms: the `, Book 5` here wins over the leading `2`.
   */
  @Test
  fun `a number inside the series name is not the position`() {
    assertPosition(5.0, "Book 2 of the Fixture Saga, Book 5")
  }

  /**
   * `Book 0` reads as unknown, which is a deliberate limitation rather than an oversight.
   *
   * Some series do number a prequel zero, and this sorts it *last* instead of first. Fixing it
   * properly means a separate "known but zero" state, because 0 is the unknown sentinel — not
   * worth a nullable column and a migration for a rare tagging choice. Recorded so the next
   * reader does not treat it as a bug to be quietly patched.
   */
  @Test
  fun `book zero reads as unknown, a known limitation`() {
    assertUnknown("Prequel Series, Book 0 - The Beginning")
  }

  @Test
  fun `a four-digit number is not a position`() {
    // Series positions are not thousands; a bare 4-digit run is a year or part of a name.
    assertUnknown("1984 - A Novel")
  }
}
