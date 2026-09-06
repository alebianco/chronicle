package io.github.mattpvaughn.chronicle.features.login

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.LoadingStatus
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType.Companion.ARTIST
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asLibrary
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.combineDistinct
import io.github.mattpvaughn.chronicle.util.setEvent
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserItemListener
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import javax.inject.Inject

@HiltViewModel
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
    private val cachedFileManager: ICachedFileManager,
  ) : ViewModel() {
    private val _userMessage = MutableStateFlow<Event<String>?>(null)
    val userMessage: StateFlow<Event<String>?>
      get() = _userMessage

    private val _bottomChooserState = MutableStateFlow(EMPTY_BOTTOM_CHOOSER)
    val bottomChooserState: StateFlow<BottomChooserState>
      get() = _bottomChooserState

    /** Dismissal from the sheet itself, e.g. a tap outside it. */
    fun setBottomSheetVisibility(shouldShow: Boolean) {
      _bottomChooserState.value = _bottomChooserState.value.copy(shouldShow = shouldShow)
    }

    private val _libraries = MutableStateFlow<List<PlexLibrary>>(emptyList())
    val libraries: StateFlow<List<PlexLibrary>>
      get() = _libraries

    /**
     * LoadingStatus represents the status of the "connected to server" state as well as the
     * "fetched libraries" state
     */
    private val _loadingStatus = MutableStateFlow(LoadingStatus.LOADING)
    val loadingStatus: StateFlow<LoadingStatus> =
      combineDistinct(plexConfig.connectionState, _loadingStatus) { serverConn, loadingConn ->
        when (serverConn) {
          PlexConfig.ConnectionState.CONNECTING -> LoadingStatus.LOADING
          PlexConfig.ConnectionState.NOT_CONNECTED -> LoadingStatus.LOADING
          PlexConfig.ConnectionState.CONNECTED -> loadingConn
          PlexConfig.ConnectionState.CONNECTION_FAILED -> LoadingStatus.ERROR
        }
      }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        LoadingStatus.LOADING,
      )

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

    private val _emptyReason = MutableStateFlow(EmptyReason.NO_LIBRARIES)
    val emptyReason: StateFlow<EmptyReason> = _emptyReason

    /** Held so [refresh] can restart it; see there for why restarting is the point. */
    private var networkJob: Job? = null

    private fun observeConnection() {
      networkJob?.cancel()
      networkJob =
        viewModelScope.launch {
          plexConfig.isConnected.collect { isConnected ->
            if (isConnected) {
              Timber.i("Connected to server at ${plexConfig.url}, fetching libraries")
              loadLibraries()
            }
          }
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
      observeConnection()
      viewModelScope.launch {
        plexConfig.connectionState.collect { state ->
          if (state == PlexConfig.ConnectionState.CONNECTION_FAILED) {
            // Distinct from a failed *request*: nothing was reachable to ask.
            _emptyReason.value = EmptyReason.CANNOT_CONNECT
          }
        }
      }
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
          Timber.i("Libraries: ${tempLibraries.map { it.name }}")
          _libraries.value = tempLibraries
          // An empty list here is the one case where "no libraries found" is *true*: the server
          // answered and has none of the right type.
          _emptyReason.value = EmptyReason.NO_LIBRARIES
          _loadingStatus.value = if (tempLibraries.isEmpty()) LoadingStatus.ERROR else LoadingStatus.DONE
        } catch (e: Throwable) {
          Timber.e(e, "Error loading libraries")
          _userMessage.setEvent("Unable to load libraries: ${e.message}")
          _emptyReason.value = EmptyReason.REQUEST_FAILED
          _loadingStatus.value = LoadingStatus.ERROR
        }
      }
    }

    /**
     * Re-runs the connected handler against the current connection state.
     *
     * The restart *is* the mechanism, as it was when this removed and re-added an `observeForever`:
     * a `StateFlow` replays its current value to each new collector, so cancelling and relaunching
     * re-delivers `isConnected` and re-fetches the libraries. Collecting a second time without
     * cancelling would leave two collectors and fetch twice.
     */
    fun refresh() {
      observeConnection()
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
     * Downloaded *files* are now asked about rather than silently reclaimed (cu-130), reusing
     * Settings' wording so the same decision reads identically wherever it is met.
     *
     * **`replacedDifferentLibrary` is the only gate, and it already excludes both cases that must
     * not prompt**: a first-ever choice (`previous == null`, so no download can exist yet) and a
     * failed re-authentication (the library is unchanged, so the ids match). A second "was this a
     * re-auth?" signal would be one more thing to keep in agreement with this one — see cu-130.
     */
    fun chooseLibrary(library: PlexLibrary) {
      val replacedDifferentLibrary = plexLoginRepo.chooseLibrary(library)
      if (!replacedDifferentLibrary) {
        return
      }
      Timber.i("Library changed; clearing the previous library's cached catalogue")
      viewModelScope.launch {
        clearCatalogue()
        // Nothing downloaded means nothing to ask about — the same short-circuit Settings uses.
        if (!cachedFileManager.hasUserCachedTracks()) {
          return@launch
        }
        promptAboutDownloads()
      }
    }

    private suspend fun clearCatalogue() {
      bookRepository.clear()
      trackRepository.clear()
      collectionsRepository.clear()
    }

    /**
     * Asks whether to keep the previous library's downloads.
     *
     * The catalogue is already cleared by this point, so the files are orphans either way: keeping
     * them means they stay on disk until the user removes them, and `CachedFileManager`'s orphan
     * pass would otherwise have deleted them silently at some later launch. That silence is what
     * cu-130 exists to remove.
     */
    private fun promptAboutDownloads() {
      _bottomChooserState.value =
        BottomChooserState(
          title = FormattableString.from(R.string.prompt_clear_downloads_allow_retain),
          options = listOf(FormattableString.yes, FormattableString.no),
          listener =
            object : BottomChooserItemListener() {
              override fun onItemClicked(formattableString: FormattableString) {
                check(formattableString is FormattableString.ResourceString)
                // "Yes, keep them" is the do-nothing branch.
                if (formattableString.stringRes != R.string.yes) {
                  viewModelScope.launch { cachedFileManager.uncacheAllInLibrary() }
                }
                _bottomChooserState.value = EMPTY_BOTTOM_CHOOSER
              }
            },
          shouldShow = true,
        )
    }
  }
