package io.github.mattpvaughn.chronicle.features.collections

import android.content.SharedPreferences
import androidx.lifecycle.*
import io.github.mattpvaughn.chronicle.data.local.*
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.util.stringFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class CollectionDetailsViewModel(
  private val collectionId: String,
  private val bookRepo: BookRepository,
  private val collectionRepo: CollectionsRepository,
  prefsRepo: PrefsRepo,
  sharedPreferences: SharedPreferences,
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
    sharedPreferences.stringFlow(
      PrefsRepo.KEY_LIBRARY_VIEW_STYLE,
      prefsRepo.libraryBookViewStyle,
    )

  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val bookRepo: BookRepository,
      private val prefsRepo: PrefsRepo,
      private val sharedPreferences: SharedPreferences,
      private val collectionRepo: CollectionsRepository,
    ) : ViewModelProvider.Factory {
      var collectionId: String? = null

      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CollectionDetailsViewModel::class.java)) {
          return CollectionDetailsViewModel(
            collectionId!!,
            bookRepo,
            collectionRepo,
            prefsRepo,
            sharedPreferences,
          ) as T
        } else {
          throw IllegalArgumentException("Incorrect class type provided")
        }
      }
    }
}
