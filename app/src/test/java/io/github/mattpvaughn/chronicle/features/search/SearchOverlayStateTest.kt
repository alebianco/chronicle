package io.github.mattpvaughn.chronicle.features.search

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.SearchField
import io.github.mattpvaughn.chronicle.data.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The search overlay's decision, which three screens share (cu-202).
 *
 * Framework-free, so it fails for the right reason: the interesting case is a *combination* of
 * three booleans, and a rendering test could only reach it through a whole screen.
 */
class SearchOverlayStateTest {
  private val rows =
    listOf(
      SearchRow.Header(SearchField.Title, 1),
      SearchRow.Book(
        Audiobook(id = "1", source = SourceId.UNKNOWN),
        SearchField.Title,
        "x",
      ),
    )

  @Test
  fun `a closed search shows nothing`() {
    assertEquals(
      SearchOverlayState.Hidden,
      searchOverlayState(isActive = false, isQueryEmpty = true, rows = emptyList()),
    )
  }

  /** Stale rows must not survive the overlay closing — the next open would show the old answer. */
  @Test
  fun `a closed search hides even when rows are still held`() {
    assertEquals(
      SearchOverlayState.Hidden,
      searchOverlayState(isActive = false, isQueryEmpty = false, rows = rows),
    )
  }

  /**
   * The case the three screens had drifted on.
   *
   * An empty query has not *failed* to match anything. Saying "no results" the moment the field
   * opens reads as a broken search — library said it, home did not.
   */
  @Test
  fun `an open search with nothing typed does not claim there are no results`() {
    assertEquals(
      SearchOverlayState.AwaitingQuery,
      searchOverlayState(isActive = true, isQueryEmpty = true, rows = emptyList()),
    )
  }

  @Test
  fun `a real query matching nothing says so`() {
    assertEquals(
      SearchOverlayState.NoResults,
      searchOverlayState(isActive = true, isQueryEmpty = false, rows = emptyList()),
    )
  }

  @Test
  fun `matches are shown`() {
    assertEquals(
      SearchOverlayState.Results(rows),
      searchOverlayState(isActive = true, isQueryEmpty = false, rows = rows),
    )
  }
}
