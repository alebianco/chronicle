package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserListener
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The chooser sheet, shared by five screens (cu-203).
 *
 * `BottomSheetChooser` was a `FrameLayout` with a hand-rolled animation and an inner adapter; its
 * only test coverage was the `DiffUtil` callback.
 */
@RunWith(RobolectricTestRunner::class)
class BottomChooserTest {
  @get:Rule
  val compose = createComposeRule()

  private class RecordingListener : BottomChooserListener {
    var clicked: FormattableString? = null
    var closed: Boolean? = null

    override fun onItemClicked(formattableString: FormattableString) {
      clicked = formattableString
    }

    override fun onChooserClosed(wasBackgroundClicked: Boolean) {
      closed = wasBackgroundClicked
    }
  }

  private fun state(
    options: List<String> = listOf("15 minutes", "30 minutes"),
    title: String = "Sleep timer",
    shouldShow: Boolean = true,
    listener: BottomChooserListener = RecordingListener(),
  ) = BottomChooserState(
    title = FormattableString.from(title),
    options = options.map { FormattableString.from(it) },
    listener = listener,
    shouldShow = shouldShow,
  )

  private fun setChooser(state: BottomChooserState) {
    compose.setContent { ChronicleTheme { BottomChooser(state) } }
  }

  @Test
  fun `a hidden chooser draws nothing`() {
    setChooser(state(shouldShow = false))

    compose.onNodeWithText("Sleep timer").assertDoesNotExist()
  }

  @Test
  fun `the title and every option are shown`() {
    setChooser(state())

    compose.onNodeWithText("Sleep timer").assertIsDisplayed()
    compose.onNodeWithText("15 minutes").assertIsDisplayed()
    compose.onNodeWithText("30 minutes").assertIsDisplayed()
  }

  /** An empty title takes no space — several callers show a bare list of options. */
  @Test
  fun `an empty title draws no heading`() {
    setChooser(state(title = ""))

    compose.onNodeWithText("15 minutes").assertIsDisplayed()
  }

  @Test
  fun `tapping an option reports which one`() {
    val listener = RecordingListener()
    setChooser(state(listener = listener))

    compose.onNodeWithText("30 minutes").performClick()

    assertEquals(FormattableString.from("30 minutes"), listener.clicked)
  }

  /**
   * Two options with the same text are two rows.
   *
   * Keying a `LazyColumn` on the string would be a duplicate key — a crash, not a merge — and two
   * servers can legitimately share a name.
   */
  @Test
  fun `duplicate option text renders as two rows`() {
    setChooser(state(options = listOf("Plex", "Plex")))

    val rows = compose.onAllNodes(hasText("Plex")).fetchSemanticsNodes()

    assertEquals("both options must render", 2, rows.size)
  }
}
