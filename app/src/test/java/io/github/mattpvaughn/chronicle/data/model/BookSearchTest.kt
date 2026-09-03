package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typo-tolerant, grouped search (cu-25).
 *
 * The cases that matter are the ones a `LIKE %q%` gets wrong: a transposed letter, a missing one,
 * a query that matches a narrator rather than a title, and a query short enough that being
 * generous would match the whole library.
 */
class BookSearchTest {
  private fun book(
    id: String,
    title: String = "T$id",
    author: String = "",
    narrator: String = "",
    series: String = "",
    seriesIndex: Int = 0,
    isCached: Boolean = false,
  ) = Audiobook(
    id = id,
    source = 1L,
    title = title,
    titleSort = title,
    author = author,
    narrator = narrator,
    series = series,
    seriesIndex = seriesIndex,
    isCached = isCached,
  )

  /** A book in the Mistborn series, for the ordering cases below. */
  private fun seriesBook(
    id: String,
    title: String,
    index: Int,
  ) = book(id = id, title = title, series = "Mistborn", seriesIndex = index)

  private val library =
    listOf(
      book("1", title = "The Hobbit", author = "J R R Tolkien", narrator = "Rob Inglis"),
      book("2", title = "Dune", author = "Frank Herbert", narrator = "Simon Vance"),
      book(
        "3",
        title = "Mistborn Book 10",
        author = "Brandon Sanderson",
        narrator = "Michael Kramer",
        series = "Mistborn",
      ),
    )

  // ---- typo tolerance ----

  @Test
  fun `an exact title match is found`() {
    val results = library.searchFuzzy("Dune")

    assertEquals(listOf("2"), results.map { it.book.id })
  }

  @Test
  fun `a missing letter still matches the title`() {
    val results = library.searchFuzzy("hobit")

    assertEquals(listOf("1"), results.map { it.book.id })
  }

  @Test
  fun `a transposed letter still matches the title`() {
    val results = library.searchFuzzy("Dnue")

    assertEquals(listOf("2"), results.map { it.book.id })
  }

  @Test
  fun `a typo in an author name matches`() {
    val results = library.searchFuzzy("Sandersen")

    assertEquals(listOf("3"), results.map { it.book.id })
  }

  @Test
  fun `a narrator name matches the book they read`() {
    val results = library.searchFuzzy("Kramer")

    assertEquals(listOf("3"), results.map { it.book.id })
  }

  @Test
  fun `a series name matches its books`() {
    val results = library.searchFuzzy("Mistborn")

    assertEquals(listOf("3"), results.map { it.book.id })
  }

  @Test
  fun `an unrelated query matches nothing`() {
    assertTrue(library.searchFuzzy("zzzzqqq").isEmpty())
  }

  /**
   * A one- or two-character query must stay strict.
   *
   * At an edit distance of two, "ab" is within reach of almost any short word, so a generous
   * threshold would answer the entire library for the first keystroke — which reads as the filter
   * being broken rather than as a wide match.
   */
  @Test
  fun `a very short query does not match everything`() {
    val results = library.searchFuzzy("Du")

    assertEquals(listOf("2"), results.map { it.book.id })
  }

  @Test
  fun `a blank query matches nothing`() {
    assertTrue(library.searchFuzzy("   ").isEmpty())
  }

  // ---- ranking ----

  @Test
  fun `an exact match outranks a fuzzy one`() {
    val books =
      listOf(
        book("fuzzy", title = "Dunes of Mars"),
        book("exact", title = "Dune"),
      )

    val results = books.searchFuzzy("Dune")

    assertEquals("exact", results.first().book.id)
  }

  @Test
  fun `a title match outranks an author match`() {
    val books =
      listOf(
        book("byAuthor", title = "Elantris", author = "Dune Author"),
        book("byTitle", title = "Dune", author = "Frank Herbert"),
      )

    val results = books.searchFuzzy("Dune")

    assertEquals("byTitle", results.first().book.id)
  }

  /**
   * Field order must beat match quality, not merely tie-break it.
   *
   * A first cut declared a field weight and never applied it, so this ordering held only by input
   * order — a fuzzy title match and an exact series match scored identically.
   */
  @Test
  fun `a fuzzy title match outranks an exact series match`() {
    val books =
      listOf(
        book("series", title = "Elantris", series = "Mistborn"),
        book("title", title = "Mistbrn"),
      )

    val results = books.searchFuzzy("Mistborn")

    assertEquals("title", results.first().book.id)
  }

  @Test
  fun `an author match outranks a narrator match of the same quality`() {
    val books =
      listOf(
        book("narr", title = "Elantris", narrator = "Frank Herbert"),
        book("auth", title = "Dune", author = "Frank Herbert"),
      )

    val results = books.searchFuzzy("Frank Herbert")

    assertEquals("auth", results.first().book.id)
  }

  @Test
  fun `a result reports which field it matched`() {
    val results = library.searchFuzzy("Kramer")

    assertEquals(SearchField.Narrator, results.first().matchedField)
  }

  @Test
  fun `ranking is stable for equally good matches`() {
    val books =
      listOf(
        book("b", title = "Dune"),
        book("a", title = "Dune"),
      )

    val first = books.searchFuzzy("Dune").map { it.book.id }
    val second = books.searchFuzzy("Dune").map { it.book.id }

    assertEquals(first, second)
  }

