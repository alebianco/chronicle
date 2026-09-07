package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.model.LibraryParseSummary
import io.github.mattpvaughn.chronicle.data.model.PatternAttempt
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The series-index tester, asserted on what it renders.
 *
 * The View version's rendering was only reachable through two adapters and eight `isVisible`
 * decisions; the two adapters had unit tests, the screen that combined them had none.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesIndexTesterScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun setScreen(
    state: SeriesIndexTesterUiState,
    onTitleSortChanged: (String) -> Unit = {},
    onSampleChosen: (String) -> Unit = {},
  ) {
    compose.setContent {
      ChronicleTheme {
        SeriesIndexTesterScreen(
          state = state,
          onTitleSortChanged = onTitleSortChanged,
          onSampleChosen = onSampleChosen,
        )
      }
    }
  }

  private fun matched(
    name: String,
    index: String = "2",
  ) = PatternAttempt(name, matched = true, capturedIndex = index)

  private fun rejected(
    name: String,
    reason: String = "No match",
  ) = PatternAttempt(name, matched = false, rejectedReason = reason)

  /**
   * The regression the View version needed a workaround for.
   *
   * `winningRule` was a `StateFlow`, so testing two different unparseable titles emitted `null`
   * twice and the second was conflated away — the "no rule read a position" headline never
   * appeared. The fix there was to drive the headline off `attempts` instead; here the winner is
   * derived from the state being rendered, so a second unparseable title is a *different* state
   * and there is nothing to drop.
   */
  @Test
  fun `a second unparseable title still reports that nothing matched`() {
    // Driven through one composition rather than two `setContent` calls, so this exercises
    // *recomposition* — which is where the original conflation bug lived.
    val state =
      mutableStateOf(
        SeriesIndexTesterUiState(titleSort = "The Hobbit", attempts = listOf(rejected("audnexus"))),
      )
    compose.setContent {
      ChronicleTheme {
        SeriesIndexTesterScreen(
          state = state.value,
          onTitleSortChanged = {},
          onSampleChosen = {},
        )
      }
    }

    compose.onNodeWithText("No rule read a position", substring = true).assertIsDisplayed()

    // A different title, same verdict. As a `StateFlow<PatternAttempt?>` this emitted `null`
    // twice and the second was dropped, so the headline vanished.
    state.value = state.value.copy(titleSort = "Dune")

    compose.onNodeWithText("No rule read a position", substring = true).assertIsDisplayed()
  }

  /** Nothing is claimed before the user has typed anything. */
  @Test
  fun `no verdict is shown for an empty input`() {
    setScreen(SeriesIndexTesterUiState(titleSort = "", attempts = emptyList()))

    compose.onNodeWithText("No rule read a position", substring = true).assertDoesNotExist()
  }

  /**
   * Which rule *decided* is the distinction the screen exists for.
   *
   * More than one rule routinely matches — `"Mistborn, Book 2 - …"` satisfies both `audnexus` and
   * `seanap` — and first-match-wins is the disambiguation mechanism. A flat "matched"
   * list leaves the user unable to tell which reading the app took.
   */
  @Test
  fun `only the first matching rule is reported as deciding`() {
    setScreen(
      SeriesIndexTesterUiState(
        titleSort = "Mistborn, Book 2 - The Well of Ascension",
        attempts = listOf(matched("audnexus"), matched("seanap")),
      ),
    )

    compose.onNodeWithText("this is the rule that decided", substring = true).assertIsDisplayed()
    compose.onNodeWithText("an earlier rule decided first", substring = true).assertIsDisplayed()
  }

  @Test
  fun `a rejected rule shows why it did not match`() {
    setScreen(
      SeriesIndexTesterUiState(
        titleSort = "The Hobbit",
        attempts = listOf(rejected("audnexus", reason = "No Book or Vol label")),
      ),
    )

    compose.onNodeWithText("No Book or Vol label", substring = true).assertIsDisplayed()
  }

  /**
   * The mirror risk: a message that means "nothing needs fixing" must not appear before
   * we know what is in the library, or it reassures the user about a library nobody has read.
   */
  @Test
  fun `nothing is said about samples before the library has been read`() {
    setScreen(SeriesIndexTesterUiState(summary = null, samples = emptyList()))

    compose.onNodeWithText("already gives a series position", substring = true).assertDoesNotExist()
  }

  @Test
  fun `an empty sample list after loading says nothing needs fixing`() {
    setScreen(
      SeriesIndexTesterUiState(
        summary = LibraryParseSummary(total = 196, withTitleSort = 138, parsed = 138, unparsed = 0),
        samples = emptyList(),
      ),
    )

    compose.onNodeWithText("already gives a series position", substring = true).assertIsDisplayed()
  }

  @Test
  fun `tapping a sample reports the title it chose`() {
    var chosen: String? = null
    setScreen(
      SeriesIndexTesterUiState(
        summary = LibraryParseSummary(total = 2, withTitleSort = 2, parsed = 0, unparsed = 2),
        samples = listOf("The Hobbit", "Dune"),
      ),
      onSampleChosen = { chosen = it },
    )

    compose.onNodeWithText("Dune").performClick()

    assertEquals("Dune", chosen)
  }

  /**
   * "No rules of your own" is not a [io.github.mattpvaughn.chronicle.data.model.PatternOrder]
   * value: an order only means something once a user rule exists, and reporting "tried before the
   * built-in ones" when none were written states something false about the configuration.
   */
  @Test
  fun `a user with no rules is not told where their rules run`() {
    setScreen(SeriesIndexTesterUiState(ruleOrderLabel = RuleOrderLabel.NoUserRules))

    compose.onNodeWithText("No rules of your own", substring = true).assertIsDisplayed()
    compose.onNodeWithText("tried before", substring = true).assertDoesNotExist()
  }
}
