package io.github.mattpvaughn.chronicle.features.browse

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.map
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.FacetList
import io.github.mattpvaughn.chronicle.data.model.facetsBy
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import io.github.mattpvaughn.chronicle.util.distinctBy
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

  private val _kind = MutableLiveData(FacetKind.Author)
  val kind: LiveData<FacetKind>
    get() = _kind

  /**
   * The whole library, deduped at the source.
   *
   * Room re-emits the `Audiobook` table on every write — once a second during playback — and this
   * screen's grouping is O(library). Without the dedupe it would regroup 196 books per tick, which
   * is the shape cu-110 was about.
   */
  private val allBooks: LiveData<List<Audiobook>> =
    bookRepository.getAllBooks().map { books ->
      books.orEmpty()
    }.distinctBy { books ->
      // The facet-relevant projection only: a progress change must not trigger a regroup.
      books.map { "${it.id}|${it.author}|${it.narrator}|${it.series}|${it.seriesIndex}" }
    }

  val facets: LiveData<FacetList> =
    DoubleLiveData(allBooks, _kind) { books, selected ->
      books.orEmpty().facetsBy(selected ?: FacetKind.Author)
    }

  fun showFacet(kind: FacetKind) {
    if (_kind.value != kind) {
      _kind.value = kind
    }
  }
}
