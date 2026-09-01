package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two chapter lookups must agree, especially on a boundary (cu-93).
 *
 * `getChapterAt` used an inclusive range (`start..end`) while `chapterAtBookProgress` was half-open.
 * A position exactly on a boundary therefore resolved to the **earlier** chapter through one and
 * the **later** through the other. Seeking to a chapter start lands exactly there, so
 * previous-chapter seeked correctly to Chapter 20's start and the screen said "Chapter 19" — which
 * reads as the button jumping to the end of the previous chapter.
 *
 * The offsets are the real ones from the owner's book.
 */
class ChapterBoundaryTest {
  private fun chapter(
    index: Long,
    start: Long,
    end: Long,
  ) = Chapter(
    title = "Chapter $index",
    id = index.toString(),
    index = index,
    discNumber = 1,
    bookStartTimeOffset = start,
    bookEndTimeOffset = end,
    downloaded = false,
    trackId = TRACK,
    bookId = "155594",
  )

  private val chapters =
    listOf(
      chapter(19, start = 25_024_800L, end = 26_879_000L),
      chapter(20, start = 26_879_000L, end = 29_184_600L),
      chapter(21, start = 29_184_600L, end = 31_300_300L),
    )

  /** The regression: a position on a boundary belongs to the chapter that *starts* there. */
  @Test
  fun `a position exactly on a boundary is the later chapter`() {
    assertEquals(
      "26879000 is Chapter 20's first millisecond, not Chapter 19's last",
      "Chapter 20",
      chapters.getChapterAt(TRACK, 26_879_000L).title,
    )
  }

  /** One millisecond earlier is genuinely still the earlier chapter. */
  @Test
  fun `a position just before a boundary is the earlier chapter`() {
    assertEquals("Chapter 19", chapters.getChapterAt(TRACK, 26_878_999L).title)
  }

  /** Both lookups must return the same chapter for the same position. */
  @Test
  fun `the two lookups agree on a boundary`() {
    val byTrack = chapters.getChapterAt(TRACK, 26_879_000L)
    val byBook = chapters.chapterAtBookProgress(26_879_000L)

    assertEquals(
      "two lookups over the same data disagreeing at a boundary is the defect itself",
      byBook.title,
      byTrack.title,
    )
  }

  @Test
  fun `the two lookups agree mid-chapter`() {
    assertEquals(
      chapters.chapterAtBookProgress(28_000_000L).title,
      chapters.getChapterAt(TRACK, 28_000_000L).title,
    )
  }

  /**
   * The very end of the book is a real position: a book paused at its last millisecond is in its
   * last chapter, not nowhere. Half-open would exclude it, so it is accepted explicitly.
   */
  @Test
  fun `the final chapter's own end still resolves to it`() {
    assertEquals("Chapter 21", chapters.getChapterAt(TRACK, 31_300_300L).title)
  }

  /** A position past the end of the book matches nothing for a track-scoped lookup. */
  @Test
  fun `a position past the book end is the empty chapter`() {
    assertEquals(EMPTY_CHAPTER, chapters.getChapterAt(TRACK, 40_000_000L))
  }

  /** A different track must not match, however well the timestamp fits. */
  @Test
  fun `a timestamp from another track matches nothing`() {
    assertEquals(EMPTY_CHAPTER, chapters.getChapterAt("999999", 28_000_000L))
  }

  private companion object {
    const val TRACK = "155595"
  }
}
