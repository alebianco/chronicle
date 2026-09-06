package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import io.github.mattpvaughn.chronicle.views.SpeedChooserState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The playback-speed popover (cu-206).
 *
 * The View version needed an `isRendering` flag around every programmatic write so its own
 * listeners would not fire back, and a `SharedPreferences` change listener to re-read what it had
 * just written. Neither exists here, so what is left to test is only the mapping from state to
 * controls — which is the point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class SpeedChooserSheetTest {
  @get:Rule
  val composeRule = createComposeRule()

  private fun setContent(
    state: SpeedChooserState,
    skipSilence: Boolean = false,
    onSpeedChange: (Float) -> Unit = {},
    onToggleOverride: (Boolean) -> Unit = {},
    onToggleSkipSilence: (Boolean) -> Unit = {},
  ) {
    composeRule.setContent {
      ChronicleTheme {
        SpeedChooserSheet(
          state = state,
          skipSilence = skipSilence,
          onSpeedChange = onSpeedChange,
          onToggleOverride = onToggleOverride,
          onToggleSkipSilence = onToggleSkipSilence,
          onDismiss = {},
        )
      }
    }
  }

  /**
   * The speed shown is `1.35x` on purpose: it is deliberately **not** one of the presets.
   *
   * The sheet renders the current speed as its own readout beside the four chips, so a state on a
   * preset makes that label appear twice and `onNodeWithText` matches two nodes. Choosing an
   * off-preset speed keeps each assertion about exactly one node — and it is also the realistic
   * case, since the slider moves in 0.05 steps.
   */
  @Test
  fun `it offers the four presets`() {
    setContent(SpeedChooserState(speed = 1.35f, isOverrideEnabled = false, canOverride = true))

    composeRule.onNodeWithText("1.0x").assertIsDisplayed()
    composeRule.onNodeWithText("1.2x").assertIsDisplayed()
    composeRule.onNodeWithText("1.5x").assertIsDisplayed()
    composeRule.onNodeWithText("2.0x").assertIsDisplayed()
  }

  @Test
  fun `choosing a preset reports its speed`() {
    var chosen: Float? = null
    setContent(
      SpeedChooserState(speed = 1.35f, isOverrideEnabled = false, canOverride = true),
      onSpeedChange = { chosen = it },
    )

    composeRule.onNodeWithText("1.5x").performClick()

    assertEquals(1.5f, chosen)
  }

  /**
   * The per-book switch is disabled when nothing is playing.
   *
   * `SpeedChooserState.canOverride` is false for a book with no id, and a speed written against
   * one would have nowhere to go — the popover would silently do nothing.
   */
  @Test
  fun `the per-book switch is unusable with no book playing`() {
    var toggled = 0
    setContent(
      SpeedChooserState(speed = 1.35f, isOverrideEnabled = false, canOverride = false),
      onToggleOverride = { toggled++ },
    )

    composeRule.onNodeWithText("Just for this book").performClick()

    assertEquals("a disabled switch must not report a toggle", 0, toggled)
  }
}
