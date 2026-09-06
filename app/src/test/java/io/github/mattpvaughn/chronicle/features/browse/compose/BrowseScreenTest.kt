package io.github.mattpvaughn.chronicle.features.browse.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.mattpvaughn.chronicle.data.model.Facet
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.FacetList
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The browse screen, asserted on what it renders (cu-202).
 *
 * The View version had no rendering test — the facet list was a `RecyclerView` and the three
 * visibility decisions were spread across two `render` passes.
 */
@RunWith(RobolectricTestRunner::class)
class BrowseScreenTest {
  @get:Rule
  val compose = createComposeRule()

  private fun setScreen(
    state: BrowseUiState,
    onSelectFacet: (FacetKind) -> Unit = {},
    onFacetClick: (Facet) -> Unit = {},
  ) {
    compose.setContent {
      ChronicleTheme {
        BrowseScreen(state = state, onSelectFacet = onSelectFacet, onFacetClick = onFacetClick)
      }
    }
  }

  private fun loaded(
    kind: FacetKind = FacetKind.Author,
    facets: List<Facet> = listOf(Facet("Frank Herbert", 3)),
    unknownCount: Int = 0,
  ) = BrowseUiState(
    selected = kind,
    content = BrowseContent.Loaded(FacetList(kind, facets, unknownCount)),
  )

  /**
   * The regression the sealed type exists for.
   *
   * `facets` is seeded with `FacetList.EMPTY`, which is a facet list with no values — so a screen
   * driven straight off it says "No narrators yet" before any grouping has run. That claim is
   * about the user's library and it has not been checked.
   */
  @Test
  fun `nothing is claimed about the library while loading`() {
    setScreen(BrowseUiState(selected = FacetKind.Narrator, content = BrowseContent.Loading))

    // The real string, not a paraphrase: an assertion against text that does not exist passes
    // whatever the screen does.
    compose.onNodeWithText("No narrators known yet", substring = true).assertDoesNotExist()
  }

  @Test
  fun `an empty facet says so, in that facet's own words`() {
    setScreen(
      BrowseUiState(
        selected = FacetKind.Narrator,
        content = BrowseContent.Empty(FacetKind.Narrator),
      ),
    )

    compose.onNodeWithText("No narrators known yet", substring = true).assertIsDisplayed()
  }

  @Test
  fun `a facet row shows its value and its book count`() {
    setScreen(loaded(facets = listOf(Facet("Frank Herbert", 3))))

    compose.onNodeWithText("Frank Herbert").assertIsDisplayed()
    compose.onNodeWithText("3", substring = true).assertIsDisplayed()
  }

  @Test
  fun `tapping a facet reports which one`() {
    var clicked: Facet? = null
    setScreen(loaded(facets = listOf(Facet("Frank Herbert", 3))), onFacetClick = { clicked = it })

    compose.onNodeWithText("Frank Herbert").performClick()

    assertEquals("Frank Herbert", clicked?.value)
  }

  /**
   * A complete index must carry no caveat.
   *
   * cu-24's reasoning: a qualification shown when nothing is missing teaches the user to skip it,
   * so it is no longer read when it matters.
   */
  @Test
  fun `a complete index shows no coverage caveat`() {
    setScreen(loaded(unknownCount = 0))

    compose.onNodeWithText("synced", substring = true).assertDoesNotExist()
  }

  @Test
  fun `a partial index says how much it does not know`() {
    setScreen(loaded(unknownCount = 184))

    compose.onNodeWithText("184", substring = true).assertIsDisplayed()
  }

  @Test
  fun `tapping a tab reports the facet it selects`() {
    var selected: FacetKind? = null
    setScreen(loaded(), onSelectFacet = { selected = it })

    compose.onNodeWithText("Series", substring = true, ignoreCase = true).performClick()

    assertEquals(FacetKind.Series, selected)
  }
}
