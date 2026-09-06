package io.github.mattpvaughn.chronicle.features.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.FacetList
import io.github.mattpvaughn.chronicle.data.model.facetsBy
import io.github.mattpvaughn.chronicle.features.browse.compose.BrowseContent
import io.github.mattpvaughn.chronicle.features.browse.compose.BrowseUiState
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.combineDistinct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Browsing the library by author, narrator or series (cu-24).
 *
 * The grouping itself lives in `BookFacets` as pure functions; this only chooses which facet is
 * showing and hands the result to the view.
 */
class BrowseViewModel(
  bookRepository: IBookRepository,
) : ViewModel() {
  class Factory
    @Inject
    constructor(
      private val bookRepository: IBookRepository,
    ) : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass.isAssignableFrom(BrowseViewModel::class.java)) {
          "Cannot create ${modelClass.name} from BrowseViewModel.Factory"
        }
        return BrowseViewModel(bookRepository) as T
      }
    }

  private val _kind = MutableStateFlow(FacetKind.Author)
  val kind: StateFlow<FacetKind>
    get() = _kind

  /**
   * Whether a real grouping has happened yet.
   *
   * Tracked separately because `stateIn`'s seed is indistinguishable from its first computed value
   * when that value is also empty — a library with no books groups to exactly `FacetList.EMPTY`.
   * Only the *arrival* of a book list can tell the two apart, so this flips there.
   */
  private val _hasGrouped = MutableStateFlow(false)

  /**
   * The whole library, deduped at the source.
   *
   * Room re-emits the `Audiobook` table on every write — once a second during playback — and this
   * screen's grouping is O(library). Without the dedupe it would regroup 196 books per tick, which
   * is the shape cu-110 was about.
   */
  private val allBooks =
    bookRepository.getAllBooks().onEach {
      _hasGrouped.value = true
    }.distinctUntilChangedBy { books ->
      // The facet-relevant projection only: a progress change must not trigger a regroup.
      books.map { "${'$'}{it.id}|${'$'}{it.author}|${'$'}{it.narrator}|${'$'}{it.series}|${'$'}{it.seriesIndex}" }
    }

  /**
   * `stateIn` rather than a bare `Flow`, so the screen has a value to draw on first collect and the
   * O(library) grouping is shared between collectors instead of run per observer.
   *
   * `WhileSubscribed(5_000)` keeps it warm across a configuration change — a rotation would
   * otherwise re-group the whole library — while still dropping the Room subscription when the
   * screen goes away.
   */
  val facets: StateFlow<FacetList> =
    combineDistinct(allBooks, _kind) { books, selected ->
      books.facetsBy(selected)
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = FacetList.EMPTY,
    )

  /**
   * The screen's whole state, with "not grouped yet" distinguishable from "nothing to group".
   *
   * [facets] is seeded with `FacetList.EMPTY`, which reads as a facet with no values — so a screen
   * driven straight off it announces "No narrators yet" before the first grouping has run. The
   * sealed type makes that impossible to express: the seed is [BrowseContent.Loading] and only a
   * real grouping can produce [BrowseContent.Empty].
   *
   * Note the *selected* tab comes from [_kind] rather than from `FacetList.kind`, so the tab
   * highlights immediately on tap instead of waiting for the regroup — which is O(library) and,
   * on the owner's 196 books, visible.
   */
  val uiState: StateFlow<BrowseUiState> =
    combineDistinct(facets, _kind, _hasGrouped) { facets, selected, hasGrouped ->
      BrowseUiState(
        selected = selected,
        content =
          when {
            !hasGrouped -> BrowseContent.Loading
            facets.facets.isEmpty() -> BrowseContent.Empty(facets.kind)
            else -> BrowseContent.Loaded(facets)
          },
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = BrowseUiState(),
    )

  fun showFacet(kind: FacetKind) {
    // MutableStateFlow already conflates an identical value, so the guard the LiveData version
    // needed is redundant — but assignment is still synchronous, which is the cu-52 point.
    _kind.value = kind
  }
}
