package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping the library into browsable facets (cu-24).
 *
 * The interesting cases are the untidy ones a hand-tagged library actually has: a book with two
 * narrators, a book with none, and a series whose books are numbered past ten.
 */
class BookFacetsTest {
  private fun book(
    id: String,
    title: String = "T$id",
    author: String = "Brandon Sanderson",
    narrator: String = "",
    series: String = "",
    seriesIndex: Int = 0,
    titleSort: String = "",
  ) = Audiobook(
    id = id,
    source = 1L,
    title = title,
    titleSort = titleSort,
    author = author,
    narrator = narrator,
    series = series,
    seriesIndex = seriesIndex,
  )

  // ---- author ----

  @Test
  fun `books group by author with counts`() {
    val books =
      listOf(
        book("1", author = "Tolkien"),
        book("2", author = "Tolkien"),
        book("3", author = "Herbert"),
      )

    val facets = books.facetsBy(FacetKind.Author)

    assertEquals(
      listOf(Facet("Tolkien", 2), Facet("Herbert", 1)),
      facets.facets,
    )
    assertFalse("every book has an author, so nothing is unknown", facets.isPartial)
  }

  /** Count first, then alphabetical — so the order is stable and can be learned. */
  @Test
  fun `equal counts order alphabetically`() {
    val books = listOf(book("1", author = "Zelazny"), book("2", author = "Adams"))

    assertEquals(
      listOf("Adams", "Zelazny"),
      books.facetsBy(FacetKind.Author).facets.map { it.value },
    )
  }

  // ---- narrator ----

  /**
   * A full-cast recording lists several narrators, and the book must appear under **each**. Storing
   * them joined and listing that string would put "Kate Reading, Michael Kramer" in the facet list
   * as if it were one person.
   */
  @Test
  fun `a book with several narrators appears under each`() {
    val books = listOf(book("1", narrator = "Kate Reading, Michael Kramer"))

    val facets = books.facetsBy(FacetKind.Narrator)

    assertEquals(
      listOf(Facet("Kate Reading", 1), Facet("Michael Kramer", 1)),
      facets.facets,
    )
  }

  @Test
  fun `narrator counts add up across books`() {
    val books =
      listOf(
        book("1", narrator = "Rob Inglis"),
        book("2", narrator = "Rob Inglis, Andy Serkis"),
        book("3", narrator = "Andy Serkis"),
      )

    assertEquals(
      listOf(Facet("Andy Serkis", 2), Facet("Rob Inglis", 2)),
      books.facetsBy(FacetKind.Narrator).facets,
    )
  }

  /**
   * The heart of the honesty requirement: narrator comes only from the per-book detail response, so
   * an unsynced library yields a partial index. The count travels with the list so a screen cannot
   * present 1 narrator out of 3 books as the whole truth.
   */
  @Test
  fun `books with no narrator are counted, not dropped`() {
    val books = listOf(book("1", narrator = "Rob Inglis"), book("2"), book("3"))

    val facets = books.facetsBy(FacetKind.Narrator)

    assertEquals(1, facets.facets.size)
    assertEquals(2, facets.unknownCount)
    assertTrue(facets.isPartial)
  }

  @Test
  fun `an entirely unsynced library yields an empty but honest list`() {
    val facets = listOf(book("1"), book("2")).facetsBy(FacetKind.Narrator)

    assertTrue(facets.facets.isEmpty())
    assertEquals(2, facets.unknownCount)
    assertTrue(facets.isPartial)
  }

  @Test
  fun `whitespace around a narrator name is not a separate narrator`() {
    val books = listOf(book("1", narrator = "Rob Inglis"), book("2", narrator = "  Rob Inglis  "))

    assertEquals(listOf(Facet("Rob Inglis", 2)), books.facetsBy(FacetKind.Narrator).facets)
  }

  // ---- series ----

  @Test
  fun `books group by series`() {
    val books =
      listOf(
        book("1", series = "Mistborn"),
        book("2", series = "Mistborn"),
        book("3", series = "Stormlight"),
      )

    assertEquals(
      listOf(Facet("Mistborn", 2), Facet("Stormlight", 1)),
      books.facetsBy(FacetKind.Series).facets,
    )
  }

  // ---- drilling in ----

  @Test
  fun `a facet resolves to its books`() {
    val books =
      listOf(
        book("1", narrator = "Rob Inglis"),
        book("2", narrator = "Andy Serkis"),
        book("3", narrator = "Rob Inglis, Andy Serkis"),
      )

    assertEquals(
      listOf("1", "3"),
      books.booksInFacet(FacetKind.Narrator, "Rob Inglis").map { it.id },
    )
  }

  /** A hand-tagged library capitalises inconsistently; a facet must still resolve. */
  @Test
  fun `resolving a facet ignores case`() {
    val books = listOf(book("1", series = "Mistborn"))

    assertEquals(1, books.booksInFacet(FacetKind.Series, "mistborn").size)
  }

  @Test
  fun `an unknown facet resolves to nothing`() {
    assertTrue(listOf(book("1")).booksInFacet(FacetKind.Series, "Nonexistent").isEmpty())
  }