  // ---- series ordering ----

  /**
   * A series group is in reading order, not alphabetical.
   *
   * Reading order is the whole reason to search a series name. Sorting by title put *The Hero of
   * Ages* (book 3) between books 1 and 2, which is worse than useless: it reads as an ordering, so
   * the user trusts it.
   */
  @Test
  fun `a series group is ordered by series index`() {
    val books =
      listOf(
        seriesBook("3", "The Hero of Ages", index = 3),
        seriesBook("1", "The Final Empire", index = 1),
        seriesBook("2", "The Well of Ascension", index = 2),
      )

    val group = books.groupedSearch("Mistborn").group(SearchField.Series)

    assertEquals(listOf(1, 2, 3), group?.results?.map { it.book.seriesIndex })
  }

  @Test
  fun `a series group orders by index even when it disagrees with the title`() {
    val books =
      listOf(
        seriesBook("b", "Alpha", index = 2),
        seriesBook("a", "Zulu", index = 1),
      )

    val group = books.groupedSearch("Mistborn").group(SearchField.Series)

    assertEquals(listOf("Zulu", "Alpha"), group?.results?.map { it.book.title })
  }

  /**
   * An unnumbered extra belongs at the end of a series, not in front of book one.
   *
   * `inSeriesOrder` already decides this (cu-24); the point here is that the search defers to it
   * rather than imposing an ordering of its own.
   */
  @Test
  fun `a series book with no index sorts after the numbered ones`() {
    val books =
      listOf(
        seriesBook("extra", "Secret History", index = 0),
        seriesBook("1", "The Final Empire", index = 1),
      )

    val group = books.groupedSearch("Mistborn").group(SearchField.Series)

    assertEquals(
      listOf("The Final Empire", "Secret History"),
      group?.results?.map { it.book.title },
    )
  }

  /** Only the series group is re-ordered; a title group stays ranked by match quality. */
  @Test
  fun `a title group is still ordered by score, not by series index`() {
    val books =
      listOf(
        book("fuzzy", title = "Dunes of Mars", series = "Mistborn", seriesIndex = 1),
        book("exact", title = "Dune", series = "Mistborn", seriesIndex = 9),
      )

    val group = books.groupedSearch("Dune").group(SearchField.Title)

    assertEquals("exact", group?.results?.first()?.book?.id)
  }

  // ---- grouping ----

  @Test
  fun `results group under the field each book matched`() {
    val grouped = library.groupedSearch("Tolkien")

    assertNotNull(grouped.group(SearchField.Author))
    assertEquals(listOf("J R R Tolkien"), grouped.group(SearchField.Author)?.values)
  }

  @Test
  fun `several fields matching produce several groups`() {
    val books =
      listOf(
        book("byTitle", title = "Mistborn Book 10"),
        book("bySeries", title = "Elantris", series = "Mistborn"),
      )

    val grouped = books.groupedSearch("Mistborn")

    assertEquals(listOf(SearchField.Title, SearchField.Series), grouped.groups.map { it.field })
  }

  /**
   * A book matching on two fields is listed once, under its strongest.
   *
   * Book 3 here matches "Mistborn" by both title and series. Listing it under both headings would
   * show the same cover twice in one result set, which reads as a duplicate library entry — the
   * phantom-book failure mode of cu-18 all over again.
   */
  @Test
  fun `a book matching two fields appears only once`() {
    val grouped = library.groupedSearch("Mistborn")

    assertEquals(1, grouped.groups.sumOf { it.count })
    assertEquals(SearchField.Title, grouped.groups.single().field)
  }

  @Test
  fun `a group carries its result count`() {
    val books =
      listOf(
        book("1", title = "Dune", author = "Frank Herbert"),
        book("2", title = "Dune Messiah", author = "Frank Herbert"),
      )

    val grouped = books.groupedSearch("Dune")

    assertEquals(2, grouped.group(SearchField.Title)?.count)
  }

  @Test
  fun `an author group lists each author once however many books match`() {
    val books =
      listOf(
        book("1", title = "Elantris", author = "Brandon Sanderson"),
        book("2", title = "Warbreaker", author = "Brandon Sanderson"),
      )

    val grouped = books.groupedSearch("Sanderson")

    assertEquals(listOf("Brandon Sanderson"), grouped.group(SearchField.Author)?.values)
  }

  @Test
  fun `an empty query produces no groups`() {
    assertTrue(library.groupedSearch("").isEmpty)
  }

  /**
   * A full-cast book is one result, not one per narrator.
   *
   * `searchableFields` emits an entry per narrator so each is matchable on its own, which makes it
   * easy for a book to be counted once per matching narrator instead of once.
   */
  @Test
  fun `a book with several matching narrators is counted once`() {
    val books = listOf(book("1", title = "Full Cast", narrator = "Kate Reading, Michael Kramer"))

    val grouped = books.groupedSearch("Reading")

    assertEquals(1, grouped.group(SearchField.Narrator)?.count)
  }

  @Test
  fun `a group is absent rather than empty when nothing matched it`() {
    val grouped = library.groupedSearch("Kramer")

    assertNull(grouped.group(SearchField.Series))
  }
}
