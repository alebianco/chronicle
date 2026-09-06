package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bookmark list (cu-22, migrated in cu-203).
 *
 * `BookmarkListAdapter` had its own test; this asserts the same behaviours plus the empty state,
 * which lived in the *host* fragment as two `isVisible` writes and so had no test at all.
 */
@RunWith(RobolectricTestRunner::class)
class BookmarkListTest {
  @get:Rule
  val compose = createComposeRule()

  private fun bookmark(
    id: String = "1",
    positionMillis: Long = 3_753_000L,
    note: String = "",
  ) = Bookmark(id = id, bookId = "1001", position = BookOffset(positionMillis), note = note)

  private fun setList(
    bookmarks: List<Bookmark>,
    onJump: (Bookmark) -> Unit = {},
    onEdit: (Bookmark) -> Unit = {},
  ) {
    compose.setContent {
      ChronicleTheme {
        BookmarkList(bookmarks = bookmarks, onJump = onJump, onEdit = onEdit)
      }
    }
  }

  /**
   * The empty state is said, not left blank.
   *
   * A sheet showing nothing gives the user no way to tell "no bookmarks" from "failed to load".
   */
  @Test
  fun `an empty list explains itself`() {
    setList(emptyList())

    compose.onNodeWithText("bookmark", substring = true, ignoreCase = true).assertIsDisplayed()
  }

  /**
   * The position is `formatPrecisePosition`, not a raw `h:mm:ss` pair (cu-19).
   *
   * 1:02:33 into the book, shown the way the player shows a position.
   */
  @Test
  fun `a bookmark shows its position`() {
    setList(listOf(bookmark(positionMillis = 3_753_000L)))

    compose.onNodeWithText("1:02:33").assertIsDisplayed()
  }

  @Test
  fun `a note is shown when there is one`() {
    setList(listOf(bookmark(note = "the riddle game")))

    compose.onNodeWithText("the riddle game").assertIsDisplayed()
  }

  /** An empty note must not take a line of its own. */
  @Test
  fun `no note line is drawn for a bookmark without one`() {
    setList(listOf(bookmark(note = "")))

    compose.onNodeWithText("").assertDoesNotExist()
  }

  @Test
  fun `tapping a row jumps to that bookmark`() {
    var jumped: Bookmark? = null
    setList(listOf(bookmark(id = "7")), onJump = { jumped = it })

    compose.onNodeWithText("1:02:33").performClick()

    assertEquals("7", jumped?.id)
  }

  /**
   * Edit is a *separate* target from the row.
   *
   * The pencil sits inside a row whose whole surface jumps, so a tap that reaches the row instead
   * of the button would silently seek the player rather than open the note editor.
   */
  @Test
  fun `tapping the pencil edits rather than jumping`() {
    var jumped: Bookmark? = null
    var edited: Bookmark? = null
    setList(listOf(bookmark(id = "7")), onJump = { jumped = it }, onEdit = { edited = it })

    compose.onNodeWithContentDescription("Edit note", substring = true, ignoreCase = true)
      .performClick()

    assertEquals("7", edited?.id)
    assertEquals("the row must not also fire", null, jumped)
  }
}