  // ---- series order ----

  /**
   * The numeric case that a string sort gets wrong: book 2 must shelve before book 10.
   */
  @Test
  fun `a series orders by index, not by string`() {
    val books =
      listOf(
        book("c", seriesIndex = 10),
        book("a", seriesIndex = 2),
        book("b", seriesIndex = 1),
      )

    assertEquals(listOf("b", "a", "c"), books.inSeriesOrder().map { it.id })
  }

  /**
   * Falls back to the numeric-aware *title* comparison when no index was parsed — the same
   * comparator the library sort already uses, so "Book 2" still precedes "Book 10".
   */
  @Test
  fun `a series with no indices orders by natural title`() {
    val books =
      listOf(
        book("c", titleSort = "Mistborn, Book 10"),
        book("a", titleSort = "Mistborn, Book 2"),
        book("b", titleSort = "Mistborn, Book 1"),
      )

    assertEquals(listOf("b", "a", "c"), books.inSeriesOrder().map { it.id })
  }

  /**
   * An unnumbered extra — a novella, a companion volume — belongs at the **end** of a series, not
   * in front of book one.
   */
  @Test
  fun `an unnumbered book sorts after the numbered ones`() {
    val books =
      listOf(
        book("extra", titleSort = "Mistborn, Secret History"),
        book("one", seriesIndex = 1),
      )

    assertEquals(listOf("one", "extra"), books.inSeriesOrder().map { it.id })
  }

  // ---- the titleSort index parse ----

  @Test
  fun `a series index is read out of titleSort`() {
    assertEquals(2, Audiobook.seriesIndexFromTitleSort("Mistborn, Book 2"))
    assertEquals(2, Audiobook.seriesIndexFromTitleSort("Mistborn, Bk 2"))
    assertEquals(2, Audiobook.seriesIndexFromTitleSort("Mistborn #2"))
    assertEquals(2, Audiobook.seriesIndexFromTitleSort("Mistborn, 2"))
    assertEquals(10, Audiobook.seriesIndexFromTitleSort("Mistborn, Book 10"))
  }

  @Test
  fun `a titleSort with no index yields zero`() {
    assertEquals(0, Audiobook.seriesIndexFromTitleSort("Mistborn"))
    assertEquals(0, Audiobook.seriesIndexFromTitleSort(""))
  }

  /**
   * Anchored to the end deliberately: a series name containing a number must not be mistaken for
   * the position.
   */
  @Test
  fun `a number inside the series name is not the index`() {
    assertEquals(5, Audiobook.seriesIndexFromTitleSort("Book 2 of the Saga, Book 5"))
    assertEquals(
      "a leading number with no trailing one is not a position",
      0,
      Audiobook.seriesIndexFromTitleSort("2001: A Space Odyssey"),
    )
  }

  // ---- surviving a library refresh ----

  /**
   * The refresh-blanking risk. A library refresh merges from the **listing**, where `Style`/`Mood`
   * are always absent — so a network copy with no narrator must not overwrite one a previous
   * per-book sync learned. Getting this wrong empties the facet index on every refresh.
   */
  @Test
  fun `a refresh does not blank a known narrator`() {
    val local =
      book("1", narrator = "Rob Inglis", series = "The Hobbit", seriesIndex = 1)
        .copy(lastViewedAt = 1_000L)
    val fromListing = book("1").copy(lastViewedAt = 2_000L, title = "The Hobbit (remaster)")

    val merged = Audiobook.merge(network = fromListing, local = local)

    assertEquals("the newer listing metadata still wins", "The Hobbit (remaster)", merged.title)
    assertEquals("Rob Inglis", merged.narrator)
    assertEquals("The Hobbit", merged.series)
    assertEquals(1, merged.seriesIndex)
  }

  @Test
  fun `a refresh does not blank a known narrator when the local copy is newer`() {
    val local =
      book("1", narrator = "Rob Inglis", series = "The Hobbit", seriesIndex = 1)
        .copy(lastViewedAt = 2_000L)
    val fromListing = book("1").copy(lastViewedAt = 1_000L)

    val merged = Audiobook.merge(network = fromListing, local = local)

    assertEquals("Rob Inglis", merged.narrator)
    assertEquals("The Hobbit", merged.series)
    assertEquals(1, merged.seriesIndex)
  }

  /**
   * The other direction: a book re-tagged on the server must be correctable. Preferring the local
   * value unconditionally would make a fixed narrator impossible to pick up.
   */
  @Test
  fun `a detail sync overwrites a stale narrator`() {
    val local =
      book("1", narrator = "Wrong Person", series = "Old Name", seriesIndex = 1)
        .copy(lastViewedAt = 1_000L)
    val fromDetail =
      book("1", narrator = "Rob Inglis", series = "The Hobbit", seriesIndex = 2)
        .copy(lastViewedAt = 2_000L)

    val merged = Audiobook.merge(network = fromDetail, local = local)

    assertEquals("Rob Inglis", merged.narrator)
    assertEquals("The Hobbit", merged.series)
    assertEquals(2, merged.seriesIndex)
  }
}
