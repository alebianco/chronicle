package io.github.mattpvaughn.chronicle.application

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
import androidx.lifecycle.*
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
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.mapAsync
import io.github.mattpvaughn.chronicle.util.postEvent
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class MainActivityViewModel(
  loginRepo: IPlexLoginRepo,
  private val trackRepository: ITrackRepository,
  private val bookRepository: IBookRepository,
  private val mediaServiceConnection: MediaServiceConnection,
  collectionsRepository: CollectionsRepository,
) : ViewModel(), MainActivity.CurrentlyPlayingInterface {
  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val loginRepo: IPlexLoginRepo,
      private val trackRepository: ITrackRepository,
      private val bookRepository: IBookRepository,
      private val mediaServiceConnection: MediaServiceConnection,
      private val collectionsRepository: CollectionsRepository,
    ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainActivityViewModel::class.java)) {
          return MainActivityViewModel(
            loginRepo,
            trackRepository,
            bookRepository,
            mediaServiceConnection,
            collectionsRepository,
          ) as T
        } else {
          throw IllegalArgumentException(
            "Cannot instantiate $modelClass from MainActivityViewModel.Factory",
          )
        }
      }
    }

  /** The status of the bottom sheet which contains "currently playing" info */
  enum class BottomSheetState {
    COLLAPSED,
    HIDDEN,
    EXPANDED,
  }

  val isLoggedIn =
    loginRepo.loginEvent.map {
      it.peekContent() == LOGGED_IN_FULLY
    }

  /**
   * True while the user is part-way through onboarding — signed in, but without a user, server or
   * library chosen yet.
   *
   * The back handler needs this. Backing out of a picker used to fall through to "switch to the
   * Home tab", which showed a Home rendered from the *previous* session's Room data — so the app
   * looked fully configured while its own state said otherwise and the prefs held no library
   * (cu-124). The emptier the cache, the more obviously broken it would have looked; with a full
   * one it was invisible.
   */
  val isOnboarding =
    loginRepo.loginEvent.map {
      when (it.peekContent()) {
        LOGGED_IN_NO_USER_CHOSEN, LOGGED_IN_NO_SERVER_CHOSEN, LOGGED_IN_NO_LIBRARY_CHOSEN -> true
        else -> false
      }
    }

  /**
   * The player sheet's state. Written with `value =`, **never** `postValue`.
   *
   * Every writer here runs on the main thread — click handlers and LiveData observers — and
   * `postValue` defers the write to the next main-loop pass. Three things read this state back to
   * decide what to do ([minimizeCurrentlyPlaying], [maximizeCurrentlyPlaying],
   * [onCurrentlyPlayingHandleDragged]) and so does the activity's back handler, so a deferred write
   * means the next reader sees the *previous* state. Back then decided the sheet was not expanded
   * and fell through to leaving the app (cu-73).
   *
   * `postValue` also coalesces: several posts in one loop keep only the last, so a
   * collapse-then-expand pair could lose the collapse entirely.
   */
  private var _currentlyPlayingLayoutState = MutableLiveData(HIDDEN)
  val currentlyPlayingLayoutState: LiveData<BottomSheetState>
    get() = _currentlyPlayingLayoutState

  private var audiobookId = MutableLiveData(NO_AUDIOBOOK_FOUND_ID)

  val audiobook =
    mapAsync(audiobookId, viewModelScope) { id ->
      bookRepository.getAudiobookAsync(id) ?: EMPTY_AUDIOBOOK
    }

  private var tracks =
    audiobookId.switchMap { id ->
      if (id != NO_AUDIOBOOK_FOUND_ID) {
        trackRepository.getTracksForAudiobook(id)
      } else {
        MutableLiveData(emptyList())
      }
    }

  private var _errorMessage = MutableLiveData<Event<String>>()
  val errorMessage: LiveData<Event<String>>
    get() = _errorMessage

  val hasCollections = collectionsRepository.hasCollections()

  // Used to cache tracks.asChapterList when tracks changes
  private val tracksAsChaptersCache =
    mapAsync(tracks, viewModelScope) {
      it.asChapterList()
    }

  val chapters: DoubleLiveData<Audiobook, List<Chapter>, List<Chapter>> =
    DoubleLiveData(
      audiobook,
      tracksAsChaptersCache,
    ) { _audiobook: Audiobook?, _tracksAsChapters: List<Chapter>? ->
      if (_audiobook?.chapters?.isNotEmpty() == true) {
        // We would really prefer this because it doesn't have to be computed
        _audiobook.chapters
      } else {
        _tracksAsChapters ?: emptyList()
      }
    }

  val currentChapterTitle =
    DoubleLiveData(tracks, chapters) { _tracks, _chapters ->
      if (_chapters.isNullOrEmpty() || _tracks.isNullOrEmpty()) {
        return@DoubleLiveData "No track playing"
      }
      // Book-absolute, because `Chapter.bookStartTimeOffset` is (cu-115). This used to pass
      // `activeTrack.progress` — an **in-track** offset — into a lookup that compares against
      // book offsets, and to filter the chapters to the active track first. On a single-track
      // book the two frames are the same number, so it worked; on any later track the offset is
      // below every one of that track's chapter starts, so nothing matched and the mini player
      // showed an empty chapter title.
      //
      // `chapterAtBookProgress` is the book-frame lookup, and it clamps past the end rather than
      // returning EMPTY_CHAPTER — which is what `CurrentlyPlayingSingleton` already falls back to.
      return@DoubleLiveData _chapters.chapterAtBookProgress(_tracks.getProgress()).title
    }

  val isPlaying =
    mediaServiceConnection.playbackState.map {
      it.isPlaying
    }

  /**
   * True while the player is buffering or connecting.
   *
   * The same derivation as the player and details screens. The mini player is often the only
   * playback control on screen, so without this a stalled start there looks identical to a paused
   * book (cu-95).
   */
  val isAudioLoading =
    mediaServiceConnection.playbackState.map { state ->
      state.state == PlaybackStateCompat.STATE_BUFFERING ||
        state.state == PlaybackStateCompat.STATE_CONNECTING
    }

  private val metadataObserver =
    Observer<MediaMetadataCompat> { metadata ->
      metadata.id?.let { trackId ->
        if (trackId.isNotEmpty()) {
          viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
            setAudiobook(trackId)
          }
        }
      } ?: run { _currentlyPlayingLayoutState.value = HIDDEN }
    }

  private val playbackObserver =
    Observer<PlaybackStateCompat> { state ->
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
        // unreachable, and for an already-finished book it was never reachable at all (cu-119).
        STATE_NONE -> setBottomSheetState(HIDDEN)
        else -> {
          if (currentlyPlayingLayoutState.value == HIDDEN) {
            setBottomSheetState(COLLAPSED)
          }
        }
      }
    }

  init {
    mediaServiceConnection.nowPlaying.observeForever(metadataObserver)
    mediaServiceConnection.playbackState.observeForever(playbackObserver)
  }

  /** The track [setAudiobook] last resolved, so an unchanged tick costs nothing. */
  private var lastResolvedTrackId: String = TRACK_NOT_FOUND

  private fun setAudiobook(trackId: String) {
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

    val previousAudiobookId = audiobook.value?.id ?: NO_AUDIOBOOK_FOUND_ID
    viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
      val bookId = trackRepository.getBookIdForTrack(trackId)
      if (bookId == NO_AUDIOBOOK_FOUND_ID) {
        return@launch
      }
      // Only change the active audiobook if it differs from the one currently in metadata
      if (previousAudiobookId != bookId) {
        audiobookId.postValue(bookId)
      }
      // Revealing the sheet is *not* conditional on the book having changed. It used to be, which
      // stranded the player: re-selecting the same book after it had been hidden was rejected by
      // the guard above, so nothing could bring the collapsed handle back (cu-119). Whether there
      // is something playing and whether it is a *new* something are different questions.
      if (_currentlyPlayingLayoutState.value == HIDDEN) {
        // The one legitimate postValue: this runs in a coroutine after a suspending DB read, so
        // it may not be on the main thread. Every other writer of this field uses `value =` —
        // see the field's own note for why that matters.
        _currentlyPlayingLayoutState.postValue(COLLAPSED)
      }
    }
  }

  /**
   * Expands the currently-playing sheet, if there is anything playing to expand.
   *
   * Separate from [onCurrentlyPlayingClicked] because that one *toggles* and throws on
   * [BottomSheetState.HIDDEN]. This is idempotent and a no-op when hidden, which is what a
   * caller that just wants the player on screen needs — used by the `show_player` debug hook
   * so the "position not synced" badge can be screenshotted without tap coordinates (cu-73).
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
    if (mediaServiceConnection.isConnected.value != true) {
      mediaServiceConnection.connect(this::pausePlay)
    } else {
      pausePlay()
    }
  }

  private fun pausePlay() {
    // Require [mediaServiceConnection] is connected
    check(mediaServiceConnection.isConnected.value == true)
    val transportControls = mediaServiceConnection.transportControls
    mediaServiceConnection.playbackState.value?.let { playbackState ->
      if (playbackState.isPlaying) {
        Timber.i("Pausing!")
        transportControls?.pause()
      } else {
        Timber.i("Playing!")
        transportControls?.play()
      }
    }
  }

  override fun onCleared() {
    mediaServiceConnection.nowPlaying.removeObserver(metadataObserver)
    mediaServiceConnection.playbackState.removeObserver(playbackObserver)
    super.onCleared()
  }

  override fun setBottomSheetState(state: BottomSheetState) {
    _currentlyPlayingLayoutState.value = state
  }

  fun showUserMessage(errorMessage: String) {
    Timber.i("Showing error message: $errorMessage")
    _errorMessage.postEvent(errorMessage)
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
