package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules for the one-off chapter backfill (cu-158). */
class ChapterBackfillTest {
  private fun chapter(
    index: Long,
    title: String = "Chapter $index",
    bookId: String = NO_AUDIOBOOK_FOUND_ID,
    trackId: String = "track-1",
    start: Long = index * 1000,
  ) = Chapter(
    id = "ch-$index",
    title = title,
    index = index,
    bookStartTimeOffset = BookOffset(start),
    bookEndTimeOffset = BookOffset(start + 1000),
    trackId = trackId,
    bookId = bookId,
  )

  private fun book(
    id: String = "book-1",
    chapters: List<Chapter> = emptyList(),
  ) = EMPTY_AUDIOBOOK.copy(id = id, title = "A Book", chapters = chapters)

  @Test
  fun `a book with a populated column and no rows is backfilled`() {
    val rows = ChapterBackfill.rowsFor(book(chapters = listOf(chapter(1), chapter(2))), existingRowCount = 0)

    assertEquals(2, rows.size)
    assertEquals(listOf("Chapter 1", "Chapter 2"), rows.map { it.title })
  }

  /**
   * The load-bearing one. `Chapter`'s primary key is `(bookId, trackId, discNumber, index)`, and a
   * record serialized before the book id was part of the format decodes with `bookId` defaulted to
   * `NO_AUDIOBOOK_FOUND_ID`. Inserting those verbatim keys every book's chapters under the same
   * sentinel, so two books' chapters collide on the composite key and one silently replaces the
   * other.
   */
  @Test
  fun `the owning book id is stamped onto every row`() {
    val rows =
      ChapterBackfill.rowsFor(
        book(id = "book-42", chapters = listOf(chapter(1), chapter(2))),
        existingRowCount = 0,
      )

    assertTrue("expected every row to carry book-42", rows.all { it.bookId == "book-42" })
  }

  @Test
  fun `two books with sentinel book ids do not collide after stamping`() {
    val a = ChapterBackfill.rowsFor(book(id = "book-a", chapters = listOf(chapter(1))), 0)
    val b = ChapterBackfill.rowsFor(book(id = "book-b", chapters = listOf(chapter(1))), 0)

    // The composite primary key, as Room would see it.
    fun key(c: Chapter) = listOf(c.bookId, c.trackId, c.discNumber, c.index)
    assertTrue("rows must not share a primary key", key(a.single()) != key(b.single()))
  }

  /** The table wins. A book already synced by a current version has authoritative rows. */
  @Test
  fun `a book that already has rows is left alone`() {
    val rows = ChapterBackfill.rowsFor(book(chapters = listOf(chapter(1), chapter(2))), existingRowCount = 2)

    assertEquals(emptyList<Chapter>(), rows)
  }

  /** Idempotence: the second run sees the rows it wrote and writes nothing. */
  @Test
  fun `running twice writes nothing the second time`() {
    val subject = book(chapters = listOf(chapter(1), chapter(2)))
    val first = ChapterBackfill.rowsFor(subject, existingRowCount = 0)
    val second = ChapterBackfill.rowsFor(subject, existingRowCount = first.size)

    assertEquals(2, first.size)
    assertEquals(emptyList<Chapter>(), second)
  }

  /**
   * An interrupted run must resume rather than skip. A book left with *some* rows is not treated
   * as done by `rowsFor` alone — the caller re-writes the full set, which the DAO replaces per
   * book. Pinned here because the alternative (a global "done" preference) silently loses the
   * remainder of an interrupted pass.
   */
  @Test
  fun `a partially written book is still reported as needing work`() {
    assertTrue(ChapterBackfill.mayNeedBackfill(book(chapters = listOf(chapter(1), chapter(2)))))
  }

  @Test
  fun `a book with no chapter data needs nothing`() {
    assertFalse(ChapterBackfill.mayNeedBackfill(book(chapters = emptyList())))
    assertEquals(emptyList<Chapter>(), ChapterBackfill.rowsFor(book(), existingRowCount = 0))
  }

  /**
   * Offsets are absolute within the book, not per-track (cu-13/cu-49/cu-136). This moves rows; it
   * must not recompute them — a per-track re-derivation here would reintroduce the bug those three
   * tasks each fixed once.
   */
  @Test
  fun `offsets are copied unchanged`() {
    val original = chapter(3, start = 987_654)
    val row = ChapterBackfill.rowsFor(book(chapters = listOf(original)), existingRowCount = 0).single()

    assertEquals(original.bookStartTimeOffset, row.bookStartTimeOffset)
    assertEquals(original.bookEndTimeOffset, row.bookEndTimeOffset)
  }

  /** Nothing else about a chapter changes — only the book id. */
  @Test
  fun `only the book id differs from the stored chapter`() {
    val original = chapter(5, title = "The Giant's Drink", trackId = "track-9")
    val row = ChapterBackfill.rowsFor(book(id = "b", chapters = listOf(original)), existingRowCount = 0).single()

    assertEquals(original.copy(bookId = "b"), row)
  }

  /** A blank placeholder record contributes no row rather than an empty chapter. */
  @Test
  fun `an empty placeholder chapter is skipped`() {
    val placeholder = Chapter()
    val rows = ChapterBackfill.rowsFor(book(chapters = listOf(placeholder, chapter(1))), existingRowCount = 0)

    assertEquals(listOf("Chapter 1"), rows.map { it.title })
  }
}
