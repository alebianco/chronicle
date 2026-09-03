package io.github.mattpvaughn.chronicle.data.model

/**
 * Grouping the library by author, narrator or series (cu-24).
 *
 * Pure over a book list so the grouping, the counts and the honesty about partial coverage are all
 * testable without a database or a screen.
 */
enum class FacetKind {
  Author,
  Narrator,
  Series,
}

/** One row in a facet list: a value, and how many books carry it. */
data class Facet(
  val value: String,
  val bookCount: Int,
)

/**
 * A facet list, plus what it does **not** know.
 *
 * [unknownCount] exists because narrator and series come only from the per-book detail response
 * (`Style`/`Mood`), so a library that has never been fully synced yields a partial index. A screen
 * listing 12 narrators out of 196 books without saying so reads as "these are the narrators I
 * have", which is worse than showing nothing — so the count travels with the list and the UI is
 * obliged to have it.
 */
data class FacetList(
  val kind: FacetKind,
  val facets: List<Facet>,
  val unknownCount: Int,
) {
  /** Whether anything is missing, i.e. whether the UI must qualify what it is showing. */
  val isPartial: Boolean
    get() = unknownCount > 0
}

/**
 * A book's values for [kind].
 *
 * A **list**, because a book can have several narrators (a full-cast recording) and must appear
 * under each — joining them would put "Kate Reading, Michael Kramer" in the list as if it were one
 * person. Author and series are single-valued, but go through the same shape so the grouping below
 * has one path.
 */
internal fun Audiobook.facetValues(kind: FacetKind): List<String> =
  when (kind) {
    FacetKind.Author -> listOf(author)
    // Stored comma-separated for display; split back out here so each narrator is its own row.
    FacetKind.Narrator -> narrator.split(',')
    FacetKind.Series -> listOf(series)
  }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Groups [books] by [kind], most books first and then alphabetically.
 *
 * Count-descending because a facet list is a navigation aid: the narrator of thirty books is more
 * useful at the top than one who read a single title. Alphabetical within a count so the order is
 * stable — a list that reshuffles between visits cannot be learned.
 *
 * A book contributing no value for [kind] is counted in [FacetList.unknownCount] rather than
 * dropped, which is the whole point of that field.
 */
fun List<Audiobook>.facetsBy(kind: FacetKind): FacetList {
  val counts = linkedMapOf<String, Int>()
  var unknown = 0
  for (book in this) {
    val values = book.facetValues(kind)
    if (values.isEmpty()) {
      unknown++
      continue
    }
    for (value in values) {
      counts[value] = (counts[value] ?: 0) + 1
    }
  }
  val facets =
    counts.entries
      .map { Facet(value = it.key, bookCount = it.value) }
      .sortedWith(compareByDescending<Facet> { it.bookCount }.thenBy { it.value.lowercase() })
  return FacetList(kind = kind, facets = facets, unknownCount = unknown)
}

/** The books carrying [value] for [kind]. */
fun List<Audiobook>.booksInFacet(
  kind: FacetKind,
  value: String,
): List<Audiobook> = filter { book -> book.facetValues(kind).any { it.equals(value, ignoreCase = true) } }

/**
 * A series' books in reading order.
 *
 * [Audiobook.seriesIndex] first where it is known, then the numeric-aware title sort that already
 * exists for this — `BookSortComparators.titleSortKey` shelves "Book 2" before "Book 10", which a
 * plain string sort does not. Books with no index sort *after* those with one rather than before:
 * an unnumbered extra belongs at the end of a series, not in front of book one.
 */
fun List<Audiobook>.inSeriesOrder(): List<Audiobook> =
  sortedWith(
    compareBy<Audiobook> { if (it.seriesIndex == 0) Int.MAX_VALUE else it.seriesIndex }
      .thenBy(
        Comparator { left, right -> BookSortComparators.compareTitlesNaturally(left, right) },
      ) { it.titleSort.ifEmpty { it.title } },
  )
