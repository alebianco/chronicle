package io.github.mattpvaughn.chronicle.application.compose

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState
import io.github.mattpvaughn.chronicle.navigation.Destination
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every bottom-navigation tab is announced to a screen reader, **including the selected one**.
 *
 * The selected tab had no content description at all. `alwaysShowLabel = false` renders the label
 * only for the selected item, `NavigationBarItem` merges its descendants' semantics, and the merged
 * `Text` replaced the icon's `contentDescription` — so the three unselected tabs exposed one and the
 * tab the user was actually *on* did not.
 *
 * For a sighted user nothing looked wrong, since the label is right there. For a screen-reader user
 * the item they are on was announced differently from every other one.
 *
 * It also broke the instrumented suite silently. `LoggedInLaunchTest` asserted
 * `onNodeWithContentDescription("Home")`, which was correct when written and started failing once
 * Home became the launch destination — **six days of red CI, diagnosed twice as something else**
 * (a missing login fixture, then a race) before a `uiautomator` dump showed the node was simply not
 * there.
 *
 * Asserted on **both** a selected and an unselected tab, and after navigating, because fixing only
 * the launch destination would leave the same hole one tap away.
 *
 * On the **merged** tree deliberately. The merge is the mechanism of the bug — the icon's
 * description survives fine in the unmerged tree, so `useUnmergedTree = true` would pass against
 * the defect and prove nothing. A screen reader reads the merged node, and so does this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class BottomBarSemanticsTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `every tab exposes a content description, selected and unselected alike`() {
    setShell()

    TAB_LABELS.forEach { label ->
      composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
    }
  }

  /**
   * And after moving to another tab, so this is not fixed only for the launch destination — which is
   * the shape the original defect had.
   */
  @Test
  fun `every tab still exposes one after navigating to a different tab`() {
    val controller = setShell()

    composeRule.runOnIdle { controller.navigate(Destination.Settings.route) }

    TAB_LABELS.forEach { label ->
      composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
    }
  }

  private fun setShell(): androidx.navigation.NavHostController {
    lateinit var controller: androidx.navigation.NavHostController
    composeRule.setContent {
      controller = rememberNavController()
      ChronicleTheme {
        ChronicleApp(
          navController = controller,
          isLoggedIn = true,
          showCollectionsTab = false,
          sheetState = BottomSheetState.HIDDEN,
          onTabSelected = {},
          miniPlayer = {},
          expandedPlayer = {},
          navHost = {
            NavHost(controller, startDestination = Destination.Home.route) {
              composable(Destination.Home.route) { Text("home") }
              composable(Destination.Library.route) { Text("library") }
              composable(Destination.Settings.route) { Text("settings") }
            }
          },
          accountNotice = {},
        )
      }
    }
    return controller
  }

  private companion object {
    /**
     * Collections is hidden in this configuration, matching the tablet's real state — the same three
     * tabs the `uiautomator` dump that found this listed.
     */
    val TAB_LABELS = listOf("Home", "Library", "Settings")
  }
}
