package io.github.mattpvaughn.chronicle.data.model

/**
 * What the series-index rules tester shows, independent of any UI.
 *
 * Kept out of the ViewModel so the interesting decisions — which titles are worth offering, and
 * what a verdict looks like — are testable without Android. The screen is presentation over this.
 */
object SeriesIndexDiagnostics {
  /** How many sample titles are worth offering. Enough to be useful, few enough to scan. */
  const val SAMPLE_LIMIT = 25

  /**
   * The titles a user would most plausibly write a rule for: the ones that currently parse to **no
   * position at all**.
   *
   * That set, rather than every title, is the fourth criterion and the reason the tester is
   * useful before a user has written anything — it answers "does my library even need a rule?"
   * with their own data.
   *
   * Books with no `titleSort` are excluded: there is nothing for a rule to match, so offering them
   * would pad the list with entries no rule could ever fix. Duplicates are collapsed, because a
   * series tagged uniformly contributes the same *shape* many times and one example is enough to
   * test against.
   */
  fun unparsedTitleSorts(
    books: List<Audiobook>,
    limit: Int = SAMPLE_LIMIT,
  ): List<String> =
    books.asSequence()
      .map { it.titleSort }
      .filter { it.isNotBlank() }
      .filter { Audiobook.seriesIndexFromTitleSort(it) == Audiobook.NO_SERIES_INDEX }
      .distinct()
      .take(limit)
      .toList()

  /**
   * A one-line summary of how the library parses today, for the top of the tester.
   *
   * [unparsed] counts books whose `titleSort` yields no position — *not* books with no series. A
   * standalone novel legitimately has no position, so this number is an upper bound on "rules could
   * help", never a defect count. The screen must word it that way; measured against the owner's own
   * library it is 58 of 196, and most of those are genuinely standalone.
   */
  fun summarise(books: List<Audiobook>): LibraryParseSummary {
    val withTitleSort = books.filter { it.titleSort.isNotBlank() }
    val parsed =
      withTitleSort.count {
        Audiobook.seriesIndexFromTitleSort(it.titleSort) != Audiobook.NO_SERIES_INDEX
      }
    return LibraryParseSummary(
      total = books.size,
      withTitleSort = withTitleSort.size,
      parsed = parsed,
      unparsed = withTitleSort.size - parsed,
    )
  }
}

/** How a whole library parses under the current rules. */
data class LibraryParseSummary(
  val total: Int,
  val withTitleSort: Int,
  val parsed: Int,
  val unparsed: Int,
)
