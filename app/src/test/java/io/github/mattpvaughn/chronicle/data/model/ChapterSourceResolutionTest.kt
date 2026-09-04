package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three-level chapter source resolution cu-82 introduces.
 *
 * Level 2 (the `Audiobook.chapters` column) exists only because the backfill (cu-158) is *launched,
 * not awaited*, so a book can have no rows on the first launch after an upgrade. Level 3 is cu-13's
 * permanent no-chapter-data fallback.
 */
class ChapterSourceResolutionTest {
  private fun chapter(id: String) =
    Chapter(
      id = id,
      bookId = "b1",
      trackId = "t1",
      title = id,
      index = 1L,
      discNumber = 1,
      bookStartTimeOffset = BookOffset.ZERO,
      bookEndTimeOffset = BookOffset(1000L),
    )

  @Test
  fun `the table wins when it has rows`() {
    val table = listOf(chapter("table"))
    val book = listOf(chapter("book"))
    assertEquals(table, resolveChapters(table, book, emptyList()))
  }

  /** The upgrade case this task exists to protect: rows absent, column populated. */
  @Test
  fun `the book column is used when the table is empty`() {
    val book = listOf(chapter("book"))
    assertEquals(book, resolveChapters(emptyList(), book, emptyList()))
  }

  /** cu-13's fallback, which stays: no chapter data anywhere. */
  @Test
  fun `tracks are the last resort`() {
    val tracks = listOf(MediaItemTrack(id = "t1", parentKey = "b1", duration = 1000L))
    assertEquals(tracks.asChapterList(), resolveChapters(emptyList(), emptyList(), tracks))
  }

  @Test
  fun `no data anywhere yields no chapters`() {
    assertEquals(emptyList<Chapter>(), resolveChapters(emptyList(), emptyList(), emptyList()))
  }
}
