package io.github.mattpvaughn.chronicle.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.LibraryParseSummary
import io.github.mattpvaughn.chronicle.data.model.PatternAttempt
import io.github.mattpvaughn.chronicle.data.model.PatternOrder
import io.github.mattpvaughn.chronicle.data.model.SeriesIndexDiagnostics
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State for the series-index rules tester (cu-151).
 *
 * The screen exists so a user can see what a rule *does* before trusting it. tvnamer has the config
 * file and not this, and its open issue #216 is a user who could not tell whether their pattern was
 * wrong or the tool was broken. Every decision worth testing already lives in
 * [SeriesIndexDiagnostics] and `SeriesIndexPatternSet.explain`; this is the plumbing plus two
 * choices that belong to the screen.
 *
 * Written in `LiveData` on its own branch and converted here, where cu-52's migration met it —
 * writing one screen in `StateFlow` while the rest of the tree was still `LiveData` would have been
 * the ad-hoc mixing convention 3 forbids.
 */
class SeriesIndexTesterViewModel(
  private val bookRepository: IBookRepository,
  private val dispatchers: DispatcherProvider,
  private val exceptionHandler: CoroutineExceptionHandler,
) : ViewModel() {
  class Factory
    @Inject
    constructor(
      private val bookRepository: IBookRepository,
      private val dispatchers: DispatcherProvider,
      private val exceptionHandler: CoroutineExceptionHandler,
    ) : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass.isAssignableFrom(SeriesIndexTesterViewModel::class.java)) {
          "Cannot create ${modelClass.name} from SeriesIndexTesterViewModel.Factory"
        }
        return SeriesIndexTesterViewModel(bookRepository, dispatchers, exceptionHandler) as T
      }
    }

  private val _titleSort = MutableStateFlow("")

  /** What the user typed, or the sample they tapped. */
  val titleSort: StateFlow<String>
    get() = _titleSort

  private val _attempts = MutableStateFlow<List<PatternAttempt>>(emptyList())

  /** Every rule's verdict against [titleSort], in the order the rules are actually tried. */
  val attempts: StateFlow<List<PatternAttempt>>
    get() = _attempts

  private val _summary = MutableStateFlow<LibraryParseSummary?>(null)

  /** How the library parses today. Null until the books have been read. */
  val summary: StateFlow<LibraryParseSummary?>
    get() = _summary

  private val _samples = MutableStateFlow<List<String>>(emptyList())

  /**
   * Real titles from the user's own library that currently parse to **no position**.
   *
   * The fourth acceptance criterion, and what makes the screen useful before any rule exists: it
   * answers "does my library even need a rule?" from the user's own data rather than from a typed
   * example they had to invent.
   */
  val samples: StateFlow<List<String>>
    get() = _samples

  /**
   * The rule that actually decided the position — the **first** that succeeded, not the only one.
   *
   * More than one rule routinely succeeds: `"Mistborn, Book 2 - …"` satisfies both `audnexus` and
   * `seanap`, and first-match-wins is the whole disambiguation mechanism (cu-146). A screen that
   * only marked "succeeded" would show two winners and leave the user unable to tell which reading
   * the app took — the same class of confusion the tester exists to remove.
   */
  val winningRule: StateFlow<PatternAttempt?> =
    _attempts
      .map { attempts -> attempts.firstOrNull { it.succeeded } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

  /**
   * The position the winning rule read, or null when nothing matched.
   *
   * Derived rather than stored, so it cannot disagree with [attempts].
   */
  val parsedPosition: StateFlow<String?> =
    winningRule
      .map { it?.capturedIndex }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

  /** Whether the user's own rules run before, after, or instead of the built-ins. */
  val ruleOrder: PatternOrder
    get() = Audiobook.seriesIndexPatterns.order

  /** How many of the active rules came from the user's file. */
  val userRuleCount: Int
    get() = Audiobook.seriesIndexPatterns.all.count { it.isUserDefined }

  init {
    loadLibrary()
  }

  /**
   * Reads the library once, off the main thread.
   *
   * `getAllBooksAsync` rather than the `LiveData` query: this is a one-shot read for a summary and
   * a sample list, and a live query would re-run both on every progress tick during playback for a
   * screen whose content cannot meaningfully change while it is open (the cu-110 shape).
   */
  private fun loadLibrary() {
    viewModelScope.launch(exceptionHandler) {
      val books = withContext(dispatchers.io) { bookRepository.getAllBooksAsync() }
      _summary.value = SeriesIndexDiagnostics.summarise(books)
      _samples.value = SeriesIndexDiagnostics.unparsedTitleSorts(books)
    }
  }

  /**
   * Re-runs every rule against [input].
   *
   * Synchronous on purpose: `explain` is a handful of regex matches over one short string, and
   * hopping threads for it would make the verdict list lag the keystroke that caused it.
   */
  fun onTitleSortChanged(input: String) {
    _titleSort.value = input
    _attempts.value =
      if (input.isBlank()) {
        emptyList()
      } else {
        Audiobook.seriesIndexPatterns.explain(input)
      }
  }

  /** Loads a library title into the input, so a sample can be inspected rather than retyped. */
  fun onSampleChosen(titleSort: String) {
    onTitleSortChanged(titleSort)
  }
}
