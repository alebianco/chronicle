package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The library grid.
 *
 * The states matter more than the grid here: the Fragment decided between them with two cached
 * locals whose seeds once rendered "No books found" over a full library, which is the bug
 * `CollectorCachesItsValueTest` exists for.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun book(
    id: String,
    title: String,
  ) = Audiobook(id = id, source = TEST_SOURCE, title = title, author = "Author")

  private fun setScreen(
    state: LibraryUiState,
    onBookClick: (Audiobook) -> Unit = {},
    onDisableOfflineMode: () -> Unit = {},
  ) {
    compose.setContent {
      ChronicleTheme {
        LibraryScreen(
          state = state,
          coverUrl = { "http://localhost/$it" },
          onBookClick = onBookClick,
          onDisableOfflineMode = onDisableOfflineMode,
        )
      }
    }
  }

  @Test
  fun `a populated library shows its books`() {
    setScreen(LibraryUiState(content = LibraryContent.Loaded(listOf(book("1", "Dune")))))

    compose.onNodeWithText("Dune").assertIsDisplayed()
  }

  /**
   * The seed renders nothing at all.
   *
   * `Loaded(emptyList())` would be indistinguishable from a genuinely empty library, so a cold
   * start would flash "No books found" before Room's first emission — the bug, and the reason
   * the seed is its own branch.
   */
  @Test
  fun `the loading seed claims nothing`() {
    setScreen(LibraryUiState(content = LibraryContent.Loading))

    assertEquals(
      "nothing may be claimed before the first emission",
      0,
      compose.onAllNodesWithText("No books found").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `an empty library online says so`() {
    setScreen(LibraryUiState(content = LibraryContent.Empty))

    compose.onNodeWithText("No books found").assertIsDisplayed()
  }

  /** Offline-and-empty is a different claim from empty, and offers a way out. */
  @Test
  fun `an empty library offline offers to go back online`() {
    setScreen(LibraryUiState(content = LibraryContent.OfflineEmpty))

    compose.onNodeWithText("No downloaded books found").assertIsDisplayed()
    compose.onNodeWithText("Disable offline mode").assertIsDisplayed()
  }

  @Test
  fun `the empty state does not offer to disable offline mode`() {
    setScreen(LibraryUiState(content = LibraryContent.Empty))

    assertEquals(
      0,
      compose.onAllNodesWithText("Disable offline mode").fetchSemanticsNodes().size,
    )
  }

  @Test
  fun `tapping a book reports which one`() {
    var clicked: Audiobook? = null
    setScreen(
      state = LibraryUiState(content = LibraryContent.Loaded(listOf(book("1", "Dune")))),
      onBookClick = { clicked = it },
    )

    compose.onNodeWithText("Dune").performClick()

    assertEquals("Dune", clicked?.title)
  }

  /** All three styles render; a two-way boolean would have dropped Details. */
  @Test
  fun `a details-list library renders its books`() {
    setScreen(
      LibraryUiState(
        content = LibraryContent.Loaded(listOf(book("1", "Dune"))),
        style = ViewStyleKind.Details,
      ),
    )

    compose.onNodeWithText("Dune").assertIsDisplayed()
  }
}
