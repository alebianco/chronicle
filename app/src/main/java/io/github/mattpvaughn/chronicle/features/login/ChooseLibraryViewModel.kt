package io.github.mattpvaughn.chronicle.features.login

import androidx.lifecycle.*
import androidx.lifecycle.Observer
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType.Companion.ARTIST
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asLibrary
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.postEvent
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import javax.inject.Inject

class ChooseLibraryViewModel
  @Inject
  constructor(
    private val plexMediaService: PlexMediaService,
    private val plexConfig: PlexConfig,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val plexLoginRepo: IPlexLoginRepo,
    private val bookRepository: IBookRepository,
    private val trackRepository: ITrackRepository,
    private val collectionsRepository: CollectionsRepository,
  ) : ViewModel() {
    class Factory
      @Inject
      constructor(
        private val plexMediaService: PlexMediaService,
        private val plexConfig: PlexConfig,
        private val plexPrefsRepo: PlexPrefsRepo,
        private val plexLoginRepo: IPlexLoginRepo,
        private val bookRepository: IBookRepository,
        private val trackRepository: ITrackRepository,
        private val collectionsRepository: CollectionsRepository,
      ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(ChooseLibraryViewModel::class.java)) {
            return ChooseLibraryViewModel(
              plexMediaService,
              plexConfig,
              plexPrefsRepo,
              plexLoginRepo,
              bookRepository,
              trackRepository,
              collectionsRepository,
            ) as T
          }
          throw IllegalArgumentException("Unknown ViewHolder class")
        }
      }

    private val _userMessage = MutableLiveData<Event<String>>()
    val userMessage: LiveData<Event<String>>
      get() = _userMessage

    private var _libraries = MutableLiveData<List<PlexLibrary>>(emptyList())
    val libraries: LiveData<List<PlexLibrary>>
      get() = _libraries

    /**
     * LoadingStatus represents the status of the "connected to server" state as well as the
     * "fetched libraries" state
     */
    private var _loadingStatus = MutableLiveData(LoadingStatus.LOADING)
    val loadingStatus: LiveData<LoadingStatus> =
      DoubleLiveData(plexConfig.connectionState, _loadingStatus) { serverConn, loadingConn ->
        when (serverConn) {
          PlexConfig.ConnectionState.CONNECTING -> LoadingStatus.LOADING
          PlexConfig.ConnectionState.NOT_CONNECTED -> LoadingStatus.LOADING
          PlexConfig.ConnectionState.CONNECTED -> loadingConn
          PlexConfig.ConnectionState.CONNECTION_FAILED -> LoadingStatus.ERROR
          null -> throw IllegalStateException("Cannot have a null server connection!")
        }
      }

    /**
     * Why the picker is empty, so the screen can say something true.
     *
     * All three causes used to render as the layout's static "No libraries found", which is a
     * statement about the *server's contents* and was wrong in two of them. The owner hit the
     * worst case: a TLS hostname mismatch after a certificate rotation, reported as though the
     * server had no audiobook libraries (cu-125).
     *
     * That reads as plausible rather than broken, because account and server selection both
     * succeed first — they are answered by plex.tv, while libraries come from the server itself.
     * So the user has no reason to suspect a connection problem.
     */
    enum class EmptyReason {
      /** The server answered, and genuinely has no audiobook libraries. */
      NO_LIBRARIES,

      /** No connection could be established — including a certificate mismatch. */
      CANNOT_CONNECT,

      /** Connected, but the library request itself failed. */
      REQUEST_FAILED,
    }

    private val _emptyReason = MutableLiveData(EmptyReason.NO_LIBRARIES)
    val emptyReason: LiveData<EmptyReason> = _emptyReason

    private val networkObserver =
      Observer<Boolean> { isConnected ->
        if (isConnected) {
          Timber.i("Connected to server at ${plexConfig.url}, fetching libraries")
          loadLibraries()
        }
      }

    private val connectionStateObserver =
      Observer<PlexConfig.ConnectionState> { state ->
        if (state == PlexConfig.ConnectionState.CONNECTION_FAILED) {
          // Distinct from a failed *request*: nothing was reachable to ask.
          _emptyReason.value = EmptyReason.CANNOT_CONNECT
        }
      }

    init {
      viewModelScope.launch {
        // chooseViableConnections must be called here because it won't be called in
        // ChronicleApplication if we have just logged in
        try {
          plexConfig.connectToServer(plexMediaService)
        } catch (t: Throwable) {
          Timber.i("Failed to return result!")
        }
      }
      plexConfig.isConnected.observeForever(networkObserver)
      plexConfig.connectionState.observeForever(connectionStateObserver)
    }

    override fun onCleared() {
      plexConfig.isConnected.removeObserver(networkObserver)
      plexConfig.connectionState.removeObserver(connectionStateObserver)
      super.onCleared()
    }

    private fun loadLibraries() {
      viewModelScope.launch {
        try {
          _loadingStatus.value = LoadingStatus.LOADING
          val libraryContainer = plexMediaService.retrieveLibraries()
          val tempLibraries =
            libraryContainer.plexMediaContainer.plexDirectories
              .filter { it.type == ARTIST.typeString }
              .map { it.asLibrary() }
          Timber.i("Libraries: $tempLibraries")
          _libraries.postValue(tempLibraries)
          // An empty list here is the one case where "no libraries found" is *true*: the server
          // answered and has none of the right type.
          _emptyReason.value = EmptyReason.NO_LIBRARIES
          _loadingStatus.value = if (tempLibraries.isEmpty()) LoadingStatus.ERROR else LoadingStatus.DONE
        } catch (e: Throwable) {
          Timber.e(e, "Error loading libraries")
          _userMessage.postEvent("Unable to load libraries: ${e.message}")
          _emptyReason.value = EmptyReason.REQUEST_FAILED
          _loadingStatus.value = LoadingStatus.ERROR
        }
      }
    }

    fun refresh() {
      plexConfig.isConnected.removeObserver(networkObserver)
      plexConfig.isConnected.observeForever(networkObserver)
    }

    /**
     * Records the chosen library and, when it **replaces a different one**, drops the cached
     * catalogue that belonged to the old library.
     *
     * Settings' "Current library" already did this; the login picker did not, so choosing a
     * different library here left Room holding the previous library's books and tracks. Until the
     * next refresh pruned them the app showed a **union of two libraries**, and a download
     * belonging to a book no longer in the catalogue was reclaimed later with no warning (cu-126).
     *
     * Downloaded *files* are deliberately left alone here — see the task notes. Deleting a
     * multi-gigabyte download without asking is worse than leaving it to be reclaimed, and the
     * prompt that Settings shows needs UI this screen does not have.
     */
    fun chooseLibrary(library: PlexLibrary) {
      val replacedDifferentLibrary = plexLoginRepo.chooseLibrary(library)
      if (!replacedDifferentLibrary) {
        return
      }
      Timber.i("Library changed; clearing the previous library's cached catalogue")
      viewModelScope.launch {
        bookRepository.clear()
        trackRepository.clear()
        collectionsRepository.clear()
      }
    }
  }
