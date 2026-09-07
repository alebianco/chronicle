package io.github.mattpvaughn.chronicle.features.search.compose

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search bar shared by home, library and collections.
 *
 * Worth testing once precisely because it is shared: the three screens each carried their own copy
 * of this wiring through a `MenuProvider`, and they had already drifted on what an empty query
 * means. One composable cannot drift from itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class SearchTopBarTest {
  @get:Rule
  val composeRule = createComposeRule()

  private fun setContent(
    isActive: Boolean,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onActiveChange: (Boolean) -> Unit = {},
    withAction: Boolean = false,
  ) {
    composeRule.setContent {
      ChronicleTheme {
        SearchTopBar(
          title = "Library",
          isActive = isActive,
          query = query,
          onQueryChange = onQueryChange,
          onActiveChange = onActiveChange,
          actions = { if (withAction) Text("Filter") },
        )
      }
    }
  }

  @Test
  fun `inactive it shows the screen title`() {
    setContent(isActive = false)

    composeRule.onNodeWithText("Library").assertIsDisplayed()
  }

  @Test
  fun `tapping search activates it`() {
    var active: Boolean? = null
    setContent(isActive = false, onActiveChange = { active = it })

    composeRule.onNodeWithContentDescription("Search").performClick()

    assertEquals(true, active)
  }

  @Test
  fun `active it shows the query instead of the title`() {
    setContent(isActive = true, query = "dune")

    composeRule.onNodeWithText("dune").assertIsDisplayed()
    assertEquals(
      "the title must give way to the field",
      0,
      composeRule.onAllNodesWithText("Library").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `typing reports every keystroke`() {
    val typed = mutableListOf<String>()
    setContent(isActive = true, onQueryChange = { typed += it })

    composeRule.onNodeWithText("Search").performTextInput("du")

    assertTrue("expected the field to report input, saw $typed", typed.isNotEmpty())
  }

  /**
   * Closing search clears the query as well as deactivating.
   *
   * The `SearchView` owned its own text, so closing it discarded the query as a side effect. With
   * the query hoisted, forgetting to clear leaves stale results behind the next time search opens.
   */
  @Test
  fun `closing search clears the query`() {
    var active: Boolean? = null
    var cleared: String? = null
    setContent(
      isActive = true,
      query = "dune",
      onQueryChange = { cleared = it },
      onActiveChange = { active = it },
    )

    composeRule.onNodeWithContentDescription("Back").performClick()

    assertEquals(false, active)
    assertEquals("", cleared)
  }

  /**
   * Extra actions are hidden while searching.
   *
   * `LibraryFragment` achieved this by flipping `showAsAction` to NEVER on two menu items as the
   * SearchView expanded, and back to IF_ROOM on collapse — the one place the three copies of this
   * wiring genuinely differed.
   */
  @Test
  fun `actions are hidden while searching`() {
    setContent(isActive = true, withAction = true)

    assertEquals(
      0,
      composeRule.onAllNodesWithText("Filter").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `actions are shown when not searching`() {
    setContent(isActive = false, withAction = true)

    composeRule.onNodeWithText("Filter").assertIsDisplayed()
  }
}
