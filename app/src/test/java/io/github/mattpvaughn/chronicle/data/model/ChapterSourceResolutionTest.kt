package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chapter source resolution cu-82 introduced, now two levels (cu-159).
 *
 * The middle level — the `Audiobook.chapters` column — is gone with the column. What remains is the
 * table, and cu-13's permanent no-chapter-data fallback that derives one chapter per track.
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
    val tracks = listOf(MediaItemTrack(id = "t1", parentKey = "b1", duration = 1000L))
    assertEquals("real chapters must beat the per-track derivation", table, resolveChapters(table, tracks))
  }

  /** cu-13's fallback, which stays: no chapter data anywhere. */
  @Test
  fun `tracks are the last resort`() {
    val tracks = listOf(MediaItemTrack(id = "t1", parentKey = "b1", duration = 1000L))
    assertEquals(tracks.asChapterList(), resolveChapters(emptyList(), tracks))
  }

  @Test
  fun `no data anywhere yields no chapters`() {
    assertEquals(emptyList<Chapter>(), resolveChapters(emptyList(), emptyList()))
  }
}
