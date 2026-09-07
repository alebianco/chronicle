package io.github.mattpvaughn.chronicle.features.login.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The login picker, tested by asserting what is on screen.
 *
 * This one composable is **four** of the screens that had no regression test: the server, library
 * and user pickers are the same list gated on `LoadingStatus`, and the login step that lists
 * accounts renders through it too. Each was previously a Fragment writing the same three
 * `isVisible` assignments — a three-state machine spelled as booleans, where nothing stopped two
 * being true at once.
 *
 * So the state cases below are the point, not the row rendering: the value of the migration was
 * making the contradiction unrepresentable, and these tests are what stop a future change from
 * reintroducing it. Every one of this codebase's worst UI defects lived in exactly this layer.
 *
 * Robolectric is the runner because Compose needs an Android runtime to measure and draw; nothing
 * from the app's graph is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class PickerScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  /**
   * A row whose `id`, `title` and `value` are all **different strings**.
   *
   * Deliberate: a first draft set `value = id`, and a sabotage that made the row report its `id`
   * instead of its `value` passed unnoticed, because the two were indistinguishable. `PickerItem`
   * keeps them separate for a reason — `id` is the `LazyColumn` key, `value` is what the caller
   * acts on — so the fixture has to keep them separate too or the click test proves nothing.
   */
  private fun item(
    id: String,
    title: String,
    subtitle: String? = null,
  ) = PickerItem(id = id, title = title, subtitle = subtitle, value = "value-of-$id")

  private fun setScreen(
    status: LoadingStatus,
    items: List<PickerItem<String>> = emptyList(),
    errorMessage: String = "Could not reach the server",
    onItemClick: (String) -> Unit = {},
  ) {
    composeRule.setContent {
      ChronicleTheme {
        PickerScreen(
          status = status,
          items = items,
          errorMessage = errorMessage,
          onItemClick = onItemClick,
        )
      }
    }
  }

  @Test
  fun `a populated list shows every row`() {
    setScreen(
      status = LoadingStatus.DONE,
      items = listOf(item("s1", "ANTARES"), item("s2", "VEGA")),
    )

    composeRule.onNodeWithText("ANTARES").assertIsDisplayed()
    composeRule.onNodeWithText("VEGA").assertIsDisplayed()
  }

  @Test
  fun `a subtitle is rendered when present and absent when not`() {
    setScreen(
      status = LoadingStatus.DONE,
      items = listOf(item("s1", "ANTARES", subtitle = "192.168.1.54"), item("s2", "VEGA")),
    )

    composeRule.onNodeWithText("192.168.1.54").assertIsDisplayed()
    composeRule.onNodeWithText("VEGA").assertIsDisplayed()
    // The subtitle belongs to ANTARES and to nothing else. Asserting the *count* rather than mere
    // presence is what would catch a `?: ""` replacing the `let`: an empty second line renders a
    // node no text query matches, so only counting the rows that do have one stays honest.
    assertEquals(
      "only one row may carry a subtitle",
      1,
      composeRule.onAllNodesWithText("192.168.1.54").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `clicking a row reports that row's value, not its label`() {
    var clicked: String? = null
    setScreen(
      status = LoadingStatus.DONE,
      items = listOf(item("s1", "ANTARES"), item("s2", "VEGA")),
      onItemClick = { clicked = it },
    )

    composeRule.onNodeWithText("VEGA").performClick()

    // The `value`, not the `id` and not the title. A picker that reported its display text would
    // break the moment two servers share a name; one that reported its `LazyColumn` key would hand
    // the caller an identifier it never asked for.
    assertEquals("value-of-s2", clicked)
  }

  @Test
  fun `loading shows no rows and no error`() {
    setScreen(
      status = LoadingStatus.LOADING,
      items = listOf(item("s1", "ANTARES")),
      errorMessage = "Could not reach the server",
    )

    // Items are deliberately passed while loading: a caller can hold stale data, and showing it
    // under a spinner is the contradiction the boolean triads allowed.
    composeRule.onNodeWithText("ANTARES").assertDoesNotExist()
    composeRule.onNodeWithText("Could not reach the server").assertDoesNotExist()
  }

  @Test
  fun `error shows the message and no rows`() {
    setScreen(
      status = LoadingStatus.ERROR,
      items = listOf(item("s1", "ANTARES")),
      errorMessage = "Could not reach the server",
    )

    composeRule.onNodeWithText("Could not reach the server").assertIsDisplayed()
    composeRule.onNodeWithText("ANTARES").assertDoesNotExist()
  }

  @Test
  fun `the error message is reachable as a content description`() {
    // `CenteredMessage` sets one deliberately: a screen whose only content is an error must be
    // announced, and the login flow is where a first-run user is most likely to be stuck.
    setScreen(status = LoadingStatus.ERROR, errorMessage = "No servers found")

    composeRule.onNodeWithContentDescription("No servers found").assertIsDisplayed()
  }

  @Test
  fun `an empty done list renders nothing rather than an error`() {
    // DONE with nothing in it is a real state — an account with no servers — and it must not be
    // mistaken for a failure. "No books found over a full library" was this shape of bug.
    setScreen(status = LoadingStatus.DONE, items = emptyList(), errorMessage = "Boom")

    composeRule.onNodeWithText("Boom").assertDoesNotExist()
  }
}
