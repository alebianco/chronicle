package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bookmark note editor.
 *
 * One of the three screens still written in Views before this task, and the only one with no
 * ViewModel at all — arguments in, a `Listener` out. That shape translates directly to parameters
 * and callbacks, which is what makes it testable without a Fragment at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class BookmarkNoteSheetTest {
  @get:Rule
  val composeRule = createComposeRule()

  private fun setContent(
    existingNote: String = "",
    onSave: (String) -> Unit = {},
    onDelete: () -> Unit = {},
  ) {
    composeRule.setContent {
      ChronicleTheme {
        BookmarkNoteSheet(
          positionMillis = 1_930_000L,
          existingNote = existingNote,
          onSave = onSave,
          onDelete = onDelete,
          onDismiss = {},
        )
      }
    }
  }

  @Test
  fun `it shows the existing note`() {
    setContent(existingNote = "the bit about crows")

    composeRule.onNodeWithText("the bit about crows").assertIsDisplayed()
  }

  @Test
  fun `saving reports the note text`() {
    var saved: String? = null
    setContent(existingNote = "a note", onSave = { saved = it })

    composeRule.onNodeWithText("Save").performClick()

    assertEquals("a note", saved)
  }

  @Test
  fun `deleting does not report a note`() {
    var deleted = 0
    var saved: String? = null
    setContent(existingNote = "a note", onSave = { saved = it }, onDelete = { deleted++ })

    composeRule.onNodeWithText("Delete bookmark").performClick()

    assertEquals(1, deleted)
    assertEquals("deleting must not also save", null, saved)
  }

  /** The position is human-formatted, never a raw `h:mm:ss/h:mm:ss` pair. */
  @Test
  fun `the title carries the formatted position`() {
    setContent()

    // 1,930,000 ms is 32:10.
    composeRule.onNodeWithText("32:10", substring = true).assertIsDisplayed()
  }
}
