package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disc-grouping decision (cu-201).
 *
 * Extracted from `ChapterListAdapter` so the player and the details screen cannot disagree about
 * where a header goes — the same reasoning as cu-198's `progressState()` extraction. These cases
 * mirror `ChapterListAdapterTest`'s, which tested the same rules through the adapter.
 */
class ChapterListRowsTest {
  private fun chapter(
    id: String,
    disc: Int = 1,
    index: Long = 0L,
    trackId: String = "t1",
  ) = Chapter(id = id, discNumber = disc, index = index, trackId = trackId, title = "Ch $id")

  @Test
  fun `a single-disc book gets no section headers`() {
    val rows = chapterRows(listOf(chapter("1"), chapter("2", index = 1L)), activeChapter = null)

    assertTrue(
      "a single-disc book must not get a pointless Disc 1 header",
      rows.none { it is ChapterRow.DiscHeader },
    )
    assertEquals(2, rows.size)
  }

  @Test
  fun `a multi-disc book gets a header for every disc including the first`() {
    val rows =
      chapterRows(
        listOf(chapter("1", disc = 1), chapter("2", disc = 2, index = 1L)),
        activeChapter = null,
      )

    assertEquals(
      listOf(1, 2),
      rows.filterIsInstance<ChapterRow.DiscHeader>().map { it.discNumber },
    )
  }

  @Test
  fun `a header precedes the chapters it introduces`() {
    val rows =
      chapterRows(
        listOf(chapter("1", disc = 1), chapter("2", disc = 2, index = 1L)),
        activeChapter = null,
      )

    assertTrue("the first row must be a header", rows.first() is ChapterRow.DiscHeader)
    assertEquals(2, (rows[2] as ChapterRow.DiscHeader).discNumber)
  }

  @Test
  fun `an empty chapter list produces no rows`() {
    assertEquals(emptyList<ChapterRow>(), chapterRows(emptyList(), activeChapter = null))
  }

  @Test
  fun `no chapter is active before one is nominated`() {
    val rows = chapterRows(listOf(chapter("1")), activeChapter = null)

    assertTrue(rows.filterIsInstance<ChapterRow.ChapterItem>().none { it.isActive })
  }

  /**
   * Identity is track, disc and index together — **not** the chapter id.
   *
   * A chapter id is unique within a track, not within a book, so two tracks can carry the same id
   * and matching on it alone highlights the wrong row.
   */
  @Test
  fun `the active chapter is identified by track disc and index together`() {
    val target = chapter("1", trackId = "t2", index = 3L)
    val rows =
      chapterRows(
        listOf(chapter("1", trackId = "t1", index = 3L), target),
        activeChapter = target,
      )

    val active = rows.filterIsInstance<ChapterRow.ChapterItem>().filter { it.isActive }
    assertEquals("exactly the chapter on track t2 is active", 1, active.size)
    assertEquals("t2", active.single().chapter.trackId)
  }
}
