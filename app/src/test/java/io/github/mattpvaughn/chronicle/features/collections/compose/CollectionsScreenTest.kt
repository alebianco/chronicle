package io.github.mattpvaughn.chronicle.features.collections.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The collections screen, tested by asserting **what is on screen**.
 *
 * Contrast `CollectionsFragmentScenarioTest`, which needs `launchFragmentInContainer`, a mocked
 * `ActivityComponent`, a real `ViewModelProvider.Factory` over four mocks and a hand-written
 * `SharedPreferences` fake — and can still only assert `view != null`, because reading a
 * RecyclerView's contents needs Espresso on a device.
 *
 * Here the screen is a function of its state, so the test states the input and reads the output.
 * No DI, no lifecycle, no fakes.
 *
 * Robolectric is still the *runner* (Compose needs an Android runtime to measure and draw), but
 * nothing from the app's graph is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class CollectionsScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  private fun collection(
    id: String,
    title: String,
  ) = Collection(id = id, source = SourceId("plex:test"), title = title)

  private fun setScreen(
    state: CollectionsUiState,
    onCollectionClick: (Collection) -> Unit = {},
    onDisableOfflineMode: () -> Unit = {},
  ) {
    composeRule.setContent {
      ChronicleTheme {
        CollectionsScreen(
          state = state,
          coverUrl = { "https://example.invalid/$it" },
          onCollectionClick = onCollectionClick,
          onDisableOfflineMode = onDisableOfflineMode,
        )
      }
    }
  }

  @Test
  fun `a populated library shows its collections`() {
    setScreen(
      CollectionsUiState(
        content =
          CollectionsContent.Loaded(
            listOf(collection("c1", "Mistborn"), collection("c2", "Stormlight")),
          ),
      ),
    )

    composeRule.onNodeWithText("Mistborn").assertIsDisplayed()
    composeRule.onNodeWithText("Stormlight").assertIsDisplayed()
  }

  @Test
  fun `an empty library shows the empty message`() {
    setScreen(CollectionsUiState(content = CollectionsContent.Empty))

    composeRule.onNodeWithText("No books found").assertIsDisplayed()
  }

  /**
   * The state the Fragment expresses as two independent `isVisible` flags, and the reason
   * [CollectionsContent] is sealed: an offline library is *unreachable*, not empty, and saying
   * otherwise is a trust bug.
   */
  @Test
  fun `an offline library says so, and offers a way out`() {
    setScreen(CollectionsUiState(content = CollectionsContent.OfflineEmpty))

    composeRule.onNodeWithText("No downloaded books found").assertIsDisplayed()
    composeRule.onNodeWithText("Disable offline mode").assertIsDisplayed()
  }

  @Test
  fun `the empty state does not offer to disable offline mode`() {
    setScreen(CollectionsUiState(content = CollectionsContent.Empty))

    composeRule.onNodeWithText("Disable offline mode").assertDoesNotExistSafely()
  }

  @Test
  fun `tapping a collection reports which one`() {
    var clicked: Collection? = null
    setScreen(
      CollectionsUiState(content = CollectionsContent.Loaded(listOf(collection("c1", "Mistborn")))),
      onCollectionClick = { clicked = it },
    )

    composeRule.onNodeWithText("Mistborn").performClick()

    assertEquals("c1", clicked?.id)
  }

  @Test
  fun `the disable-offline button reports the tap`() {
    var disabled = false
    setScreen(
      CollectionsUiState(content = CollectionsContent.OfflineEmpty),
      onDisableOfflineMode = { disabled = true },
    )

    composeRule.onNodeWithText("Disable offline mode").performClick()

    assertEquals(true, disabled)
  }

  /**
   * A populated screen must not *also* render an empty message. In the Fragment this is three
   * independent `isVisible` assignments that can disagree; here it is a sealed `when`, and this
   * test is what says so.
   */
  @Test
  fun `a populated library shows no empty message`() {
    setScreen(
      CollectionsUiState(content = CollectionsContent.Loaded(listOf(collection("c1", "Mistborn")))),
    )

    composeRule.onNodeWithText("No books found").assertDoesNotExistSafely()
    composeRule.onNodeWithText("No downloaded books found").assertDoesNotExistSafely()
  }

  @Test
  fun `a list view renders the same titles as a grid`() {
    val state =
      CollectionsUiState(
        content = CollectionsContent.Loaded(listOf(collection("c1", "Mistborn"))),
        isGrid = false,
      )
    setScreen(state)

    composeRule.onNodeWithText("Mistborn").assertIsDisplayed()
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistSafely() {
    assertNull(
      "node should not be present",
      runCatching { fetchSemanticsNode() }.getOrNull(),
    )
  }
}
