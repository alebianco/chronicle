package io.github.mattpvaughn.chronicle.features.browse.compose

import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.FacetList

/**
 * What the browse screen is showing, as one exhaustive state.
 *
 * Was three independent `isVisible` decisions over a `FacetList` seeded to `FacetList.EMPTY` —
 * and that seed is the problem the sealed type removes: `EMPTY` is indistinguishable from a
 * genuinely empty facet, so the screen announced "No narrators yet" before the first grouping had
 * run. Same defect class as the home shelves' empty states, and the reason
 * [BrowseContent.Loading] is a state rather than an empty list.
 */
data class BrowseUiState(
  val selected: FacetKind = FacetKind.Author,
  val content: BrowseContent = BrowseContent.Loading,
)

sealed interface BrowseContent {
  /** Nothing grouped yet. Neither the list nor the empty message may claim anything. */
  data object Loading : BrowseContent

  /**
   * The facet has no values.
   *
   * Carries its [kind] because the *reason* differs per facet and the wording says so — an author
   * is always present on a Plex album, a narrator only arrives with the per-book detail.
   */
  data class Empty(val kind: FacetKind) : BrowseContent

  /**
   * Values to show, and how many books contributed none.
   *
   * [FacetList.isPartial] decides whether the coverage line appears at all: a complete index must
   * not carry a caveat, or the caveat stops being read.
   */
  data class Loaded(val facets: FacetList) : BrowseContent
}
