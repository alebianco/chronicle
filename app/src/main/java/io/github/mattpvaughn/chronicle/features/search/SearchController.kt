package io.github.mattpvaughn.chronicle.features.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.GroupedSearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
  private val _results = MutableLiveData(GroupedSearchResults(emptyList()))
  val results: LiveData<GroupedSearchResults> get() = _results

  private val _rows = MutableLiveData<List<SearchRow>>(emptyList())
  val rows: LiveData<List<SearchRow>> get() = _rows

  private val _isQueryEmpty = MutableLiveData(true)
  val isQueryEmpty: LiveData<Boolean> get() = _isQueryEmpty

  private val _isSearchActive = MutableLiveData(false)
  val isSearchActive: LiveData<Boolean> get() = _isSearchActive

  /**
   * The in-flight search, cancelled by the next keystroke.
   *
   * Cancelling matters for correctness as well as cost: two searches racing can deliver their
   * results out of order, leaving the list showing an answer to a query the user has moved past.
   */
  private var pending: Job? = null

  fun setSearchActive(active: Boolean) {
    _isSearchActive.postValue(active)
    if (!active) clear()
  }

  /** Runs a search for [query] after the debounce interval, superseding any pending one. */
  fun search(query: String) {
    pending?.cancel()
    val trimmed = query.trim()
    _isQueryEmpty.postValue(trimmed.isEmpty())
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
    _isQueryEmpty.postValue(true)
    publish(GroupedSearchResults(emptyList()))
  }

  private fun publish(grouped: GroupedSearchResults) {
    _results.postValue(grouped)
    _rows.postValue(grouped.toRows())
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
