package io.github.mattpvaughn.chronicle.features.bookdetails

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
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.*
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus.*
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.*
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.util.DoubleLiveData
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.mapAsync
import io.github.mattpvaughn.chronicle.util.postEvent
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.*
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
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
          ) as T
        } else {
          throw IllegalStateException("Wrong class provided to ${this.javaClass.name}")
        }
      }
    }

  val audiobook: LiveData<Audiobook?> = bookRepository.getAudiobook(inputAudiobook.id)
  val tracks = trackRepository.getTracksForAudiobook(inputAudiobook.id)

  // Used to cache tracks.asChapterList when tracks changes
  private val tracksAsChaptersCache: LiveData<List<Chapter>> =
    mapAsync(tracks, viewModelScope) {
      it.asChapterList()
    }

  val chapters: DoubleLiveData<Audiobook?, List<Chapter>, List<Chapter>> =
    DoubleLiveData(
      audiobook,
      tracksAsChaptersCache,
    ) { _audiobook: Audiobook?, _tracksAsChapters: List<Chapter>? ->
      Timber.i("Chapter data updated! ")
      if (_audiobook?.chapters?.isNotEmpty() == true) {
        _audiobook.chapters
      } else {
        _tracksAsChapters ?: emptyList()
      }
    }

  private var _messageForUser = MutableLiveData<Event<FormattableString>>()
  val messageForUser: LiveData<Event<FormattableString>>
    get() = _messageForUser

  /**
   * Cache status of the current audiobook. Reflects the cache status of [tracks] if they've
   * been loaded, otherwise default to [_manualCacheStatus].
   */
  val cacheStatus =
    DoubleLiveData(
      cachedFileManager.activeBookDownloads,
      audiobook,
    ) { activeDownloadIDs: Set<String>?, _audiobook: Audiobook? ->
      Timber.i("Active downloads: ${activeDownloadIDs?.size ?: 0}")
      return@DoubleLiveData when {
        _audiobook?.isCached == true -> CACHED
        inputAudiobook.id in (activeDownloadIDs ?: emptySet()) -> CACHING
        else -> NOT_CACHED
      }
    }

  val cacheIconTint =
    cacheStatus.map { status ->
      return@map when (status) {
        CACHING -> R.color.icon // Doesn't matter, we show a spinner over it
        NOT_CACHED -> R.color.icon
        CACHED -> R.color.iconActive
        null -> R.color.icon
      }
    }

  val cacheIconDrawable: LiveData<Int> =
    cacheStatus.map { status ->
      return@map when (status) {
        CACHING -> R.drawable.ic_cloud_download_white // Doesn't matter, we show a spinner over it
        NOT_CACHED -> R.drawable.ic_cloud_download_white
        CACHED -> R.drawable.ic_cloud_done_white
      }
    }

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
  val cacheContentDescription: LiveData<Int> =
    cacheStatus.map { status ->
      return@map when (status) {
        CACHING -> R.string.download_cancel
        NOT_CACHED -> R.string.download
        CACHED -> R.string.download_remove
      }
    }

  private val activeBook = currentlyPlaying.book.asLiveData(viewModelScope.coroutineContext)

  /** Whether the book in the current view is also the same on in the [MediaController] */
  private val isBookInViewActive =
    DoubleLiveData<Audiobook, Audiobook?, Boolean>(
      activeBook,
      audiobook,
    ) { activeBook, currentBook ->
      return@DoubleLiveData activeBook?.id == currentBook?.id &&
        activeBook?.id != null
    }

  /** Whether the book in the current view is playing */
  val isBookInViewPlaying =
    DoubleLiveData<Boolean, PlaybackStateCompat, Boolean>(
      isBookInViewActive,
      mediaServiceConnection.playbackState,
    ) { isBookActive, currState ->
      return@DoubleLiveData isBookActive ?: false && currState?.isPlaying ?: false
    }

  val progressString =
    tracks.map { tracks: List<MediaItemTrack> ->
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
    }

  val progressPercentageString =
    tracks.map { tracks: List<MediaItemTrack> ->
      return@map "${tracks.getProgressPercentage()}%"
    }

  private var _isLoadingTracks = MutableLiveData(false)
  val isLoadingTracks: LiveData<Boolean>
    get() = _isLoadingTracks

  private var _bottomChooserState = MutableLiveData(EMPTY_BOTTOM_CHOOSER)
  val bottomChooserState: LiveData<BottomChooserState>
    get() = _bottomChooserState

  // The maximum number of lines to shown in the info section
  private val lineCountSummaryMinimized = 5
  private val lineCountSummaryMaximized = Int.MAX_VALUE
  private var _summaryLinesShown = MutableLiveData(lineCountSummaryMinimized)
  val summaryLinesShown: LiveData<Int>
    get() = _summaryLinesShown

  val isAudioLoading =
    mediaServiceConnection.playbackState.map { state ->
      if (state.state == PlaybackStateCompat.STATE_ERROR) {
        Timber.i("Playback state: ${state.stateName}, (${state.errorMessage})")
      } else {
        Timber.i("Playback state: ${state.stateName}")
      }
      state.state == STATE_BUFFERING || state.state == STATE_CONNECTING
    }

  val showSummary =
    audiobook.map { book ->
      book?.summary?.isNotEmpty() ?: false
    }

  val isExpanded =
    summaryLinesShown.map { lines ->
      return@map lines == lineCountSummaryMaximized
    }

  val serverConnection = plexConfig.connectionState.map { it }

  fun onToggleSummaryView() {
    _summaryLinesShown.value =
      if (_summaryLinesShown.value == lineCountSummaryMinimized) lineCountSummaryMaximized else lineCountSummaryMinimized
  }

  fun connectToServer() {
    viewModelScope.launch(Dispatchers.IO) {
      plexConfig.connectToServer(plexMediaService)
    }
  }

  private val networkObserver =
    Observer<Boolean> { isConnected ->
      if (isConnected) {
        loadBookDetails(inputAudiobook.id)
      }
    }

  private val cachedChapter =
    DoubleLiveData(
      chapters,
      tracks,
    ) { _chapters: List<Chapter>?, _tracks: List<MediaItemTrack>? ->
      // Deliberately not logged. These lines serialised the entire chapter list — 40+ objects —
      // several times a second on a real book, which is a measurable cost in a debug build and
      // drowned the log when diagnosing the seek churn (cu-93).

      // See the same fix in CurrentlyPlayingViewModel: the hand-rolled walk this replaces mixed
      // relative and absolute chapter offsets and resolved the wrong chapter (cu-73).
      if (_tracks != null && _chapters != null) {
        _chapters.chapterAtBookProgress(_tracks.getProgress())
      } else {
        EMPTY_CHAPTER
      }
    }.asFlow()

  val activeChapter =
    currentlyPlaying.chapter.combine(
      cachedChapter,
    ) { activeChapter: Chapter, cachedChapter: Chapter ->
      if (activeChapter != EMPTY_CHAPTER && activeChapter.trackId == cachedChapter.trackId) {
        activeChapter
      } else {
        cachedChapter
      }
    }.asLiveData(viewModelScope.coroutineContext)

  val isWatchedIcon: LiveData<Int> =
    audiobook.map {
      if (it?.viewCount != 0L) R.drawable.ic_visibility_off else R.drawable.ic_visibility
    }

  init {
    plexConfig.isConnected.observeForever(networkObserver)
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
        val noExistingChapters = chapters.value.isNullOrEmpty()
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
        if (plexConfig.isConnected.value != true) {
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
      // null until `cacheStatus` has an observer *and* both its sources have emitted. That is
      // "not known yet", not an error — throwing here crashed a main-screen control (cu-92). The
      // Fragment also keeps the button disabled until the status resolves, so this is the backstop
      // rather than the only guard.
      null -> Timber.i("Cache button pressed before the status resolved; ignoring")
    }
  }

  private fun showUserMessage(message: FormattableString) {
    _messageForUser.postEvent(message)
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
    if (plexConfig.isConnected.value != true && audiobook.value?.isCached == false) {
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
    if (mediaServiceConnection.isConnected.value != true) {
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
    if (mediaServiceConnection.isConnected.value != true) {
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
      isBookInViewPlaying.value == true -> transportControls.pause()
      isBookInViewActive.value == true -> transportControls.play()
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
    if (mediaServiceConnection.isConnected.value != true) {
      mediaServiceConnection.connect(onConnected = jumpToChapterAction)
    } else {
      jumpToChapterAction()
    }
  }

  private fun hideBottomSheet() {
    Timber.i("Hiding bottom sheet?")
    _bottomChooserState.postValue(
      _bottomChooserState.value?.copy(shouldShow = false) ?: EMPTY_BOTTOM_CHOOSER,
    )
  }

  private fun showOptionsMenu(
    title: FormattableString,
    options: List<FormattableString>,
    listener: BottomChooserListener,
  ) {
    _bottomChooserState.postValue(
      BottomChooserState(
        title = title,
        options = options,
        listener = listener,
        shouldShow = true,
      ),
    )
  }

  override fun onCleared() {
    plexConfig.isConnected.removeObserver(networkObserver)
    super.onCleared()
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
        Injector.get().applicationContext(),
        message,
        Toast.LENGTH_LONG,
      )
    toast.setGravity(Gravity.BOTTOM, 0, 200)
    toast.show()
  }

  private var _forceSyncInProgress = MutableLiveData(false)
  val forceSyncInProgress: LiveData<Boolean>
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
        if (plexConfig.isConnected.value != true) {
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
