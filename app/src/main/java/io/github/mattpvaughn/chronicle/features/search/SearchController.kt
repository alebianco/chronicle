package io.github.mattpvaughn.chronicle.features.search

import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.GroupedSearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The search half of a screen's ViewModel, in one place (cu-25).
 *
 * Extracted rather than repeated because three screens (library, home, collections) each carried
 * their own copy of the same four fields and the same `search(query)` — so a fix to any of them
 * reached one screen out of three. The debounce in particular has to live somewhere single: it is
 * the difference between one query per *pause* and one per keystroke.
 */
class SearchController(
  private val bookRepository: IBookRepository,
  private val scope: CoroutineScope,
  private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
  private val _results = MutableStateFlow(GroupedSearchResults(emptyList()))
  val results: StateFlow<GroupedSearchResults> get() = _results

  private val _rows = MutableStateFlow<List<SearchRow>>(emptyList())
  val rows: StateFlow<List<SearchRow>> get() = _rows

  private val _isQueryEmpty = MutableStateFlow(true)
  val isQueryEmpty: StateFlow<Boolean> get() = _isQueryEmpty

  private val _isSearchActive = MutableStateFlow(false)
  val isSearchActive: StateFlow<Boolean> get() = _isSearchActive

  /**
   * The in-flight search, cancelled by the next keystroke.
   *
   * Cancelling matters for correctness as well as cost: two searches racing can deliver their
   * results out of order, leaving the list showing an answer to a query the user has moved past.
   */
  private var pending: Job? = null

  fun setSearchActive(active: Boolean) {
    _isSearchActive.value = active
    if (!active) clear()
  }

  /** Runs a search for [query] after the debounce interval, superseding any pending one. */
  fun search(query: String) {
    pending?.cancel()
    val trimmed = query.trim()
    _isQueryEmpty.value = trimmed.isEmpty()
    if (trimmed.isEmpty()) {
      publish(GroupedSearchResults(emptyList()))
      return
    }
    pending =
      scope.launch {
        delay(debounceMillis)
        try {
          publish(bookRepository.searchGrouped(trimmed))
        } catch (e: Exception) {
          // Never leave the previous query's results on screen as if they answered this one.
          Timber.e(e, "Search failed for a ${trimmed.length}-character query")
          publish(GroupedSearchResults(emptyList()))
        }
      }
  }

  private fun clear() {
    pending?.cancel()
    _isQueryEmpty.value = true
    publish(GroupedSearchResults(emptyList()))
  }

  /**
   * Publishes the results and the rows derived from them.
   *
   * Both were `postValue` before cu-52, which is asynchronous *and coalescing*: the two are one
   * fact in two fields, and nothing stopped a collector observing the new results beside the
   * previous rows for a frame. `value =` is synchronous, so they land together.
   */
  private fun publish(grouped: GroupedSearchResults) {
    _results.value = grouped
    _rows.value = grouped.toRows()
  }

  companion object {
    /**
     * How long typing must pause before a search runs.
     *
     * 250 ms is the usual type-ahead figure: long enough that a word typed at speed costs one
     * query rather than one per letter, short enough to feel immediate. Before this, every screen
     * ran a database read on every keystroke.
     */
    const val DEFAULT_DEBOUNCE_MILLIS = 250L
  }
}
