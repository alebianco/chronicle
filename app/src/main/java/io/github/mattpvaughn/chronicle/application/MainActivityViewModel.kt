package io.github.mattpvaughn.chronicle.application

import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.application.MainActivityViewModel.BottomSheetState.*
import io.github.mattpvaughn.chronicle.data.local.CollectionsRepository
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.*
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_FULLY
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_NO_LIBRARY_CHOSEN
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_NO_SERVER_CHOSEN
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_NO_USER_CHOSEN
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.player.id
import io.github.mattpvaughn.chronicle.features.player.isPlaying
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.combineDistinct
import io.github.mattpvaughn.chronicle.util.setEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel
  @Inject
  constructor(
    loginRepo: IPlexLoginRepo,
    private val trackRepository: ITrackRepository,
    private val bookRepository: IBookRepository,
    private val mediaServiceConnection: MediaServiceConnection,
    collectionsRepository: CollectionsRepository,
    private val exceptionHandler: CoroutineExceptionHandler,
  ) : ViewModel() {
    /** The status of the bottom sheet which contains "currently playing" info */
    enum class BottomSheetState {
      COLLAPSED,
      HIDDEN,
      EXPANDED,
    }

    val isLoggedIn: StateFlow<Boolean> =
      loginRepo.loginEvent
        .map { it.peekContent() == LOGGED_IN_FULLY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /**
     * True while the user is part-way through onboarding — signed in, but without a user, server or
     * library chosen yet.
     *
     * The back handler needs this. Backing out of a picker used to fall through to "switch to the
     * Home tab", which showed a Home rendered from the *previous* session's Room data — so the app
     * looked fully configured while its own state said otherwise and the prefs held no library
     *. The emptier the cache, the more obviously broken it would have looked; with a full
     * one it was invisible.
     */
    val isOnboarding: StateFlow<Boolean> =
      loginRepo.loginEvent
        .map {
          when (it.peekContent()) {
            LOGGED_IN_NO_USER_CHOSEN, LOGGED_IN_NO_SERVER_CHOSEN, LOGGED_IN_NO_LIBRARY_CHOSEN -> true
            else -> false
          }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /**
     * The player sheet's state.
     *
     * Three things read this state back to decide what to do ([minimizeCurrentlyPlaying],
     * [maximizeCurrentlyPlaying], [onCurrentlyPlayingHandleDragged]) and so does the activity's back
     * handler. As a `MutableLiveData` written with `postValue` the write deferred to the next
     * main-loop pass, so the next reader saw the *previous* state — back then decided the sheet was
     * not expanded and fell through to leaving the app — and several posts in one loop
     * coalesced, losing a collapse-then-expand pair entirely. A `MutableStateFlow` assignment lands
     * immediately and cannot have either shape.
     */
    private val _currentlyPlayingLayoutState = MutableStateFlow(HIDDEN)
    val currentlyPlayingLayoutState: StateFlow<BottomSheetState>
      get() = _currentlyPlayingLayoutState

    /**
     * The sheet's state.
     *
     * Was `CurrentlyPlayingInterface`'s read side — an interface the activity implemented
     * so `CurrentlyPlayingFragment` could ask "am I on screen?" without inferring it from view
     * geometry. With the player composed directly by the activity there is no host to ask, so the
     * interface is gone and this is simply a property. It is kept distinct from
     * [currentlyPlayingLayoutState] only as a name; both read the same flow.
     */
    val bottomSheetState: StateFlow<BottomSheetState>
      get() = _currentlyPlayingLayoutState

    private val audiobookId = MutableStateFlow(NO_AUDIOBOOK_FOUND_ID)

    val audiobook: StateFlow<Audiobook> =
      audiobookId
        .mapLatest { id -> bookRepository.getAudiobookAsync(id) ?: EMPTY_AUDIOBOOK }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), EMPTY_AUDIOBOOK)

    private val tracks: Flow<List<MediaItemTrack>> =
      audiobookId.flatMapLatest { id ->
        if (id != NO_AUDIOBOOK_FOUND_ID) {
          trackRepository.getTracksForAudiobook(id)
        } else {
          flowOf(emptyList())
        }
      }

    private val _errorMessage = MutableStateFlow<Event<String>?>(null)
    val errorMessage: StateFlow<Event<String>?>
      get() = _errorMessage

    val hasCollections: StateFlow<Boolean> =
      collectionsRepository
        .hasCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    // Used to cache tracks.asChapterList when tracks changes
    private val tracksAsChaptersCache: Flow<List<Chapter>> = tracks.mapLatest { it.asChapterList() }

    /** The book's chapters from `ChapterDatabase`, the preferred source. */
    private val chaptersFromTable: Flow<List<Chapter>> =
      audiobookId.flatMapLatest { id ->
        if (id != NO_AUDIOBOOK_FOUND_ID) {
          bookRepository.getChaptersForBookLive(id)
        } else {
          flowOf(emptyList())
        }
      }

    val chapters: StateFlow<List<Chapter>> =
      combineDistinct(
        chaptersFromTable,
        tracksAsChaptersCache,
      ) { fromTable, tracksAsChapters ->
        resolveChaptersFromCache(fromTable, tracksAsChapters)
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val currentChapterTitle: StateFlow<String> =
      combineDistinct(tracks, chapters) { _tracks, _chapters ->
        if (_chapters.isEmpty() || _tracks.isEmpty()) {
          return@combineDistinct "No track playing"
        }
        // Book-absolute, because `Chapter.bookStartTimeOffset` is. This used to pass
        // `activeTrack.progress` — an **in-track** offset — into a lookup that compares against
        // book offsets, and to filter the chapters to the active track first. On a single-track
        // book the two frames are the same number, so it worked; on any later track the offset is
        // below every one of that track's chapter starts, so nothing matched and the mini player
        // showed an empty chapter title.
        //
        // `chapterAtBookProgress` is the book-frame lookup, and it clamps past the end rather than
        // returning EMPTY_CHAPTER — which is what `CurrentlyPlayingSingleton` already falls back to.
        return@combineDistinct _chapters.chapterAtBookProgress(_tracks.getProgress()).title
      }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        "No track playing",
      )

    val isPlaying: StateFlow<Boolean> =
      mediaServiceConnection.playbackState
        .map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /**
     * True while the player is buffering or connecting.
     *
     * The same derivation as the player and details screens. The mini player is often the only
     * playback control on screen, so without this a stalled start there looks identical to a paused
     * book.
     */
    val isAudioLoading: StateFlow<Boolean> =
      mediaServiceConnection.playbackState
        .map { state ->
          state.state == PlaybackStateCompat.STATE_BUFFERING ||
            state.state == PlaybackStateCompat.STATE_CONNECTING
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /**
     * Collected on [viewModelScope] rather than observed forever.
     *
     * As `observeForever` pairs these needed an explicit `removeObserver` in [onCleared] against a
     * collaborator that outlives the ViewModel; the scope's own cancellation is what does that now.
     */
    private fun observePlaybackState() {
      viewModelScope.launch(exceptionHandler) {
        mediaServiceConnection.nowPlaying.collect { metadata ->
          metadata.id?.let { trackId ->
            if (trackId.isNotEmpty()) {
              setAudiobook(trackId)
            }
          } ?: run { _currentlyPlayingLayoutState.value = HIDDEN }
        }
      }
      viewModelScope.launch(exceptionHandler) {
        mediaServiceConnection.playbackState.collect { state -> onPlaybackStateChanged(state) }
      }
    }

    private fun onPlaybackStateChanged(state: PlaybackStateCompat) {
      Timber.i("Observing playback: $state")
      when (state.state) {
        // Only STATE_NONE hides the player. It means "there is no longer anything to play" —
        // the service tore down, or nothing was ever loaded.
        //
        // STATE_STOPPED deliberately does **not**: it fires when a book reaches the end of its
        // last track, and the book is still the current one, merely not advancing. Hiding on it
        // was a one-way door — nothing could bring the sheet back, because the only routes off
        // HIDDEN need either a later non-stopped state (there is none; playback has ended) or
        // `setAudiobook` seeing a *different* book id, which re-selecting the same book fails.
        // Since the collapsed player is the only handle that expands the sheet, the player became
        // unreachable, and for an already-finished book it was never reachable at all.
        STATE_NONE -> setBottomSheetState(HIDDEN)
        else -> {
          if (currentlyPlayingLayoutState.value == HIDDEN) {
            setBottomSheetState(COLLAPSED)
          }
        }
      }
    }

    init {
      observePlaybackState()
    }

    /** The track [setAudiobook] last resolved, so an unchanged tick costs nothing. */
    private var lastResolvedTrackId: String = TRACK_NOT_FOUND

    private suspend fun setAudiobook(trackId: String) {
      // Cheapest guard first (DRAFT-117). `nowPlaying` re-emits on every 1 Hz progress tick with
      // the *same* track, and this method used to do a suspending DB read on each one before the
      // "has the book changed?" check below could reject it. Measured: 48 `mapAsync` resumptions
      // and 50 `bindImageRounded` calls in 20 s of playback, each rebinding the cover with a fresh
      // `crossfade(true)` — an animation that invalidates continuously, producing ~14 full
      // ConstraintLayout measure/layout passes a second.
      if (trackId == lastResolvedTrackId) {
        return
      }
      lastResolvedTrackId = trackId

      val previousAudiobookId = audiobook.value.id
      val bookId = trackRepository.getBookIdForTrack(trackId)
      if (bookId == NO_AUDIOBOOK_FOUND_ID) {
        return
      }
      // Only change the active audiobook if it differs from the one currently in metadata
      if (previousAudiobookId != bookId) {
        audiobookId.value = bookId
      }
      // Revealing the sheet is *not* conditional on the book having changed. It used to be, which
      // stranded the player: re-selecting the same book after it had been hidden was rejected by
      // the guard above, so nothing could bring the collapsed handle back. Whether there
      // is something playing and whether it is a *new* something are different questions.
      if (_currentlyPlayingLayoutState.value == HIDDEN) {
        // Both writes are plain assignments now. This runs in a coroutine after a suspending DB
        // read so it may not be on the main thread, which is why it used to need `postValue` — a
        // `MutableStateFlow` is thread-safe, so the exception the field's note carved out is gone.
        _currentlyPlayingLayoutState.value = COLLAPSED
      }
    }

    /**
     * Expands the currently-playing sheet, if there is anything playing to expand.
     *
     * Separate from [onCurrentlyPlayingClicked] because that one *toggles* and throws on
     * [BottomSheetState.HIDDEN]. This is idempotent and a no-op when hidden, which is what a
     * caller that just wants the player on screen needs — used by the `show_player` debug hook
     * so the "position not synced" badge can be screenshotted without tap coordinates.
     */
    fun expandCurrentlyPlaying() {
      if (currentlyPlayingLayoutState.value == COLLAPSED) {
        _currentlyPlayingLayoutState.value = EXPANDED
      }
    }

    /**
     * React to clicks on the "currently playing" modal, which is shown at the bottom of the
     * R.layout.activity_main view when media is active (can be playing or paused)
     */
    fun onCurrentlyPlayingClicked() {
      when (currentlyPlayingLayoutState.value) {
        COLLAPSED -> _currentlyPlayingLayoutState.value = EXPANDED
        EXPANDED -> _currentlyPlayingLayoutState.value = COLLAPSED
        HIDDEN -> throw IllegalStateException("Cannot click on hidden sheet!")
        else -> {}
      }
    }

    fun pausePlayButtonClicked() {
      if (!mediaServiceConnection.isConnected.value) {
        mediaServiceConnection.connect(this::pausePlay)
      } else {
        pausePlay()
      }
    }

    private fun pausePlay() {
      // Require [mediaServiceConnection] is connected
      check(mediaServiceConnection.isConnected.value)
      val transportControls = mediaServiceConnection.transportControls
      if (mediaServiceConnection.playbackState.value.isPlaying) {
        Timber.i("Pausing!")
        transportControls?.pause()
      } else {
        Timber.i("Playing!")
        transportControls?.play()
      }
    }

    fun setBottomSheetState(state: BottomSheetState) {
      _currentlyPlayingLayoutState.value = state
    }

    fun showUserMessage(errorMessage: String) {
      Timber.i("Showing error message: $errorMessage")
      _errorMessage.setEvent(errorMessage)
    }

    /** Minimize the currently playing modal/overlay if it is expanded */
    fun minimizeCurrentlyPlaying() {
      if (currentlyPlayingLayoutState.value == EXPANDED) {
        _currentlyPlayingLayoutState.value = COLLAPSED
      }
    }

    /** Maximize the currently playing modal/overlay if it is visible, but not expanded yet */
    fun maximizeCurrentlyPlaying() {
      if (currentlyPlayingLayoutState.value != EXPANDED) {
        _currentlyPlayingLayoutState.value = EXPANDED
      }
    }

    fun onCurrentlyPlayingHandleDragged() {
      if (currentlyPlayingLayoutState.value == COLLAPSED) {
        _currentlyPlayingLayoutState.value = EXPANDED
      }
    }
  }
