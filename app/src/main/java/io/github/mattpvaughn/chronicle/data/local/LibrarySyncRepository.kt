package io.github.mattpvaughn.chronicle.data.local

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.BookTrackData
import io.github.mattpvaughn.chronicle.data.model.getProgress
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibrarySyncRepository
  @Inject
  constructor(
    private val bookRepository: BookRepository,
    private val trackRepository: TrackRepository,
    private val collectionsRepository: CollectionsRepository,
    private val dispatchers: DispatcherProvider,
  ) {
    private var repoJob = Job()
    private val repoScope = CoroutineScope(repoJob + dispatchers.io)

    private var _isRefreshing = MutableLiveData<Boolean>()
    val isRefreshing: LiveData<Boolean>
      get() = _isRefreshing

    /**
     * A refresh failure, for the UI to surface.
     *
     * This used to be a `Toast` raised from inside the `catch` — on [DispatcherProvider.io],
     * where `Toast.show()` throws `Can't create handler inside thread ... that has not called
     * Looper.prepare()`. So the handler for a sync failure was itself a crash, on exactly the
     * trigger that also made `refreshDataPaginated` prune the library. Emitting an event instead
     * keeps the decision about *how* to tell the user in the UI layer, where a Looper exists.
     */
    private val _errorMessage = MutableLiveData<Event<Int>>()
    val errorMessage: LiveData<Event<Int>>
      get() = _errorMessage

    fun refreshLibrary() {
      repoScope.launch {
        val refreshed =
          try {
            _isRefreshing.postValue(true)
            bookRepository.refreshDataPaginated()
            trackRepository.refreshDataPaginated()
            true
          } catch (e: Throwable) {
            Timber.e(e, "Failed to refresh the library")
            _errorMessage.postValue(Event(R.string.failed_to_refresh_library))
            false
          } finally {
            _isRefreshing.postValue(false)
          }

        // Only the re-derive is skipped on failure: nothing was fetched, so nothing changed, and
        // the whole-library pass below would be pure cost. Collections still refreshes — it is an
        // independent fetch that handles its own failure, and skipping it would leave it stale for
        // no reason if the network recovered in between.
        if (refreshed) {
          val audiobooks = bookRepository.getAllBooksAsync()
          val tracks = trackRepository.getAllTracksAsync()

          // Grouped once rather than filtered per book: this used to be
          // `tracks.filter { it.parentKey == book.id }` inside the loop, i.e. O(books × tracks) —
          // about 20M comparisons for a 2000-book library — plus one write per book.
          val tracksByBook = tracks.groupBy { it.parentKey }
          bookRepository.updateTrackData(
            audiobooks.map { book ->
              val tracksInAudiobook = tracksByBook[book.id].orEmpty()
              BookTrackData(
                bookId = book.id,
                bookProgress = tracksInAudiobook.getProgress(),
                bookDuration = tracksInAudiobook.getDuration(),
                trackCount = tracksInAudiobook.size,
              )
            },
          )
        }

        collectionsRepository.refreshCollectionsPaginated()
      }
    }
  }
