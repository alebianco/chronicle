package io.github.mattpvaughn.chronicle.features.collections

import androidx.lifecycle.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.data.local.*
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.util.stringFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = CollectionDetailsViewModel.Factory::class)
class CollectionDetailsViewModel
  @AssistedInject
  constructor(
    private val bookRepo: BookRepository,
    private val collectionRepo: CollectionsRepository,
    prefsRepo: PrefsRepo,
    settings: SettingsDataStore,
    /**
     * Which collection, handed over directly by the Circuit screen key.
     *
     * Two earlier shapes both lost this value. The Fragment-era factory held it as a nullable `var`
     * set before `create` and dereferenced with `!!`, which did not survive process death — the
     * system rebuilds the ViewModel without replaying the write, so the `!!` threw on a restored
     * screen. Navigation Compose fixed that by routing it through `SavedStateHandle`, at the cost
     * of encoding it into a URL path segment.
     *
     * Circuit removes the round trip: `CollectionDetailsScreenKey` *is* the argument, and the
     * screen key is saved and restored with the back stack, so this survives process death without
     * being a string in a route.
     */
    @Assisted private val collectionId: String,
  ) : ViewModel() {
    private suspend fun getBooksInCollection(): List<Audiobook> {
      val childIds = collectionRepo.getChildIds(collectionId)
      return childIds.mapNotNull {
        bookRepo.getAudiobookAsync(it)
      }
    }

    private val _booksInCollection = MutableStateFlow<List<Audiobook>>(emptyList())
    val booksInCollection: StateFlow<List<Audiobook>>
      get() = _booksInCollection

    val title = collectionRepo.getCollection(collectionId)

    init {
      viewModelScope.launch {
        _booksInCollection.value = getBooksInCollection()
      }
    }

    val viewStyle =
      settings.stringFlow(
        PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
        prefsRepo.libraryBookViewStyle,
      )

    /** How Circuit's presenter factory builds this, passing the id from the screen key. */
    @AssistedFactory
    interface Factory {
      fun create(collectionId: String): CollectionDetailsViewModel
    }
  }
