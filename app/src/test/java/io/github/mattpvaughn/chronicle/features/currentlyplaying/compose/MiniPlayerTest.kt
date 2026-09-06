package io.github.mattpvaughn.chronicle.features.currentlyplaying.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The collapsed mini player (cu-206).
 *
 * The View version's fields were bound one at a time from `MainActivity`, so nothing could assert
 * what the row showed without inflating `activity_main.xml` and reaching into it. As a function of
 * [MiniPlayerState] the questions are direct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class MiniPlayerTest {
  @get:Rule
  val composeRule = createComposeRule()

  private fun state(
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
  ) = MiniPlayerState(
    bookTitle = "The Wisdom of Crowds",
    chapterTitle = "Chapter 12",
    artworkUrl = "thumb",
    isPlaying = isPlaying,
    isLoading = isLoading,
  )

  private fun setContent(
    state: MiniPlayerState,
    onClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
  ) {
    composeRule.setContent {
      ChronicleTheme {
        MiniPlayer(
          state = state,
          coverUrl = { it },
          onClick = onClick,
          onPlayPauseClick = onPlayPauseClick,
        )
      }
    }
  }

  @Test
  fun `it shows the chapter over the book title`() {
    setContent(state())

    composeRule.onNodeWithText("Chapter 12").assertIsDisplayed()
    composeRule.onNodeWithText("The Wisdom of Crowds").assertIsDisplayed()
  }

  @Test
  fun `tapping the row opens the player`() {
    var opened = 0
    setContent(state(), onClick = { opened++ })

    composeRule.onNodeWithText("Chapter 12").performClick()

    assertEquals(1, opened)
  }

  @Test
  fun `the play control is separate from the row`() {
    var opened = 0
    var toggled = 0
    setContent(state(), onClick = { opened++ }, onPlayPauseClick = { toggled++ })

    composeRule.onNodeWithContentDescription("Pause/Play button").performClick()

    assertEquals("tapping the button must not also open the player", 0, opened)
    assertEquals(1, toggled)
  }

  /**
   * A buffering mini player shows a spinner *instead of* the button (cu-95).
   *
   * Both live in a fixed-size box, so the row does not reflow — which is what the View version's
   * `INVISIBLE` (rather than `GONE`) achieved by hand.
   */
  @Test
  fun `buffering replaces the play control with a spinner`() {
    setContent(state(isLoading = true))

    composeRule.onNodeWithContentDescription("Buffering").assertIsDisplayed()
    val buttons =
      composeRule
        .onAllNodesWithContentDescription("Pause/Play button")
        .fetchSemanticsNodes()
        .size
    assertEquals("the button must be gone while buffering", 0, buttons)
  }
}
