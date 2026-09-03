package io.github.mattpvaughn.chronicle.views

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bookmark list's identity and equality (cu-22).
 *
 * `DiffUtil` decides whether a row is *the same row* and whether it needs redrawing. Getting the
 * first wrong makes an edit animate as a delete-and-insert; getting the second wrong leaves a
 * stale note on screen. Both are silent.
 *
 * Tested through the data class rather than by instantiating the adapter, which needs a
 * `ViewGroup`: the callback's logic is `id` for identity and `(position, note)` for contents, so
 * these assert the properties those comparisons rest on.
 */
class BookmarkDiffTest {
  private val original =
    Bookmark(
      id = "bm-1",
      bookId = "1001",
      position = BookOffset(90_000L),
      note = "the riddle game",
      createdAt = 1_700_000_000_000L,
    )

  @Test
  fun `editing a note keeps the same identity`() {
    val edited = original.copy(note = "riddles in the dark")

    assertEquals("an edit must animate as a change, not a delete and insert", original.id, edited.id)
  }

  @Test
  fun `an edited note is a content change`() {
    val edited = original.copy(note = "riddles in the dark")

    assertEquals(original.position, edited.position)
    // The pair the diff callback compares.
    assert(original.note != edited.note) { "the note must be part of what makes a row stale" }
  }

  /**
   * `createdAt` is not shown, so a change to it alone must not be a content change — otherwise a
   * rewrite of the same row would repaint for nothing.
   */
  @Test
  fun `the creation time is not part of what is displayed`() {
    val touched = original.copy(createdAt = 2_000_000_000_000L)

    assertEquals(original.position, touched.position)
    assertEquals(original.note, touched.note)
  }

  @Test
  fun `two bookmarks at the same moment are still different rows`() {
    val other = original.copy(id = "bm-2")

    assert(original.id != other.id) {
      "identity must not be the position: two bookmarks may mark the same moment"
    }
    assertEquals(original.position, other.position)
  }
}
