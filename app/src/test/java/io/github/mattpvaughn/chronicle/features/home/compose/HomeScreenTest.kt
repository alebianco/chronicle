package io.github.mattpvaughn.chronicle.features.home.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The home shelves.
 *
 * Three shelves make the seed question sharper than elsewhere: `Loaded(empty, empty, empty)` is
 * indistinguishable from a genuinely empty library, so it would flash "no books found" on every
 * cold start.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun book(
    id: String,
    title: String,
  ) = Audiobook(id = id, source = TEST_SOURCE, title = title, author = "Author")

  private fun setScreen(
    state: HomeUiState,
    onBookClick: (Audiobook) -> Unit = {},
    onResumeClick: (Audiobook) -> Unit = {},
  ) {
    compose.setContent {
      ChronicleTheme {
        HomeScreen(
          state = state,
          coverUrl = { "http://localhost/$it" },
          onBookClick = onBookClick,
          onResumeClick = onResumeClick,
          onDisableOfflineMode = {},
        )
      }
    }
  }

  @Test
  fun `a shelf with books shows its title and contents`() {
    setScreen(
      HomeUiState(
        content =
          HomeContent.Loaded(
            downloaded = emptyList(),
            recentlyListened = listOf(book("1", "Dune")),
            recentlyAdded = emptyList(),
          ),
      ),
    )

    compose.onNodeWithText("RECENTLY LISTENED").assertIsDisplayed()
    compose.onNodeWithText("Dune").assertIsDisplayed()
  }

  /** An empty shelf renders nothing at all — not an empty row with a heading. */
  @Test
  fun `an empty shelf shows no title`() {
    setScreen(
      HomeUiState(
        content =
          HomeContent.Loaded(
            downloaded = emptyList(),
            recentlyListened = listOf(book("1", "Dune")),
            recentlyAdded = emptyList(),
          ),
      ),
    )

    assertEquals(
      "an empty shelf must not render a heading",
      0,
      compose.onAllNodesWithText("AVAILABLE OFFLINE").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `the loading seed claims nothing`() {
    setScreen(HomeUiState(content = HomeContent.Loading))

    assertEquals(
      0,
      compose.onAllNodesWithText("No books found").fetchSemanticsNodes().size,
    )
  }

  /**
   * Continue Listening resumes on tap rather than opening details — the whole point, and the
   * only shelf with distinct behaviour. Losing it would be a silent behaviour change.
   */
  @Test
  fun `tapping continue listening resumes rather than opening details`() {
    var resumed: Audiobook? = null
    var opened: Audiobook? = null
    setScreen(
      state =
        HomeUiState(
          content =
            HomeContent.Loaded(
              downloaded = emptyList(),
              recentlyListened = listOf(book("1", "Dune")),
              recentlyAdded = emptyList(),
            ),
        ),
      onBookClick = { opened = it },
      onResumeClick = { resumed = it },
    )

    compose.onNodeWithText("Dune").performClick()

    assertEquals("the continue-listening shelf must resume", "Dune", resumed?.title)
    assertEquals("and must not open details", null, opened)
  }

  /** Whereas Recently Added opens details, as it always did. */
  @Test
  fun `tapping recently added opens details`() {
    var opened: Audiobook? = null
    setScreen(
      state =
        HomeUiState(
          content =
            HomeContent.Loaded(
              downloaded = emptyList(),
              recentlyListened = emptyList(),
              recentlyAdded = listOf(book("2", "Mistborn")),
            ),
        ),
      onBookClick = { opened = it },
    )

    compose.onNodeWithText("Mistborn").performClick()

    assertEquals("Mistborn", opened?.title)
  }
}
