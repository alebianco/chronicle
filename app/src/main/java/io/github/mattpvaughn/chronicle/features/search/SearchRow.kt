package io.github.mattpvaughn.chronicle.features.search

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.GroupedSearchResults
import io.github.mattpvaughn.chronicle.data.model.SearchField

/**
 * A grouped search result flattened into `RecyclerView` rows (cu-25).
 *
 * Kept out of the adapter so the flattening — which is where the ordering and the counts are
 * decided — is testable without inflating anything.
 */
sealed interface SearchRow {
  /** A group heading, with the number of books beneath it. */
  data class Header(
    val field: SearchField,
    val bookCount: Int,
  ) : SearchRow

  /**
   * One matching book.
   *
   * [matchedValue] travels with the row so a result found by narrator can say *why* it is here —
   * a book listed under "Narrators" with only its title showing gives the user no way to tell
   * which of several narrators matched.
   */
  data class Book(
    val book: Audiobook,
    val field: SearchField,
    val matchedValue: String,
  ) : SearchRow
}

/**
 * Flattens [GroupedSearchResults] into headed rows.
 *
 * Every group contributes a header followed by its books, in the group order the search already
 * decided — so this adds no ordering policy of its own.
 */
fun GroupedSearchResults.toRows(): List<SearchRow> =
  groups.flatMap { group ->
    buildList {
      add(SearchRow.Header(field = group.field, bookCount = group.count))
      group.results.forEach { result ->
        add(
          SearchRow.Book(
            book = result.book,
            field = group.field,
            matchedValue = result.matchedValue,
          ),
        )
      }
    }
  }
