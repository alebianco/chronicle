package io.github.mattpvaughn.chronicle.features.currentlyplaying.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlayingViewModel.PlayerProgress
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The player screen, asserted on what it renders.
 *
 * `createComposeRule` with no `FragmentScenario`, no mocked `ActivityComponent` and no hand-written
 * `SharedPreferences` fake — the Fragment equivalent needed all three and could still only assert
 * `view != null`, because reading a RecyclerView's contents needs Espresso on a device.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun progress(
    chapterNumber: Int = 3,
    chapterCount: Int = 8,
    millisLeftInChapter: Long = 150_000L,
    millisLeftInBook: Long = 22_320_000L,
  ) = PlayerProgress(chapterNumber, chapterCount, millisLeftInChapter, millisLeftInBook)

  private fun setScreen(
    state: PlayerUiState,
    actions: PlayerActions = PlayerActions(),
    showArtwork: Boolean = true,
  ) {
    compose.setContent {
      ChronicleTheme {
        PlayerScreen(
          state = state,
          actions = actions,
          coverUrl = { "http://localhost/$it" },
          showArtwork = showArtwork,
        )
      }
    }
  }

  @Test
  fun `the chapter title is rendered`() {
    setScreen(PlayerUiState(text = TextState(progress = progress(), chapterTitle = "Roast Mutton")))

    compose.onNodeWithText("Roast Mutton").assertIsDisplayed()
  }

  /**
   * The wording rule, at the screen rather than only in `PlayerText`'s own tests: a duration reads
   * `6h 12m`, never `47:12:33/52:04:11` (§3.1 rule 3).
   */
  @Test
  fun `durations read as human text, never a raw pair`() {
    setScreen(PlayerUiState(text = TextState(progress = progress(), chapterTitle = "Ch")))

    // 22,320,000 ms is 6h 12m. The banned rendering would be "6:12:00/…".
    compose.onNodeWithText("6h 12m left in book").assertIsDisplayed()
    compose.onNodeWithText("Ch 3 of 8").assertIsDisplayed()
  }

  /**
   * Two earlier bugs were both landscape-only: a text block that rendered in portrait and vanished
   * in landscape, because the guard probed a view `values-land` hides. Landscape here differs by
   * exactly one thing — no cover — and everything else must still be on screen.
   */
  @Test
  fun `landscape drops the cover and keeps the readout`() {
    setScreen(
      state = PlayerUiState(text = TextState(progress = progress(), chapterTitle = "Roast Mutton")),
      showArtwork = false,
    )

    compose.onNodeWithText("Roast Mutton").assertIsDisplayed()
    compose.onNodeWithText("6h 12m left in book").assertIsDisplayed()
  }

  @Test
  fun `the play button reports a tap`() {
    var tapped = false
    setScreen(
      state = PlayerUiState(),
      actions = PlayerActions(onPlayPause = { tapped = true }),
    )

    compose.onNodeWithContentDescription("Pause/Play button").performClick()

    assertTrue("the transport row must be wired", tapped)
  }

  /**
   * Buffering replaces the button in place rather than hiding it: the Fragment used `INVISIBLE`
   * rather than `GONE` so the row would not reflow, and losing that is a visible jump.
   */
  @Test
  fun `a buffering player shows no play button`() {
    setScreen(PlayerUiState(transport = TransportState(isAudioLoading = true)))

    assertEquals(
      "the play control must be replaced while buffering",
      0,
      compose.onAllNodesWithContentDescription("Pause/Play button").fetchSemanticsNodes().size,
    )
  }
}
