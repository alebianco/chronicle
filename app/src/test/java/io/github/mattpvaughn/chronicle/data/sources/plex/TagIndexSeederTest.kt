package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Seeding narrator and series onto books that have not learned them (cu-143).
 *
 * The merge rule is the delicate part and the reason this is pure. It mirrors `Audiobook.merge`'s
 * third rule (cu-24): a value read from a book's own detail response is authoritative, and this
 * coarser index must never overwrite it — but a book that knows nothing takes what the index has.
 * Getting that backwards would blank correct metadata on every refresh, which is exactly the
 * failure cu-24 documented for the network/local merge.
 */
class TagIndexSeederTest {
  private fun book(
    id: String,
    narrator: String = "",
    series: String = "",
  ) = Audiobook(id = id, source = 1L, title = "Book $id", narrator = narrator, series = series)

  private fun narrators(
    value: String,
    vararg ids: String,
  ) = TagAssociation(TagFilter.STYLE, value, ids.toSet())

  private fun series(
    value: String,
    vararg ids: String,
  ) = TagAssociation(TagFilter.MOOD, value, ids.toSet())

  // ---- the basic fill ----

  @Test
  fun `a book with no narrator learns one`() {
    val seeded = listOf(book("1")).withSeededTags(listOf(narrators("Michael Kramer", "1")))

    assertEquals("Michael Kramer", seeded.single().narrator)
  }

  @Test
  fun `a book with no series learns one`() {
    val seeded = listOf(book("1")).withSeededTags(listOf(series("Mistborn", "1")))

    assertEquals("Mistborn", seeded.single().series)
  }

  @Test
  fun `a book not in any association is untouched`() {
    val seeded = listOf(book("1")).withSeededTags(listOf(narrators("Michael Kramer", "2")))

    assertEquals("", seeded.single().narrator)
  }

  @Test
  fun `no associations leaves the list exactly as it was`() {
    val books = listOf(book("1"), book("2"))

    assertSame(books, books.withSeededTags(emptyList()))
  }

  // ---- the rule that matters ----

  /**
   * A narrator read from the book's own detail response outranks the index.
   *
   * Overwriting it would replace a precise value with a coarser one on every refresh — the same
   * shape as the refresh-blanking bug cu-24's third merge rule exists to prevent.
   */
  @Test
  fun `an existing narrator is never overwritten`() {
    val known = listOf(book("1", narrator = "Kate Reading"))

    val seeded = known.withSeededTags(listOf(narrators("Michael Kramer", "1")))

    assertEquals("Kate Reading", seeded.single().narrator)
  }

  @Test
  fun `an existing series is never overwritten`() {
    val known = listOf(book("1", series = "Stormlight Archive"))

    val seeded = known.withSeededTags(listOf(series("Mistborn", "1")))

    assertEquals("Stormlight Archive", seeded.single().series)
  }

  @Test
  fun `a book knowing one field still learns the other`() {
    val partial = listOf(book("1", narrator = "Kate Reading"))

    val seeded = partial.withSeededTags(listOf(narrators("Michael Kramer", "1"), series("Mistborn", "1")))

    assertEquals("Kate Reading", seeded.single().narrator)
    assertEquals("Mistborn", seeded.single().series)
  }

  // ---- full-cast recordings ----

  /**
   * Each narrator arrives as its own tag value, so they must accumulate rather than overwrite.
   *
   * A full-cast recording appears under every reader's filter; taking only the last would drop the
   * rest and make the facet index disagree with itself.
   */
  @Test
  fun `several narrators for one book are joined`() {
    val seeded =
      listOf(book("1")).withSeededTags(
        listOf(narrators("Michael Kramer", "1"), narrators("Kate Reading", "1")),
      )

    assertEquals("Kate Reading, Michael Kramer", seeded.single().narrator)
  }

  /** Sorted, so the joined string does not depend on the order the server enumerated the tags. */
  @Test
  fun `the joined narrators are in a stable order`() {
    val one =
      listOf(book("1")).withSeededTags(
        listOf(narrators("Michael Kramer", "1"), narrators("Kate Reading", "1")),
      ).single().narrator
    val other =
      listOf(book("1")).withSeededTags(
        listOf(narrators("Kate Reading", "1"), narrators("Michael Kramer", "1")),
      ).single().narrator

    assertEquals(one, other)
  }

  // ---- the Audnexus prefix ----

  /** `Mood` carries `"Series: Mistborn"` by convention; the prefix is not part of the name. */
  @Test
  fun `the Series prefix is stripped from a series tag`() {
    val seeded = listOf(book("1")).withSeededTags(listOf(series("Series: Mistborn", "1")))

    assertEquals("Mistborn", seeded.single().series)
  }

  @Test
  fun `a series tag with no prefix is taken as-is`() {
    val seeded = listOf(book("1")).withSeededTags(listOf(series("Mistborn", "1")))

    assertEquals("Mistborn", seeded.single().series)
  }

  @Test
  fun `the prefix match is case-insensitive`() {
    val seeded = listOf(book("1")).withSeededTags(listOf(series("SERIES: Mistborn", "1")))

    assertEquals("Mistborn", seeded.single().series)
  }

  // ---- several books ----

  @Test
  fun `one narrator seeds every book they read`() {
    val library = listOf(book("1"), book("2"), book("3"))

    val seeded = library.withSeededTags(listOf(narrators("Michael Kramer", "1", "3")))

    assertEquals("Michael Kramer", seeded.first { it.id == "1" }.narrator)
    assertEquals("", seeded.first { it.id == "2" }.narrator)
    assertEquals("Michael Kramer", seeded.first { it.id == "3" }.narrator)
  }

  /** A book needing no change is returned unchanged, not copied — the list is a refresh hot path. */
  @Test
  fun `an unchanged book keeps its identity`() {
    val original = book("1", narrator = "Kate Reading", series = "Mistborn")

    val seeded = listOf(original).withSeededTags(listOf(narrators("Michael Kramer", "1")))

    assertSame(original, seeded.single())
  }
}
