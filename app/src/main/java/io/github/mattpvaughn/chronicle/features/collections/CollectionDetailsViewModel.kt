package io.github.mattpvaughn.chronicle.features.collections

import android.content.SharedPreferences
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.data.local.*
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.util.stringFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionDetailsViewModel
  @Inject
  constructor(
    private val bookRepo: BookRepository,
    private val collectionRepo: CollectionsRepository,
    prefsRepo: PrefsRepo,
    sharedPreferences: SharedPreferences,
    savedStateHandle: SavedStateHandle,
  ) : ViewModel() {
    /**
     * Which collection, from the navigation arguments rather than a factory field.
     *
     * The factory held `collectionId` as a nullable `var` the Fragment set before `create` and then
     * dereferenced with `!!`. That did not survive process death — the system rebuilds the ViewModel
     * without replaying the write, so the `!!` threw on a restored screen.
     */
    private val collectionId: String = savedStateHandle.get<String>(ARG_COLLECTION_ID).orEmpty()

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
      sharedPreferences.stringFlow(
        PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
        prefsRepo.libraryBookViewStyle,
      )

    @Suppress("UNCHECKED_CAST")
    companion object {
      /** Navigation argument key, owned here because this is what reads it. */
      const val ARG_COLLECTION_ID = "collection_id"
    }
  }
