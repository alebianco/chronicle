package io.github.mattpvaughn.chronicle.application.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the standing account notice is drawn, which is a **layout** question rather than a
 * semantic one.
 *
 * This exists because the defect it guards was invisible to every other kind of test. The notice
 * was a sibling composable placed after the app shell with no layout between them, so its
 * `SnackbarHost` painted at the same top-start origin as `ChronicleScaffold`'s `TopAppBar` and
 * covered it completely — every pushed sub-screen lost its title *and its back arrow*, leaving the
 * system back gesture as the only way off the screen.
 *
 * The semantics tree was **correct throughout**: the toolbar was composed, present and findable by
 * `onNodeWithText`. Only the pixels were wrong. So the assertion here is on `getBoundsInRoot` —
 * asserting the notice is nowhere near the top of the window is the only thing that would have
 * failed before the fix, and it is what makes this a guard rather than a restatement.
 *
 * Mock Plex mode always reports a revoked account, so this banner is up during **every** mock-mode
 * device verification. That is why it went unnoticed for so long and why it was worth pinning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class AccountNoticePlacementTest {
  @get:Rule
  val composeRule = createComposeRule()

  private fun setContent(sheetState: BottomSheetState = BottomSheetState.HIDDEN) {
    composeRule.setContent {
      ChronicleTheme {
        ChronicleApp(
          navController = rememberNavController(),
          isLoggedIn = true,
          showCollectionsTab = false,
          sheetState = sheetState,
          onTabSelected = {},
          miniPlayer = {},
          expandedPlayer = {},
          navHost = { Text(NAV_HOST, modifier = Modifier.fillMaxSize()) },
          accountNotice = { Text(NOTICE) },
        )
      }
    }
  }

  /**
   * The assertion that would have caught the bug: the notice must not sit in the band where a
   * sub-screen's `TopAppBar` lives.
   */
  @Test
  fun `the account notice is not drawn over the toolbar band`() {
    setContent()

    val top = composeRule.onNodeWithText(NOTICE).getUnclippedBoundsInRoot().top

    assertTrue(
      "The account notice starts ${top.value}dp from the top of the window, which is inside the " +
        "band a pushed screen's TopAppBar occupies. It covered the title and the back arrow " +
        "there before it was moved to the bottom.",
      top.value > TOOLBAR_BAND_DP,
    )
  }

  @Test
  fun `the account notice sits below the nav host content`() {
    setContent()

    val notice = composeRule.onNodeWithText(NOTICE).getUnclippedBoundsInRoot()
    val navHost = composeRule.onNodeWithText(NAV_HOST).getUnclippedBoundsInRoot()

    assertTrue(
      "expected the notice (top ${notice.top.value}dp) below the nav host's midpoint",
      notice.top.value > (navHost.top.value + navHost.bottom.value) / 2,
    )
  }

  /**
   * With the mini player showing, the notice moves up by its height rather than covering it —
   * otherwise the fix would trade a hidden toolbar for a hidden player.
   */
  @Test
  fun `the account notice clears the collapsed mini player`() {
    // One `setContent` per rule, so the state is driven from inside the composition rather than by
    // calling the helper twice — which silently keeps the first content and measures it again.
    val sheetState = mutableStateOf(BottomSheetState.HIDDEN)
    composeRule.setContent {
      ChronicleTheme {
        ChronicleApp(
          navController = rememberNavController(),
          isLoggedIn = true,
          showCollectionsTab = false,
          sheetState = sheetState.value,
          onTabSelected = {},
          miniPlayer = {},
          expandedPlayer = {},
          navHost = { Text(NAV_HOST, modifier = Modifier.fillMaxSize()) },
          accountNotice = { Text(NOTICE) },
        )
      }
    }

    val hidden = composeRule.onNodeWithText(NOTICE).getUnclippedBoundsInRoot().top.value

    composeRule.runOnIdle { sheetState.value = BottomSheetState.COLLAPSED }

    val collapsed = composeRule.onNodeWithText(NOTICE).getUnclippedBoundsInRoot().top.value

    assertTrue(
      "expected the notice to move up when the mini player appears: hidden=${hidden}dp, " +
        "collapsed=${collapsed}dp",
      collapsed < hidden,
    )
  }

  private companion object {
    const val NOTICE = "account notice"
    const val NAV_HOST = "nav host"

    /**
     * A `TopAppBar` is 64dp, and a sub-screen's sits at the top of the window. Anything starting
     * within this band is overlapping it.
     */
    const val TOOLBAR_BAND_DP = 64f
  }
}
