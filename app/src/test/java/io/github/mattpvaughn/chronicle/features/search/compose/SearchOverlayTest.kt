package io.github.mattpvaughn.chronicle.features.search.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.SearchField
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.features.search.SearchOverlayState
import io.github.mattpvaughn.chronicle.features.search.SearchRow
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The search overlay, shared by library, home and collections (cu-202).
 *
 * `GroupedSearchAdapter` had a unit test that drove its ViewHolders directly; this asserts what a
 * reader actually sees, which is what the adapter test could not.
 */
@RunWith(RobolectricTestRunner::class)
class SearchOverlayTest {
  @get:Rule
  val compose = createComposeRule()

  private fun book(
    id: String = "1",
    title: String = "Dune",
    author: String = "Frank Herbert",
  ) = Audiobook(id = id, source = SourceId.UNKNOWN, title = title, author = author)

  private fun setOverlay(
    state: SearchOverlayState,
    onBookClick: (Audiobook) -> Unit = {},
    serverConnected: Boolean = true,
  ) {
    compose.setContent {
      ChronicleTheme {
        SearchOverlay(
          state = state,
          serverConnected = serverConnected,
          coverUrl = { it },
          onBookClick = onBookClick,
        )
      }
    }
  }

  @Test
  fun `a hidden overlay draws nothing`() {
    setOverlay(SearchOverlayState.Hidden)

    compose.onNodeWithText("Dune").assertDoesNotExist()
  }

  /** An empty query has not failed to match anything, so it must not say so. */
  @Test
  fun `an awaiting-query overlay makes no claim about results`() {
    setOverlay(SearchOverlayState.AwaitingQuery)

    compose.onNodeWithText("No books found", substring = true).assertDoesNotExist()
  }

  @Test
  fun `a query that matched nothing says so`() {
    setOverlay(SearchOverlayState.NoResults)

    compose.onNodeWithText("No books found", substring = true).assertIsDisplayed()
  }

  @Test
  fun `a group header and its books are shown`() {
    setOverlay(
      SearchOverlayState.Results(
        listOf(
          SearchRow.Header(SearchField.Title, 1),
          SearchRow.Book(book(), SearchField.Title, "Dune"),
        ),
      ),
    )

    compose.onNodeWithText("Books").assertIsDisplayed()
    compose.onNodeWithText("Dune").assertIsDisplayed()
  }

  /**
   * Under a narrator heading the *matched narrator* is shown, not the author.
   *
   * A book listed under "Narrators" showing only its title gives the user no way to tell which of
   * several narrators matched (cu-25).
   */
  @Test
  fun `a narrator match says who narrated it rather than repeating the author`() {
    setOverlay(
      SearchOverlayState.Results(
        listOf(
          SearchRow.Header(SearchField.Narrator, 1),
          SearchRow.Book(book(), SearchField.Narrator, "Scott Brick"),
        ),
      ),
    )

    compose.onNodeWithText("Scott Brick", substring = true).assertIsDisplayed()
    compose.onNodeWithText("Frank Herbert").assertDoesNotExist()
  }

  /**
   * The same book under two headings is two rows.
   *
   * Identity is the book *within its group*; keying on the id alone would be a duplicate key,
   * which for a `LazyColumn` is a crash rather than a silent merge.
   */
  @Test
  fun `the same book can appear under two headings`() {
    setOverlay(
      SearchOverlayState.Results(
        listOf(
          SearchRow.Header(SearchField.Title, 1),
          SearchRow.Book(book(), SearchField.Title, "Dune"),
          SearchRow.Header(SearchField.Author, 1),
          SearchRow.Book(book(), SearchField.Author, "Frank Herbert"),
        ),
      ),
    )

    compose.onNodeWithText("Books").assertIsDisplayed()
    compose.onNodeWithText("Authors").assertIsDisplayed()
  }

  @Test
  fun `tapping a result reports the book`() {
    var clicked: Audiobook? = null
    setOverlay(
      SearchOverlayState.Results(
        listOf(
          SearchRow.Header(SearchField.Title, 1),
          SearchRow.Book(book(), SearchField.Title, "Dune"),
        ),
      ),
      onBookClick = { clicked = it },
    )

    compose.onNodeWithText("Dune").performClick()

    assertEquals("1", clicked?.id)
  }
}
