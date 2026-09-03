package io.github.mattpvaughn.chronicle.views

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a bookmark row shows (cu-22).
 *
 * The row's two decisions — how the position reads, and whether the note line is there at all —
 * are the parts that can be wrong without anyone noticing on a screenshot of a book that happens
 * to have short notes.
 */
class BookmarkRowStateTest {
  private fun bookmark(
    position: Long = 0L,
    note: String = "",
  ) = Bookmark(bookId = "1001", position = BookOffset(position), note = note)

  /**
   * A bookmark's position is a position *inside a book*, so it is formatted the way every other
   * such readout in the app is (cu-19, §3.1 rule 3) — never `h:mm:ss` of a duration.
   */
  @Test
  fun `a position under an hour reads as minutes and seconds`() {
    assertEquals("32:10", formatPrecisePosition(bookmark(position = 1_930_000L).position.millis))
  }

  @Test
  fun `a position past an hour grows an hours field`() {
    assertEquals("1:02:33", formatPrecisePosition(bookmark(position = 3_753_000L).position.millis))
  }

  @Test
  fun `the start of a book reads as zero, not blank`() {
    assertEquals("0:00", formatPrecisePosition(bookmark(position = 0L).position.millis))
  }

  /**
   * A note of only whitespace must not reserve a line. `isNotEmpty` would let a stray space
   * through and leave a blank row under the position, which looks like a rendering bug.
   */
  @Test
  fun `a blank note does not count as a note`() {
    assertFalse(bookmark(note = "").hasNote)
    assertFalse(bookmark(note = "   ").hasNote)
    assertFalse(bookmark(note = "\n\t").hasNote)
  }

  @Test
  fun `a real note counts`() {
    assertTrue(bookmark(note = "the riddle game").hasNote)
    assertTrue("a note that merely starts with a space is still a note", bookmark(note = " x").hasNote)
  }
}
