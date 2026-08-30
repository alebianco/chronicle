package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSortComparatorsTest {
  private fun sortTitles(vararg titles: String): List<String> =
    titles.sortedWith { a, b -> BookSortComparators.compareTitlesNaturally(a, b) }

  @Test
  fun `series volumes sort by number, not by digit text`() {
    // The bug this fixes: plain string ordering puts "Book 10" before "Book 2"
    // because '1' < '2'.
    assertEquals(
      listOf("Book 1", "Book 2", "Book 9", "Book 10", "Book 20", "Book 100"),
      sortTitles("Book 10", "Book 2", "Book 100", "Book 1", "Book 20", "Book 9"),
    )
  }

  @Test
  fun `leading zeros do not change a volume's position`() {
    assertEquals(
      listOf("Book 2", "Book 03", "Book 10"),
      sortTitles("Book 10", "Book 03", "Book 2"),
    )
  }

  @Test
  fun `titles without numbers sort alphabetically, ignoring case`() {
    assertEquals(
      listOf("dune", "Dune Messiah", "Elantris"),
      sortTitles("Elantris", "dune", "Dune Messiah"),
    )
  }

  @Test
  fun `numbers embedded mid-title compare by value`() {
    assertEquals(
      listOf("Chapter 2 of Water", "Chapter 10 of Fire"),
      sortTitles("Chapter 10 of Fire", "Chapter 2 of Water"),
    )
  }

  @Test
  fun `comparator is symmetric`() {
    // A non-total comparator throws "Comparison method violates its general
    // contract" from the sort, which surfaces as a crash on the library screen.
    val titles = listOf("Book 1", "Book 10", "book 2", "Alpha", "alpha 3", "")
    for (a in titles) {
      for (b in titles) {
        val ab = BookSortComparators.compareTitlesNaturally(a, b)
        val ba = BookSortComparators.compareTitlesNaturally(b, a)
        assertTrue(
          "compare($a, $b)=$ab must be the inverse of compare($b, $a)=$ba",
          (ab == 0 && ba == 0) || (ab > 0 && ba < 0) || (ab < 0 && ba > 0),
        )
      }
    }
  }

  @Test
  fun `authors file under surname`() {
    assertEquals("sanderson brandon", BookSortComparators.authorSortKey("Brandon Sanderson"))
    assertEquals("herbert frank", BookSortComparators.authorSortKey("Frank Herbert"))
  }

  @Test
  fun `multi-part given names keep their order after the surname`() {
    assertEquals("tolkien j r r", BookSortComparators.authorSortKey("J R R Tolkien"))
  }

  @Test
  fun `an already-inverted name is left alone`() {
    assertEquals("le guin, ursula k", BookSortComparators.authorSortKey("Le Guin, Ursula K"))
  }

  @Test
  fun `single-word and blank authors are returned unchanged`() {
    assertEquals("homer", BookSortComparators.authorSortKey("Homer"))
    assertEquals("", BookSortComparators.authorSortKey(""))
    assertEquals("", BookSortComparators.authorSortKey("   "))
  }

  @Test
  fun `sorting by author key orders by surname`() {
    val authors = listOf("Brandon Sanderson", "Frank Herbert", "Ursula K Le Guin")
    assertEquals(
      listOf("Ursula K Le Guin", "Frank Herbert", "Brandon Sanderson"),
      authors.sortedBy { BookSortComparators.authorSortKey(it) },
    )
  }
}
