package io.github.mattpvaughn.chronicle.features.browse

import androidx.lifecycle.ViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.local.SettingsDataStore
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.FacetKind
import io.github.mattpvaughn.chronicle.data.model.booksInFacet
import io.github.mattpvaughn.chronicle.data.model.inSeriesOrder
import io.github.mattpvaughn.chronicle.util.stringFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map

/**
 * The books under one facet value.
 *
 * Filtering lives in `BookFacets` as pure functions; this is the LiveData plumbing plus the one
 * decision that belongs here: a **series** is shown in reading order, while an author's or
 * narrator's books keep the library's own ordering, because "book 2 then book 10" only means
 * something within a series.
 */
@HiltViewModel(assistedFactory = FacetBooksViewModel.Factory::class)
class FacetBooksViewModel
  @AssistedInject
  constructor(
    bookRepository: IBookRepository,
    settings: SettingsDataStore,
    /**
     * Which facet, handed over directly by the Circuit screen key.
     *
     * The Fragment-era factory carried `kind` and `value` as **mutable properties** the Fragment
     * set before calling `create`, so they did not survive process death: the system recreates the
     * ViewModel without replaying those writes, and the screen came back showing the default facet.
     * Navigation Compose fixed that by routing both through `SavedStateHandle`.
     *
     * That fix carried a cost this removes. A `Bundle` holds no enums, so `kind` travelled as a
     * **string name** and was matched back with a `?: FacetKind.Author` fallback — meaning a typo
     * or a renamed member silently showed the wrong facet rather than failing. Here it is the enum
     * itself, so there is nothing to parse and no fallback to be wrong.
     */
    @Assisted private val kind: FacetKind,
    @Assisted private val value: String,
  ) : ViewModel() {
    val viewStyle =
      settings.stringFlow(
        PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
        PrefsRepo.VIEW_STYLE_COVER_GRID,
      )

    val books: Flow<List<Audiobook>> =
      bookRepository.getAllBooks()
        // Deduped on the facet-relevant projection: Room re-emits this table once a second during
        // playback, and re-filtering the whole library per tick was the shape of that cost.
        .distinctUntilChangedBy { books ->
          books.map { "${'$'}{it.id}|${'$'}{it.author}|${'$'}{it.narrator}|${'$'}{it.series}|${'$'}{it.seriesIndex}" }
        }
        .map { all ->
          val matching = all.booksInFacet(kind, value)
          if (kind == FacetKind.Series) matching.inSeriesOrder() else matching
        }

    /** How Circuit's presenter factory builds this, passing both values from the screen key. */
    @AssistedFactory
    interface Factory {
      fun create(
        kind: FacetKind,
        value: String,
      ): FacetBooksViewModel
    }
  }
