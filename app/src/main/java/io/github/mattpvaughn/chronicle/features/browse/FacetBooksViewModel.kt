package io.github.mattpvaughn.chronicle.features.browse

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.booksInFacet
import io.github.mattpvaughn.chronicle.data.model.inSeriesOrder
import io.github.mattpvaughn.chronicle.util.stringFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The books under one facet value (cu-24).
 *
 * Filtering lives in `BookFacets` as pure functions; this is the LiveData plumbing plus the one
 * decision that belongs here: a **series** is shown in reading order, while an author's or
 * narrator's books keep the library's own ordering, because "book 2 then book 10" only means
 * something within a series.
 */
@HiltViewModel
class FacetBooksViewModel
  @Inject
  constructor(
    bookRepository: IBookRepository,
    sharedPreferences: SharedPreferences,
    savedStateHandle: SavedStateHandle,
  ) : ViewModel() {
    /**
     * Which facet, read from the navigation arguments rather than a factory field (cu-185).
     *
     * The factory carried `kind` and `value` as **mutable properties** the Fragment set before
     * calling `create`, so they did not survive process death: the system recreates the ViewModel
     * without replaying those writes, and the screen came back showing the default facet. A
     * `SavedStateHandle` read is restored with the rest of the saved state.
     *
     * By name, not ordinal: an ordinal in a Bundle would silently mean a different facet if the
     * enum ever gained a member — which is why the argument was always written as a name.
     */
    private val kind: FacetKind =
      FacetKind.entries.firstOrNull { it.name == savedStateHandle.get<String>(ARG_KIND) }
        ?: FacetKind.Author

    private val value: String = savedStateHandle.get<String>(ARG_VALUE).orEmpty()

    val viewStyle =
      sharedPreferences.stringFlow(
        PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
        PrefsRepo.VIEW_STYLE_COVER_GRID,
      )

    val books: Flow<List<Audiobook>> =
      bookRepository.getAllBooks()
        // Deduped on the facet-relevant projection: Room re-emits this table once a second during
        // playback, and re-filtering the whole library per tick is the shape cu-110 was about.
        .distinctUntilChangedBy { books ->
          books.map { "${'$'}{it.id}|${'$'}{it.author}|${'$'}{it.narrator}|${'$'}{it.series}|${'$'}{it.seriesIndex}" }
        }
        .map { all ->
          val matching = all.booksInFacet(kind, value)
          if (kind == FacetKind.Series) matching.inSeriesOrder() else matching
        }

    companion object {
      /** Navigation argument keys, owned here because this is what reads them (cu-185). */
      const val ARG_KIND = "facet_kind"
      const val ARG_VALUE = "facet_value"
    }
  }
