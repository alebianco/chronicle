package io.github.mattpvaughn.chronicle.features.search

/**
 * What the search overlay is showing, for the three screens that carry one.
 *
 * Library, home and collections each wrote this out as two or three `isVisible` decisions over the
 * same three flows — and they had already drifted: library showed a "no results" message, home
 * never did. Extracting the decision is the same move as `chapterRows` and `progressState()`:
 * two screens rendering one thing must not disagree about it.
 */
sealed interface SearchOverlayState {
  /** The search field is closed. The screen behind shows through untouched. */
  data object Hidden : SearchOverlayState

  /**
   * Open, with nothing typed yet.
   *
   * Distinct from [NoResults] on purpose: an empty query has not failed to match anything, and
   * telling the user "no results" before they type reads as a broken search.
   */
  data object AwaitingQuery : SearchOverlayState

  /** A real query that matched nothing. */
  data object NoResults : SearchOverlayState

  data class Results(val rows: List<SearchRow>) : SearchOverlayState
}

/**
 * The overlay state for a query's outcome.
 *
 * Pure over the three facts the `SearchController` publishes, so the ordering between them is
 * testable without a screen — which matters because the interesting case is the *combination*
 * (active, empty query, no rows) that must not say "no results".
 */
fun searchOverlayState(
  isActive: Boolean,
  isQueryEmpty: Boolean,
  rows: List<SearchRow>,
): SearchOverlayState =
  when {
    !isActive -> SearchOverlayState.Hidden
    rows.isNotEmpty() -> SearchOverlayState.Results(rows)
    isQueryEmpty -> SearchOverlayState.AwaitingQuery
    else -> SearchOverlayState.NoResults
  }
