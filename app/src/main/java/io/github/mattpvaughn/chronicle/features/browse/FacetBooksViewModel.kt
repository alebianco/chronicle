package io.github.mattpvaughn.chronicle.features.browse

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.map
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.booksInFacet
import io.github.mattpvaughn.chronicle.data.model.inSeriesOrder
import io.github.mattpvaughn.chronicle.util.StringPreferenceLiveData
import io.github.mattpvaughn.chronicle.util.distinctBy
import javax.inject.Inject

/**
 * The books under one facet value (cu-24).
 *
 * Filtering lives in `BookFacets` as pure functions; this is the LiveData plumbing plus the one
 * decision that belongs here: a **series** is shown in reading order, while an author's or
 * narrator's books keep the library's own ordering, because "book 2 then book 10" only means
 * something within a series.
 */
class FacetBooksViewModel(
  bookRepository: IBookRepository,
  sharedPreferences: SharedPreferences,
  private val kind: FacetKind,
  private val value: String,
) : ViewModel() {
  class Factory
    @Inject
    constructor(
      private val bookRepository: IBookRepository,
      private val sharedPreferences: SharedPreferences,
    ) : ViewModelProvider.Factory {
      var kind: FacetKind = FacetKind.Author
      var value: String = ""

      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass.isAssignableFrom(FacetBooksViewModel::class.java)) {
          "Cannot create ${modelClass.name} from FacetBooksViewModel.Factory"
        }
        return FacetBooksViewModel(bookRepository, sharedPreferences, kind, value) as T
      }
    }

  val viewStyle =
    StringPreferenceLiveData(
      PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
      PrefsRepo.VIEW_STYLE_COVER_GRID,
      sharedPreferences,
    )

  val books: LiveData<List<Audiobook>> =
    bookRepository.getAllBooks()
      // Deduped on the facet-relevant projection: Room re-emits this table once a second during
      // playback, and re-filtering the whole library per tick is the shape cu-110 was about.
      .distinctBy { books ->
        books.orEmpty().map { "${it.id}|${it.author}|${it.narrator}|${it.series}|${it.seriesIndex}" }
      }
      .map { all ->
        val matching = all.orEmpty().booksInFacet(kind, value)
        if (kind == FacetKind.Series) matching.inSeriesOrder() else matching
      }
}
