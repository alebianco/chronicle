package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_CHAPTER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scrubber window Android Auto and the notification draw.
 *
 * Before this, position and duration both described the whole track while the title beside them
 * named the chapter, so on a single-file 47-hour book a small drag skipped hours.
 */
class ScrubberWindowTest {
  private fun chapter(
    startMillis: Long,
    endMillis: Long,
  ) = Chapter(
    title = "Ch",
    id = "1",
    bookStartTimeOffset = BookOffset(startMillis),
    bookEndTimeOffset = BookOffset(endMillis),
  )

  @Test
  fun `the window is the chapter, and the position is relative to its start`() {
    val window = chapterScrubberWindow(BookOffset(1_500_000L), chapter(1_200_000L, 1_800_000L))

    assertEquals(ScrubberWindow(positionMillis = 300_000L, durationMillis = 600_000L), window)
  }

  /**
   * The whole point: a book-framed position must not leak through as if it were chapter-framed.
   * Publishing 1,500,000 here would put the marker past the end of a 600 s bar.
   */
  @Test
  fun `the position is not the raw book offset`() {
    val window = chapterScrubberWindow(BookOffset(1_500_000L), chapter(1_200_000L, 1_800_000L))!!

    assertEquals(300_000L, window.positionMillis)
  }

  /** A book with no chapter data must keep a working track bar, not get a dead one. */
  @Test
  fun `no chapter yields no window, so the caller keeps the track's`() {
    assertNull(chapterScrubberWindow(BookOffset(1_000L), EMPTY_CHAPTER))
  }

  /**
   * A zero-length chapter would make Auto divide by zero or draw a full bar. Real libraries have
   * them — "ghost chapters with 0 length" is a fix in the Epilogue fork.
   */
  @Test
  fun `a zero-length chapter yields no window`() {
    assertNull(chapterScrubberWindow(BookOffset(1_200_000L), chapter(1_200_000L, 1_200_000L)))
  }

  /**
   * The position is sampled a tick behind the chapter resolved from it, so at a boundary it can
   * land just outside. Unclamped, a negative position makes Auto draw a full bar.
   */
  @Test
  fun `a position before the chapter clamps to its start`() {
    val window = chapterScrubberWindow(BookOffset(1_100_000L), chapter(1_200_000L, 1_800_000L))!!

    assertEquals(0L, window.positionMillis)
  }

  @Test
  fun `a position past the chapter clamps to its end`() {
    val window = chapterScrubberWindow(BookOffset(1_900_000L), chapter(1_200_000L, 1_800_000L))!!

    assertEquals(600_000L, window.positionMillis)
  }

  /**
   * A first chapter starting at zero is the case where book and chapter frames agree, which is
   * exactly how a frame bug hides. Pinned so it cannot be the only case covered.
   */
  @Test
  fun `a chapter starting at zero still reports a chapter-length window`() {
    val window = chapterScrubberWindow(BookOffset(30_000L), chapter(0L, 600_000L))!!

    assertEquals(ScrubberWindow(positionMillis = 30_000L, durationMillis = 600_000L), window)
  }
}
