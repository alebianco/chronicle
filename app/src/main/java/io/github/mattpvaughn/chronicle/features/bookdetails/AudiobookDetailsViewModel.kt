package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState.*
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.*
import io.github.mattpvaughn.chronicle.data.model.progressState
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager.CacheStatus.*
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.BookHeader
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DetailsUiState
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.DownloadState
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.PlaybackState
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.ProgressLine
import io.github.mattpvaughn.chronicle.features.bookdetails.compose.SummaryState
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.*
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.STOP_TIMEOUT_MILLIS
import io.github.mattpvaughn.chronicle.util.combineDistinct
import io.github.mattpvaughn.chronicle.util.setEvent
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserItemListener
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserListener
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import kotlin.math.roundToInt

@ExperimentalCoroutinesApi
@HiltViewModel(assistedFactory = AudiobookDetailsViewModel.Factory::class)
class AudiobookDetailsViewModel
  @AssistedInject
  constructor(
    private val bookRepository: IBookRepository,
    private val trackRepository: ITrackRepository,
    private val cachedFileManager: ICachedFileManager,
    private val mediaServiceConnection: MediaServiceConnection,
    private val plexConfig: PlexConfig,
    private val plexMediaService: PlexMediaService,
    currentlyPlaying: CurrentlyPlaying,
    private val appContext: Context,
    private val dispatchers: DispatcherProvider,
    @Assisted private val bookId: String,
  ) : ViewModel() {
    /**
     * `Eagerly`, not `WhileSubscribed` — five click handlers read `audiobook.value` synchronously.
     *
     * `pausePlayButtonClicked`, `onCacheButtonClick`, `toggleWatched` and `forceSync` all branch on
     * this without collecting it, and under `WhileSubscribed` a screen whose button is pressed
     * before anything subscribes reads the `null` seed. `pausePlayButtonClicked`'s offline guard
     * (`audiobook.value?.isCached == false`) then evaluates false and lets an uncached book reach
     * the player with no server — the exact case `playing an undownloaded book while disconnected
     * does not reach the player` pins. The `LiveData` this replaces was a Room query, hot from the
     * moment the screen observed it, so the distinction did not arise.
     */
    val audiobook: StateFlow<Audiobook?> =
      bookRepository
        .getAudiobook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tracks: StateFlow<List<MediaItemTrack>> =
      trackRepository
        .getTracksForAudiobook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    // Used to cache tracks.asChapterList when tracks changes
    private val tracksAsChaptersCache: Flow<List<Chapter>> = tracks.mapLatest { it.asChapterList() }

    /** The book's chapters from `ChapterDatabase`, the preferred source. */
    private val chaptersFromTable: Flow<List<Chapter>> =
      bookRepository.getChaptersForBookLive(bookId)

    val chapters: StateFlow<List<Chapter>> =
      combineDistinct(
        chaptersFromTable,
        tracksAsChaptersCache,
      ) { fromTable, tracksAsChapters ->
        resolveChaptersFromCache(fromTable, tracksAsChapters)
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _messageForUser = MutableStateFlow<Event<FormattableString>?>(null)
    val messageForUser: StateFlow<Event<FormattableString>?>
      get() = _messageForUser

    /**
     * Cache status of the current audiobook.
     *
     * Null until both sources have emitted, which means **"not known yet"** rather than "not cached".
     *
     * The nullability is load-bearing and survives the `Flow` conversion deliberately.
     * `audiobook` is Room-backed, so there is a real window at screen open where the book has not
     * arrived; seeding this `NOT_CACHED` instead would let the download button render enabled and
     * offer to download a book that is already on disk. The Fragment keeps the control disabled
     * while this is null, and `onCacheButtonClick` ignores a press — throwing there crashed a
     * main-screen control once.
     */
    val cacheStatus: StateFlow<CacheStatus?> =
      combineDistinct(
        cachedFileManager.activeBookDownloads,
        audiobook,
      ) { activeDownloadIDs, book ->
        Timber.i("Active downloads: ${activeDownloadIDs.size}")
        when {
          book?.isCached == true -> CACHED
          bookId in activeDownloadIDs -> CACHING
          else -> NOT_CACHED
        }
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

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
     * expression the only one that compiles.
     */
    val isBookInViewPlaying: StateFlow<Boolean> =
      combineDistinct(
        isBookInViewActive,
        mediaServiceConnection.playbackState,
      ) { isBookActive, currState ->
        isBookActive && currState.isPlaying
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /**
     * How far into the book, and how long it is — the two numbers, not a rendered string.
     *
     * This was `progressString`, which built the literal `h:mm:ss/h:mm:ss` pair §3.1 rule 3 bans
     * The wording now lives in [DetailsProgressText] and the resource, so what crosses
     * this boundary is arithmetic: the screen cannot render a raw duration because it is never
     * given one.
     *
     * **The tracks when there are any, the book row otherwise.** Both carry the same two numbers,
     * and which one has them depends on timing: the book row arrives with the library, while the
     * tracks are fetched lazily the first time a book is opened. Reading only the tracks left the
     * readout **blank on every book the user had not opened before** — the common case on this
     * screen, and exactly the state the "not started" wording exists for. Found on the tablet: a
     * book whose row held `duration=540000, progress=54000` rendered nothing at all.
     *
     * Tracks win where both exist, because they are what playback advances; the book row's copy is
     * updated by sync and can lag mid-listen. Zero for both only when neither has loaded, which the
     * formatter renders as blank rather than as a zero-length book.
     */
    private val bookProgress: StateFlow<ProgressLine> =
      combineDistinct(tracks, audiobook) { tracks, book ->
        if (book == null) {
          ProgressLine()
        } else {
          // The tracks' numbers when they have loaded, the book row's otherwise — but the *state*
          // is always resolved from the book, because `viewCount` lives only there and is what
          // makes completion an explicit fact rather than a guess at the position (decision-16).
          val progress = if (tracks.isNotEmpty()) tracks.getProgress().millis else book.progress
          val duration = if (tracks.isNotEmpty()) tracks.getDuration() else book.duration
          ProgressLine(
            state = book.copy(progress = progress, duration = duration).progressState(),
            progressMillis = progress,
            durationMillis = duration,
          )
        }
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProgressLine())

    /**
     * The percentage, derived from **the same two numbers** the readout beside it uses.
     *
     * It read the tracks directly, which was a second independent source for one line of UI. On a
     * book whose tracks had not been fetched that produced a visible contradiction on the tablet:
     * `7m left` next to `0%`, the left half falling back to the book row while the right half saw
     * an empty track list. Deriving both from [bookProgress] makes that state unrepresentable
     * rather than merely fixed.
     */
    val progressPercentageString: StateFlow<String> =
      bookProgress
        .map { line ->
          if (line.durationMillis <= 0L) {
            "0%"
          } else {
            "${((line.progressMillis / line.durationMillis.toDouble()) * 100).roundToInt()}%"
          }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), "0%")

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
      viewModelScope.launch(dispatchers.io) {
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
        // drowned the log when diagnosing the seek churn.

        // See the same fix in CurrentlyPlayingViewModel: the hand-rolled walk this replaces mixed
        // relative and absolute chapter offsets and resolved the wrong chapter.
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
            loadBookDetails(bookId)
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
            // The title comes from the loaded book, not from a navigation argument.
            //
            // It used to read `ARG_AUDIOBOOK_TITLE` out of `SavedStateHandle` — a key **nothing
            // ever wrote**. The nav graph put only the id into the route, so in every production
            // build this was the empty string and the download notification was titled with
            // nothing; only two tests, which seeded the handle by hand, ever saw a real value.
            // The line directly above already reads the title from `audiobook.value`, which is
            // where it actually lives.
            cachedFileManager.downloadTracks(bookId, audiobook.value?.title.orEmpty())
          }
        }
        CACHED -> {
          Timber.i("Already cached. Uncache?")
          promptUserToUncache()
        }
        CACHING -> {
          Timber.i("Cancelling download: $bookId")
          cachedFileManager.cancelGroup(bookId)
        }
        // Null until both of `cacheStatus`'s sources have emitted. That is "not known yet", not an
        // error — throwing here crashed a main-screen control. The Fragment also keeps the
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
        cachedFileManager.deleteCachedBook(bookId)
      }
    }

    fun pausePlayButtonClicked() {
      if (!plexConfig.isConnected.value && audiobook.value?.isCached == false) {
        showUserMessage(FormattableString.from(R.string.cannot_play_media_no_server))
        return
      }

      val pausePlayAction = {
        pausePlay(
          bookId = bookId,
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
          // offset by the service. One conversion, one home.
          val inTrackOffset =
            tracks.value.let { loaded -> inTrackOffsetOf(bookStartTimeOffset, trackId, loaded) }
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
            trackRepository.markTracksInBookAsWatched(bookId)
            bookRepository.setWatched(bookId)
            R.string.marked_as_played
          } catch (t: Throwable) {
            Timber.e(t, "Failed to mark book $bookId as played")
            R.string.mark_as_played_failed
          }
        showToast(message)
      }
    }

    private fun setAudiobookUnwatched() {
      Timber.i("Marking audiobook as unwatched")
      viewModelScope.launch {
        // The track half now talks to the server, so it can fail. Reporting success before
        // knowing the outcome would tell the user a book was repaired when it was not — and an
        // uncaught throw here would skip `setUnwatched` entirely, leaving the two halves disagreeing,
        // which is the split this pairing exists to prevent.
        val message =
          try {
            // Mirrors setAudiobookWatched: both halves, or the tracks keep a state the book does not
            // and which one shows depends on the order things ran in.
            trackRepository.markTracksInBookAsUnwatched(bookId)
            bookRepository.setUnwatched(bookId)
            R.string.marked_as_unplayed
          } catch (t: Throwable) {
            Timber.e(t, "Failed to mark book $bookId as unplayed")
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

    // ---- the aggregated header state ----

    private val bookHeader: StateFlow<BookHeader> =
      combineDistinct(audiobook, plexConfig.isConnected) { book, connected ->
        BookHeader(
          title = book?.title.orEmpty(),
          author = book?.author.orEmpty(),
          thumb = book?.thumb,
          narrator = book?.let { BookMetadataLines.narrator(it) },
          series = book?.let { BookMetadataLines.series(it) },
          serverConnected = connected,
        )
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BookHeader())

    /**
     * The download control, as one exhaustive state.
     *
     * Replaced four flows — `cacheStatus`, `cacheIconDrawable`, `cacheContentDescription` and
     * `cacheIconTint` — each a `map` over the same source with its own `null ->` branch meaning
     * "not resolved yet". The other three are **deleted**: nothing read them once the
     * screen rendered from this, and `CacheLabelPairingTest` — which checked the icon and its
     * spoken label branched on the same states by parsing this file's *source text* — went with
     * them. One sealed type means the compiler enforces what that scan approximated.
     */
    private val downloadState: StateFlow<DownloadState> =
      cacheStatus
        .map { status ->
          when (status) {
            CacheStatus.CACHED -> DownloadState.Cached
            CacheStatus.CACHING -> DownloadState.Caching
            CacheStatus.NOT_CACHED -> DownloadState.NotCached
            null -> DownloadState.Unknown
          }
        }.stateIn(
          viewModelScope,
          SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
          DownloadState.Unknown,
        )

    private val playbackState: StateFlow<PlaybackState> =
      combineDistinct(
        isBookInViewPlaying,
        isAudioLoading,
        isWatchedIcon,
        forceSyncInProgress,
      ) { playing, loading, watched, syncing ->
        PlaybackState(playing, loading, watched, syncing)
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PlaybackState())

    private val summaryState: StateFlow<SummaryState> =
      combineDistinct(audiobook, showSummary, isExpanded, summaryLinesShown) {
          book, shown, expanded, lines ->
        SummaryState(
          text = book?.summary.orEmpty(),
          isShown = shown,
          isExpanded = expanded,
          linesShown = lines,
        )
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SummaryState())

    private val progressLine: StateFlow<ProgressLine> =
      combineDistinct(bookProgress, progressPercentageString) { progress, percentage ->
        progress.copy(percentage = percentage)
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProgressLine())

    /**
     * Everything the header renders.
     *
     * The Fragment collected 17 flows and made twelve independent `isVisible` decisions from them,
     * each on its own boolean or enum comparison, with nothing stopping two being true at once.
     * Grouped here by what changes together, so a progress tick does not recompose the artwork.
     */
    val uiState: StateFlow<DetailsUiState> =
      combineDistinct(
        combineDistinct(bookHeader, progressLine) { header, progress -> header to progress },
        downloadState,
        combineDistinct(playbackState, summaryState) { playback, summary -> playback to summary },
        combineDistinct(serverConnection, isLoadingTracks) { conn, loading -> conn to loading },
      ) { headerProgress, download, playbackSummary, connLoading ->
        DetailsUiState(
          book = headerProgress.first,
          progress = headerProgress.second,
          download = download,
          playback = playbackSummary.first,
          summary = playbackSummary.second,
          connection = connLoading.first,
          isLoadingTracks = connLoading.second,
        )
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DetailsUiState())

    /** How Circuit's presenter factory builds this, passing the id from the screen key. */
    @AssistedFactory
    interface Factory {
      fun create(bookId: String): AudiobookDetailsViewModel
    }
  }
