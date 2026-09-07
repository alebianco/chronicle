package io.github.mattpvaughn.chronicle.views.compose

import androidx.compose.material3.Text
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
 * The standard screen frame.
 *
 * The back arrow's presence is the property worth pinning: it is driven by `onNavigateUp` being
 * non-null, which is how a top-level tab differs from a pushed screen. The XML expressed the same
 * thing by simply not calling `setNavigationOnClickListener` — invisible in the layout, and easy
 * to get wrong in either direction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class ChronicleScaffoldTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `it shows its title and body`() {
    composeRule.setContent {
      ChronicleTheme {
        ChronicleScaffold(title = "Browse") { Text("body") }
      }
    }

    composeRule.onNodeWithText("Browse").assertIsDisplayed()
    composeRule.onNodeWithText("body").assertIsDisplayed()
  }

  @Test
  fun `a top-level screen has no back arrow`() {
    composeRule.setContent {
      ChronicleTheme {
        ChronicleScaffold(title = "Home", onNavigateUp = null) { Text("body") }
      }
    }

    assertEquals(
      "a tab is a root; showing an up arrow there offers a navigation that does not exist",
      0,
      composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `a pushed screen navigates up`() {
    var up = 0
    composeRule.setContent {
      ChronicleTheme {
        ChronicleScaffold(title = "Details", onNavigateUp = { up++ }) { Text("body") }
      }
    }

    composeRule.onNodeWithContentDescription("Back").performClick()

    assertEquals(1, up)
  }

  @Test
  fun `actions are rendered in the bar`() {
    composeRule.setContent {
      ChronicleTheme {
        ChronicleScaffold(title = "Details", actions = { Text("Sync") }) { Text("body") }
      }
    }

    composeRule.onNodeWithText("Sync").assertIsDisplayed()
  }
}
