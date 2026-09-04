package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState.*
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.*
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.*
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus.*
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.*
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.combineDistinct
import io.github.mattpvaughn.chronicle.util.setEvent
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.*
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

@ExperimentalCoroutinesApi
class AudiobookDetailsViewModel(
  private val bookRepository: IBookRepository,
  private val trackRepository: ITrackRepository,
  private val cachedFileManager: ICachedFileManager,
  // Just the skeleton of an audiobook. Only guaranteed to contain a correct [Audiobook.id], [Audiobook.title]
  private val inputAudiobook: Audiobook,
  private val mediaServiceConnection: MediaServiceConnection,
  private val plexConfig: PlexConfig,
  private val plexMediaService: PlexMediaService,
  currentlyPlaying: CurrentlyPlaying,
  private val appContext: Context,
) : ViewModel() {
  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val bookRepository: IBookRepository,
      private val trackRepository: ITrackRepository,
      private val cachedFileManager: ICachedFileManager,
      private val mediaServiceConnection: MediaServiceConnection,
      private val plexConfig: PlexConfig,
      private val plexMediaService: PlexMediaService,
      private val currentlyPlaying: CurrentlyPlaying,
      private val appContext: Context,
    ) : ViewModelProvider.Factory {
      lateinit var inputAudiobook: Audiobook

      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(this::inputAudiobook.isInitialized) { "Input audiobook not provided!" }
        if (modelClass.isAssignableFrom(AudiobookDetailsViewModel::class.java)) {
          return AudiobookDetailsViewModel(
            bookRepository,
            trackRepository,
            cachedFileManager,
            inputAudiobook,
            mediaServiceConnection,
            plexConfig,
            plexMediaService,
            currentlyPlaying,
            appContext,
          ) as T
        } else {
          throw IllegalStateException("Wrong class provided to ${this.javaClass.name}")
        }
      }
    }

  val audiobook: StateFlow<Audiobook?> =
    bookRepository
      .getAudiobook(inputAudiobook.id)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

  val tracks: StateFlow<List<MediaItemTrack>> =
    trackRepository
      .getTracksForAudiobook(inputAudiobook.id)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  // Used to cache tracks.asChapterList when tracks changes
  private val tracksAsChaptersCache: Flow<List<Chapter>> = tracks.mapLatest { it.asChapterList() }

  /** The book's chapters from `ChapterDatabase`, the preferred source (cu-82). */
  private val chaptersFromTable: Flow<List<Chapter>> =
    bookRepository.getChaptersForBookLive(inputAudiobook.id)

  val chapters: StateFlow<List<Chapter>> =
    combineDistinct(
      chaptersFromTable,
      audiobook,
      tracksAsChaptersCache,
    ) { fromTable, book, tracksAsChapters ->
      resolveChaptersFromCache(fromTable, book, tracksAsChapters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  private val _messageForUser = MutableStateFlow<Event<FormattableString>?>(null)
  val messageForUser: StateFlow<Event<FormattableString>?>
    get() = _messageForUser

  /**
   * Cache status of the current audiobook.
   *
   * Null until both sources have emitted, which means **"not known yet"** rather than "not cached".
   *
   * The nullability is load-bearing and survives the `Flow` conversion deliberately (cu-52).
   * `audiobook` is Room-backed, so there is a real window at screen open where the book has not
   * arrived; seeding this `NOT_CACHED` instead would let the download button render enabled and
   * offer to download a book that is already on disk. The Fragment keeps the control disabled
   * while this is null, and `onCacheButtonClick` ignores a press — throwing there crashed a
   * main-screen control once (cu-92).
   */
  val cacheStatus: StateFlow<CacheStatus?> =
    combineDistinct(
      cachedFileManager.activeBookDownloads,
      audiobook,
    ) { activeDownloadIDs, book ->
      Timber.i("Active downloads: ${activeDownloadIDs.size}")
      when {
        book?.isCached == true -> CACHED
        inputAudiobook.id in activeDownloadIDs -> CACHING
        else -> NOT_CACHED
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

  val cacheIconTint: StateFlow<Int> =
    cacheStatus
      .map { status ->
        when (status) {
          CACHING -> R.color.icon // Doesn't matter, we show a spinner over it
          CACHED -> R.color.iconActive
          NOT_CACHED, null -> R.color.icon
        }
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), R.color.icon)

  val cacheIconDrawable: StateFlow<Int> =
    cacheStatus
      .map { status ->
        when (status) {
          CACHED -> R.drawable.ic_cloud_done_white
          // Spinner covers it while CACHING; null is "not known yet" and the control is disabled.
          CACHING, NOT_CACHED, null -> R.drawable.ic_cloud_download_white
        }
      }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        R.drawable.ic_cloud_download_white,
      )

  /**
   * What the download control announces, one label per state (cu-149).
   *
   * Derived from `cacheStatus` **immediately beside the icon it labels**, so the two cannot
   * diverge: the label used to be a static `android:contentDescription="@string/download"` in the
   * layout while the icon swapped in Kotlin, so a screen reader said "Download" for a book that was
   * already downloaded — one control with two meanings and one label.
   *
   * Each string says what a *tap does*, which is what `onCacheButtonClick` actually branches on:
   * download it, remove the download, or cancel one in flight. `CacheLabelPairingTest` pins the two
   * `when` blocks to the same states so a new one cannot be added to the icon alone.
   */
  val cacheContentDescription: StateFlow<Int> =
    cacheStatus
      .map { status ->
        when (status) {
          CACHING -> R.string.download_cancel
          CACHED -> R.string.download_remove
          NOT_CACHED, null -> R.string.download
        }
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), R.string.download)

  /** Whether the book in the current view is also the same on in the [MediaController] */
  private val isBookInViewActive: StateFlow<Boolean> =
    combineDistinct(currentlyPlaying.book, audiobook) { activeBook, currentBook ->
      activeBook.id == currentBook?.id && activeBook.id != EMPTY_AUDIOBOOK.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

  /**
   * Whether the book in the current view is playing.
   *
   * The combiner used to read `isBookActive ?: false && currState?.isPlaying ?: false`, which
   * Kotlin parses as `isBookActive ?: (false && …)` — so a non-null `isBookActive` short-circuited
   * and the playback state was never consulted at all. Non-null `Flow` sources make the intended
   * expression the only one that compiles (cu-52).
   */
  val isBookInViewPlaying: StateFlow<Boolean> =
    combineDistinct(
      isBookInViewActive,
      mediaServiceConnection.playbackState,
    ) { isBookActive, currState ->
      isBookActive && currState.isPlaying
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

  val progressString: StateFlow<String> =
    tracks.map { tracks ->
      if (tracks.isEmpty()) {
        return@map "0:00/0:00"
      }
      val progressStr =
        DateUtils.formatElapsedTime(
          StringBuilder(),
          tracks.getProgress().millis / 1000L,
        )
      val durationStr =
        DateUtils.formatElapsedTime(
          StringBuilder(),
          tracks.getDuration() / 1000L,
        )
      return@map "$progressStr/$durationStr"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "0:00/0:00")

  val progressPercentageString: StateFlow<String> =
    tracks
      .map { "${it.getProgressPercentage()}%" }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "0%")

  private val _isLoadingTracks = MutableStateFlow(false)
  val isLoadingTracks: StateFlow<Boolean>
    get() = _isLoadingTracks

  private val _bottomChooserState = MutableStateFlow(EMPTY_BOTTOM_CHOOSER)
  val bottomChooserState: StateFlow<BottomChooserState>
    get() = _bottomChooserState

  // The maximum number of lines to shown in the info section
  private val lineCountSummaryMinimized = 5
  private val lineCountSummaryMaximized = Int.MAX_VALUE
  private val _summaryLinesShown = MutableStateFlow(lineCountSummaryMinimized)
  val summaryLinesShown: StateFlow<Int>
    get() = _summaryLinesShown

  val isAudioLoading: StateFlow<Boolean> =
    mediaServiceConnection.playbackState.map { state ->
      if (state.state == PlaybackStateCompat.STATE_ERROR) {
        Timber.i("Playback state: ${state.stateName}, (${state.errorMessage})")
      } else {
        Timber.i("Playback state: ${state.stateName}")
      }
      state.state == STATE_BUFFERING || state.state == STATE_CONNECTING
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

  val showSummary: StateFlow<Boolean> =
    audiobook
      .map { it?.summary?.isNotEmpty() ?: false }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

  val isExpanded: StateFlow<Boolean> =
    summaryLinesShown
      .map { it == lineCountSummaryMaximized }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

  val serverConnection: StateFlow<PlexConfig.ConnectionState> = plexConfig.connectionState

  fun onToggleSummaryView() {
    _summaryLinesShown.value =
      if (_summaryLinesShown.value == lineCountSummaryMinimized) lineCountSummaryMaximized else lineCountSummaryMinimized
  }

  fun connectToServer() {
    viewModelScope.launch(Dispatchers.IO) {
      plexConfig.connectToServer(plexMediaService)
    }
  }

  private val cachedChapter: Flow<Chapter> =
    combineDistinct(
      chapters,
      tracks,
    ) { _chapters, _tracks ->
      // Deliberately not logged. These lines serialised the entire chapter list — 40+ objects —
      // several times a second on a real book, which is a measurable cost in a debug build and
      // drowned the log when diagnosing the seek churn (cu-93).

      // See the same fix in CurrentlyPlayingViewModel: the hand-rolled walk this replaces mixed
      // relative and absolute chapter offsets and resolved the wrong chapter (cu-73).
      _chapters.chapterAtBookProgress(_tracks.getProgress())
    }

  val activeChapter: StateFlow<Chapter> =
    combineDistinct(
      currentlyPlaying.chapter,
      cachedChapter,
    ) { activeChapter, cachedChapter ->
      if (activeChapter != EMPTY_CHAPTER && activeChapter.trackId == cachedChapter.trackId) {
        activeChapter
      } else {
        cachedChapter
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), EMPTY_CHAPTER)

  val isWatchedIcon: StateFlow<Int> =
    audiobook
      .map { if (it?.viewCount != 0L) R.drawable.ic_visibility_off else R.drawable.ic_visibility }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        R.drawable.ic_visibility_off,
      )

  init {
    // Collected on viewModelScope rather than observed forever, which is what the explicit
    // removeObserver in onCleared was doing by hand — the scope cancels there anyway.
    viewModelScope.launch {
      plexConfig.isConnected.collect { isConnected ->
        if (isConnected) {
          loadBookDetails(inputAudiobook.id)
        }
      }
    }
  }

  /**
   * Refresh details for the current audiobook. Mostly important because we want to refresh the
   * progress in the audiobook is there has been new playback
   */
  private fun loadBookDetails(bookId: String) {
    Timber.i("Refreshing tracks!")
    viewModelScope.launch {
      try {
        // If we're just updating underlying track list, and there are already tracks/chapters
        // loaded, don't replace chapter view with loading view.
        //
        // This was `delay(50)` "to ensure chapters have loaded from db" — a guess at how long a
        // Room-backed LiveData takes to emit, which is a race either way round: too short and the
        // spinner replaces chapters that were already on screen, too long and every refresh pays
        // for it. `chapters` derives from `audiobook`, so the question is only whether that first
        // emission has arrived; ask the value, not the clock.
        val noExistingChapters = chapters.value.isEmpty()
        _isLoadingTracks.value = noExistingChapters
        val trackRequest = trackRepository.loadTracksForAudiobook(bookId)
        if (trackRequest.isOk) {
          val audiobook = bookRepository.getAudiobookAsync(bookId)
          audiobook?.let {
            trackRepository.syncTracksInBook(audiobook.id)
            bookRepository.syncAudiobook(audiobook, trackRequest.value)
          }
        }
        _isLoadingTracks.value = false
      } catch (e: Throwable) {
        Timber.e(e, "Failed to load tracks for audiobook $bookId")
        _isLoadingTracks.value = false
      }
    }
  }

  fun onCacheButtonClick() {
    when (cacheStatus.value) {
      NOT_CACHED -> {
        Timber.i("Caching tracks for \"${audiobook.value?.title}\"")
        if (!plexConfig.isConnected.value) {
          showUserMessage(FormattableString.from(R.string.unable_to_cache_audiobook))
        } else {
          cachedFileManager.downloadTracks(inputAudiobook.id, inputAudiobook.title)
        }
      }
      CACHED -> {
        Timber.i("Already cached. Uncache?")
        promptUserToUncache()
      }
      CACHING -> {
        Timber.i("Cancelling download: ${inputAudiobook.id}")
        cachedFileManager.cancelGroup(inputAudiobook.id)
      }
      // Null until both of `cacheStatus`'s sources have emitted. That is "not known yet", not an
      // error — throwing here crashed a main-screen control (cu-92). The Fragment also keeps the
      // button disabled until the status resolves, so this is the backstop rather than the only
      // guard.
      null -> Timber.i("Cache button pressed before the status resolved; ignoring")
    }
  }

  private fun showUserMessage(message: FormattableString) {
    _messageForUser.setEvent(message)
  }

  private fun promptUserToUncache() {
    showOptionsMenu(
      title = FormattableString.from(R.string.delete_cache_files_prompt),
      options = listOf(FormattableString.yes, FormattableString.no),
      listener =
        object : BottomChooserItemListener() {
          override fun onItemClicked(formattableString: FormattableString) {
            when (formattableString) {
              FormattableString.yes -> uncacheFiles()
              FormattableString.no -> { // Do nothing
              }
              else -> throw NoWhenBranchMatchedException("Unknown option selected!")
            }
            hideBottomSheet()
          }
        },
    )
  }

  private fun uncacheFiles() {
    viewModelScope.launch {
      cachedFileManager.deleteCachedBook(inputAudiobook.id)
    }
  }

  fun pausePlayButtonClicked() {
    if (!plexConfig.isConnected.value && audiobook.value?.isCached == false) {
      showUserMessage(FormattableString.from(R.string.cannot_play_media_no_server))
      return
    }

    val pausePlayAction = {
      pausePlay(
        bookId = inputAudiobook.id,
        bookStartTimeOffset = USE_SAVED_TRACK_PROGRESS,
        forcePlayFromMediaId = false,
      )
    }
    if (!mediaServiceConnection.isConnected.value) {
      mediaServiceConnection.connect(pausePlayAction)
    } else {
      pausePlayAction()
    }
  }

  /**
   * Play or pause the audiobook with id [bookId] depending whether playback is active (as
   * determined by [isBookInViewPlaying]
   *
   * Assume that [mediaServiceConnection] has connected
   *
   * Play behavior: start/resume playback from [bookStartTimeOffset] milliseconds from the start of
   * the book. A null [trackId] indicates that playback should be resumed from the most recent
   * playback location
   *
   * [forcePlayFromMediaId] == true indicates to ignore playback state and play the book from the
   * given [trackId] and [bookStartTimeOffset] provided, otherwise pause/play/resume depending on
   * playback state
   */
  private fun pausePlay(
    bookId: String,
    bookStartTimeOffset: Long = USE_SAVED_TRACK_PROGRESS,
    trackId: String? = null,
    forcePlayFromMediaId: Boolean = false,
  ) {
    if (!mediaServiceConnection.isConnected.value) {
      Timber.e("MediaServiceConnection not connected")
      return
    }
    val transportControls = mediaServiceConnection.transportControls ?: return

    val extras =
      Bundle().apply {
        putLong(KEY_START_TIME_TRACK_OFFSET, bookStartTimeOffset)
        // Only written when a specific track was asked for; absence means "resume active".
        trackId?.let { putString(KEY_SEEK_TO_TRACK_WITH_ID, it) }
      }
    Timber.i(
      "is this book playing? ${isBookInViewPlaying.value}, this this book active? ${isBookInViewActive.value}",
    )
    when {
      forcePlayFromMediaId -> transportControls.playFromMediaId(bookId, extras)
      isBookInViewPlaying.value -> transportControls.pause()
      isBookInViewActive.value -> transportControls.play()
      else -> transportControls.playFromMediaId(bookId, extras)
    }
  }

  fun jumpToChapter(
    bookStartTimeOffset: BookOffset = BookOffset.ZERO,
    trackId: String = TRACK_NOT_FOUND,
    hasUserConfirmation: Boolean = false,
  ) {
    if (!hasUserConfirmation) {
      showOptionsMenu(
        title =
          FormattableString.from(
            R.string.warning_jump_to_chapter_will_clear_progress,
          ),
        options = listOf(FormattableString.yes, FormattableString.no),
        listener =
          object : BottomChooserItemListener() {
            override fun onItemClicked(formattableString: FormattableString) {
              when (formattableString) {
                FormattableString.yes -> jumpToChapter(bookStartTimeOffset, trackId, true)
                FormattableString.no -> Unit
                else -> throw NoWhenBranchMatchedException()
              }
              hideBottomSheet()
            }
          },
      )
      return
    }

    val jumpToChapterAction = {
      audiobook.value?.let { book ->
        // The offset arrives book-absolute from the chapter list, but is applied as an in-track
        // offset by the service (cu-96). One conversion, one home (cu-136).
        val inTrackOffset =
          tracks.value?.let { loaded -> inTrackOffsetOf(bookStartTimeOffset, trackId, loaded) }
            ?: TrackOffset(bookStartTimeOffset.millis)
        pausePlay(book.id, inTrackOffset.millis, trackId, forcePlayFromMediaId = true)
      }
    }
    if (!mediaServiceConnection.isConnected.value) {
      mediaServiceConnection.connect(onConnected = jumpToChapterAction)
    } else {
      jumpToChapterAction()
    }
  }

  private fun hideBottomSheet() {
    Timber.i("Hiding bottom sheet?")
    _bottomChooserState.value = _bottomChooserState.value.copy(shouldShow = false)
  }

  private fun showOptionsMenu(
    title: FormattableString,
    options: List<FormattableString>,
    listener: BottomChooserListener,
  ) {
    _bottomChooserState.value =
      BottomChooserState(
        title = title,
        options = options,
        listener = listener,
        shouldShow = true,
      )
  }

  fun toggleWatched() {
    val notPlayedYet = (audiobook.value?.viewCount ?: 0) == 0L

    val prompt =
      if (notPlayedYet) {
        R.string.prompt_mark_as_played
      } else {
        R.string.prompt_mark_as_unplayed
      }

    showOptionsMenu(
      title = FormattableString.from(prompt),
      options = listOf(FormattableString.yes, FormattableString.no),
      listener =
        object : BottomChooserItemListener() {
          override fun onItemClicked(formattableString: FormattableString) {
            if (formattableString == FormattableString.yes) {
              if (notPlayedYet) {
                setAudiobookWatched()
              } else {
                setAudiobookUnwatched()
              }
            }
            hideBottomSheet()
          }
        },
    )
  }

  private fun setAudiobookWatched() {
    Timber.i("Marking audiobook as watched")
    viewModelScope.launch {
      // Symmetrical with setAudiobookUnwatched: report what actually happened, and do not let a
      // server failure escape the coroutine.
      val message =
        try {
          // Plex will set tracks as unwatched if their parent becomes unwatched, so no need
          // for [ITrackRepository.setWatched]
          trackRepository.markTracksInBookAsWatched(inputAudiobook.id)
          bookRepository.setWatched(inputAudiobook.id)
          R.string.marked_as_played
        } catch (t: Throwable) {
          Timber.e(t, "Failed to mark book ${inputAudiobook.id} as played")
          R.string.mark_as_played_failed
        }
      showToast(message)
    }
  }

  private fun setAudiobookUnwatched() {
    Timber.i("Marking audiobook as unwatched")
    viewModelScope.launch {
      // The track half now talks to the server (cu-98), so it can fail. Reporting success before
      // knowing the outcome would tell the user a book was repaired when it was not — and an
      // uncaught throw here would skip `setUnwatched` entirely, leaving the two halves disagreeing,
      // which is the cu-86 split this pairing exists to prevent.
      val message =
        try {
          // Mirrors setAudiobookWatched: both halves, or the tracks keep a state the book does not
          // and which one shows depends on the order things ran in (cu-86).
          trackRepository.markTracksInBookAsUnwatched(inputAudiobook.id)
          bookRepository.setUnwatched(inputAudiobook.id)
          R.string.marked_as_unplayed
        } catch (t: Throwable) {
          Timber.e(t, "Failed to mark book ${inputAudiobook.id} as unplayed")
          R.string.mark_as_unplayed_failed
        }
      showToast(message)
    }
  }

  /** Bottom-anchored toast, matching the placement the other playback messages use. */
  private fun showToast(
    @StringRes message: Int,
  ) {
    val toast =
      Toast.makeText(
        appContext,
        message,
        Toast.LENGTH_LONG,
      )
    toast.setGravity(Gravity.BOTTOM, 0, 200)
    toast.show()
  }

  private val _forceSyncInProgress = MutableStateFlow(false)
  val forceSyncInProgress: StateFlow<Boolean>
    get() = _forceSyncInProgress

  fun forceSyncBook(hasUserConfirmation: Boolean = false) {
    viewModelScope.launch {
      if (!hasUserConfirmation) {
        showOptionsMenu(
          title = FormattableString.from(R.string.prompt_force_sync),
          options = listOf(FormattableString.yes, FormattableString.no),
          listener =
            object : BottomChooserItemListener() {
              override fun onItemClicked(formattableString: FormattableString) {
                if (formattableString == FormattableString.yes) {
                  forceSyncBook(hasUserConfirmation = true)
                }
                hideBottomSheet()
              }
            },
        )
        return@launch
      } else {
        Timber.i("Refreshing track data!!!")
        if (!plexConfig.isConnected.value) {
          showUserMessage(FormattableString.from(R.string.cannot_sync_no_server))
          return@launch
        }
        val audiobook = audiobook.value
        if (audiobook == null) {
          showUserMessage(FormattableString.from(R.string.progress_sync_failed))
          return@launch
        }
        _forceSyncInProgress.value = true
        val updatedTracks =
          trackRepository.syncTracksInBook(audiobook.id, forceUseNetwork = true)
        val loadSucceeded = bookRepository.syncAudiobook(audiobook, updatedTracks, true)
        if (loadSucceeded) {
          showUserMessage(FormattableString.from(R.string.progress_sync_successful))
        } else {
          showUserMessage(FormattableString.from(R.string.progress_sync_failed))
        }
        _forceSyncInProgress.value = false
      }
    }
  }
}
