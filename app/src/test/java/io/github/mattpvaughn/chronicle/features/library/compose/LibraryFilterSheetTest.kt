package io.github.mattpvaughn.chronicle.features.library.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The library's sort/view-style panel (cu-206).
 *
 * Replaces a persistent `BottomSheetBehavior` whose state was kept in step with the ViewModel by a
 * `BottomSheetCallback` pushing one way and a flow collector pushing back. The interesting
 * property now is simply that a chip reports the **stored key** rather than its label — the two
 * were one value in the XML (`android:tag="@string/key_sort_by_title"`), which is the shape
 * CLAUDE.md warns about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1920dp")
class LibraryFilterSheetTest {
  @get:Rule
  val composeRule = createComposeRule()

  private val sortOptions =
    listOf(
      FilterOption("title", R.string.sort_by_title),
      FilterOption("author", R.string.sort_by_author),
    )

  private val viewStyles =
    listOf(FilterOption("view_style_cover_grid", R.string.view_style_book_cover))

  private fun setContent(
    selectedSortKey: String = "title",
    hidePlayed: Boolean = false,
    onSortKeyChange: (String) -> Unit = {},
    onToggleHidePlayed: () -> Unit = {},
    onDismiss: () -> Unit = {},
  ) {
    composeRule.setContent {
      ChronicleTheme {
        LibraryFilterSheet(
          sortOptions = sortOptions,
          selectedSortKey = selectedSortKey,
          onSortKeyChange = onSortKeyChange,
          isSortDescending = false,
          onToggleSortDirection = {},
          viewStyleOptions = viewStyles,
          selectedViewStyleKey = "view_style_cover_grid",
          onViewStyleChange = {},
          hidePlayed = hidePlayed,
          onToggleHidePlayed = onToggleHidePlayed,
          onDismiss = onDismiss,
        )
      }
    }
  }

  @Test
  fun `it shows the sort options`() {
    setContent()

    composeRule.onNodeWithText("Title").assertIsDisplayed()
    composeRule.onNodeWithText("Author").assertIsDisplayed()
  }

  /**
   * A chip reports the **stored key**, never its own label.
   *
   * The XML kept both in `android:tag="@string/key_sort_by_title"`. Those keys live in
   * `strings_no_translate.xml` so they could not actually be localised out from under the lookup,
   * but `FilterOption` splits them so the hazard cannot return if that file ever changes.
   */
  @Test
  fun `choosing a chip reports its key rather than its label`() {
    var chosen: String? = null
    setContent(onSortKeyChange = { chosen = it })

    composeRule.onNodeWithText("Author").performClick()

    assertEquals("author", chosen)
  }

  @Test
  fun `done dismisses the sheet`() {
    var dismissed = 0
    setContent(onDismiss = { dismissed++ })

    composeRule.onNodeWithText("Done").performClick()

    assertEquals(1, dismissed)
  }
}
