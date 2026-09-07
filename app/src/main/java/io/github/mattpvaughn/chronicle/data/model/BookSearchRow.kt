package io.github.mattpvaughn.chronicle.data.model

/**
 * The columns a search matches on, and nothing else.
 *
 * `groupedSearch` reads exactly title, author, narrator and series, plus the id to identify the
 * book afterwards. Reading whole `Audiobook` rows to do that materialises twenty columns the
 * matching never touches — a profiling pass measured that read as most of a search's cost at 10,000 books.
 *
 * Not an `@Entity`: it is a projection over `Audiobook`, so it has no table, no migration and no
 * exported schema. Room maps it by column name from the `@Query` that returns it.
 */
data class BookSearchRow(
  val id: String,
  val title: String,
  val author: String,
  val narrator: String,
  val series: String,
) {
  /**
   * The projection as an `Audiobook`, carrying **only** the matched fields.
   *
   * Used to decide *which* books match, never to render one: the real rows are fetched by id
   * afterwards. Reusing `Audiobook` here rather than making the matching generic keeps the
   * rules — the fuzzy tier, the 4-character floor, the character-count prefilter — running over
   * exactly one implementation, and keeps `GroupedSearchResults` free of a type parameter that
   * would leak into every UI call site for no benefit.
   */
  fun asMatchCandidate(): Audiobook =
    Audiobook(
      id = id,
      source = SourceId.UNKNOWN,
      title = title,
      author = author,
      narrator = narrator,
      series = series,
    )
}
