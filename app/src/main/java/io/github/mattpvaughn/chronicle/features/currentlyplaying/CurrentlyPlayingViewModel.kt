package io.github.mattpvaughn.chronicle.features.currentlyplaying

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.Toast
import androidx.lifecycle.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.WorkManager
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.application.MILLIS_PER_SECOND
import io.github.mattpvaughn.chronicle.application.SECONDS_PER_MINUTE
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.*
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.player.*
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_SEEK_TO_TRACK_WITH_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.KEY_START_TIME_TRACK_OFFSET
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.USE_SAVED_TRACK_PROGRESS
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_ACTION
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_DURATION_MILLIS
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.SleepTimerAction
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.SleepTimerAction.*
import io.github.mattpvaughn.chronicle.util.*
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.*
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState.Companion.EMPTY_BOTTOM_CHOOSER
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@ExperimentalCoroutinesApi
class CurrentlyPlayingViewModel(
  private val bookRepository: IBookRepository,
  private val trackRepository: ITrackRepository,
  private val localBroadcastManager: LocalBroadcastManager,
  private val mediaServiceConnection: MediaServiceConnection,
  private val prefsRepo: PrefsRepo,
  private val plexConfig: PlexConfig,
  private val currentlyPlaying: CurrentlyPlaying,
  private val workManager: WorkManager,
  sharedPrefs: SharedPreferences,
) : ViewModel() {
  @Suppress("UNCHECKED_CAST")
  class Factory
    @Inject
    constructor(
      private val bookRepository: IBookRepository,
      private val trackRepository: ITrackRepository,
      private val localBroadcastManager: LocalBroadcastManager,
      private val mediaServiceConnection: MediaServiceConnection,
      private val prefsRepo: PrefsRepo,
      private val plexConfig: PlexConfig,
      private val currentlyPlaying: CurrentlyPlaying,
      private val workManager: WorkManager,
      private val sharedPrefs: SharedPreferences,
    ) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CurrentlyPlayingViewModel::class.java)) {
          return CurrentlyPlayingViewModel(
            bookRepository,
            trackRepository,
            localBroadcastManager,
            mediaServiceConnection,
            prefsRepo,
            plexConfig,
            currentlyPlaying,
            workManager,
            sharedPrefs,
          ) as T
        } else {
          throw IllegalArgumentException("Incorrect class type provided")
        }
      }
    }

  private var _showUserMessage = MutableLiveData<Event<String>>()
  val showUserMessage: LiveData<Event<String>>
    get() = _showUserMessage

  /**
   * True when a progress report has permanently failed, so the position on the server is
   * behind the device's.
   *
   * Failure-only by design — see [hasFailedSync] for why a synced/pending indicator
   * cannot be built honestly from WorkManager's states here.
   */
  val hasFailedProgressSync: LiveData<Boolean> =
    workManager
      .getWorkInfosByTagLiveData(ProgressUpdater.PROGRESS_SYNC_WORK_TAG)
      .map { hasFailedSync(it) }

  private var audiobookId = MutableLiveData(EMPTY_AUDIOBOOK.id)

  val audiobook: LiveData<Audiobook?> =
    audiobookId.switchMap { id ->
      if (id == EMPTY_AUDIOBOOK.id) {
        emptyAudiobook
      } else {
        bookRepository.getAudiobook(id)
      }
    }

  private val emptyAudiobook = MutableLiveData(EMPTY_AUDIOBOOK)
  private val emptyTrackList = MutableLiveData<List<MediaItemTrack>>(emptyList())

  // TODO: expose combined track/chapter bits in ViewModel as "windowSomething" instead of in xml
  val tracks: LiveData<List<MediaItemTrack>> =
    audiobookId.switchMap { id ->
      if (id == EMPTY_AUDIOBOOK.id) {
        emptyTrackList
      } else {
        trackRepository.getTracksForAudiobook(id)
      }
    }

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
      if (_audiobook?.chapters?.isNotEmpty() == true) {
        // We would really prefer this because it doesn't have to be computed
        _audiobook.chapters
      } else {
        _tracksAsChapters ?: emptyList()
      }
    }

  val speed =
    FloatPreferenceLiveData(
      PrefsRepo.KEY_PLAYBACK_SPEED,
      PLAYBACK_SPEED_DEFAULT,
      sharedPrefs,
    ).map {
      Timber.i("Speed: %.2f", it)
      return@map it.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)
    }

  val playbackSpeedString =
    speed.map { speed ->
      return@map String.format("%.2f", speed) + "x"
    }

  private var _showModalBottomSheetSpeedChooser = MutableLiveData<Event<Unit>>()
  val showModalBottomSheetSpeedChooser: LiveData<Event<Unit>>
    get() = _showModalBottomSheetSpeedChooser

  val activeTrackId: LiveData<String> =
    mediaServiceConnection.nowPlaying.map { metadata ->
      metadata.takeIf { !it.id.isNullOrEmpty() }?.id ?: TRACK_NOT_FOUND
    }

  val currentTrack: LiveData<MediaItemTrack> =
    currentlyPlaying.track.asLiveData(viewModelScope.coroutineContext)

  // cachedChapter and activeChapter are declared here, above their first use in chapterDuration
  // below, and not further down the file. Kotlin initialises properties in declaration order, so a
  // `get()` alias that resolves to a property declared later reads null during construction — which
  // crashed MainActivity on launch with "Parameter specified as non-null is null" from
  // Transformations.map. Nothing in the unit suite constructs this ViewModel, so only the app
  // caught it (cu-87).
  private val cachedChapter =
    DoubleLiveData(
      chapters,
      tracks,
    ) { _chapters: List<Chapter>?, _tracks: List<MediaItemTrack>? ->
      // Deliberately not logged. These lines serialised the entire chapter list — 40+ objects —
      // several times a second on a real book, which is a measurable cost in a debug build and
      // drowned the log when diagnosing the seek churn (cu-93).

      // `chapterAtBookProgress`, not a hand-rolled walk. The loop this replaces subtracted each
      // chapter's *duration* from a running offset while comparing against the **absolute**
      // `endTimeOffset` — mixing relative and absolute coordinates, the same defect as cu-13 and
      // cu-49. At 28,359,976ms in a real book it picked Chapter 12 (ending 15,803,900) instead of
      // Chapter 20, so a cold start showed the wrong chapter until playback corrected it (cu-73).
      //
      // The helper also sorts, which matters: the list arrives from the DB and the network in no
      // guaranteed order, and the old walk trusted the given order.
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
    }.distinctUntilChanged().asLiveData(viewModelScope.coroutineContext)

  /**
   * The chapter the book is at, for the timeline readout.
   *
   * Deliberately the *same* value as [activeChapter], which the chapter list highlights. These used
   * to differ: this was the raw `currentlyPlaying.chapter`, which starts at `EMPTY_CHAPTER` and is
   * only recomputed by `CurrentlyPlayingSingleton.update()` — called from playback callbacks only.
   * So on returning to the screen without playing, the timeline read from a stale or empty chapter
   * while the list highlighted the one derived from saved progress, and the two disagreed until
   * playback started (cu-87).
   */
  val currentChapter: LiveData<Chapter> get() = activeChapter

  val chapterProgress =
    currentlyPlaying.chapter.combine(
      currentlyPlaying.track,
    ) { chapter: Chapter, track: MediaItemTrack ->
      track.progress - chapter.startTimeOffset
    }.asLiveData(viewModelScope.coroutineContext)

  val chapterProgressString =
    chapterProgress.map { progress ->
      return@map DateUtils.formatElapsedTime(
        StringBuilder(),
        progress / 1000,
      )
    }

  val chapterProgressForSlider =
    currentlyPlaying.chapter.combine(
      currentlyPlaying.track,
    ) { chapter: Chapter, track: MediaItemTrack ->
      track.progress - chapter.startTimeOffset
    }.filter { !isSliding }
      // distinctUntilChanged, because `currentlyPlaying` publishes book, track *and* chapter on
      // every progress tick and each fans out through this combine. The device logged 228
      // recomputations in one minute — four a second for a value that changes once a second — and
      // every one of them wrote to the slider. That churn is what made seeking feel unstable
      // however well the in-flight guard worked (cu-93).
      .distinctUntilChanged()
      .asLiveData(viewModelScope.coroutineContext)

  val trackProgressForSlider =
    currentlyPlaying.track
      .filter { !isSliding }
      .map { it.progress }
      .distinctUntilChanged()
      .asLiveData(viewModelScope.coroutineContext)

  val chapterDuration =
    currentChapter.map {
      return@map it.endTimeOffset - it.startTimeOffset
    }

  val chapterDurationString =
    chapterDuration.map { duration ->
      return@map DateUtils.formatElapsedTime(
        StringBuilder(),
        duration / 1000,
      )
    }

  /**
   * Suppresses slider writes while the user owns the position.
   *
   * True from touch-down until the seek has actually landed — **not** until touch-up. Releasing it
   * on touch-up left a window of up to one progress tick in which the old position was written
   * back, so the thumb snapped to where it was before jumping forward when the seek completed. The
   * owner described exactly that: *"seeking moves the timeline where I clicked, then back at the
   * previous place, then starts playing and goes back to where I requested"* (cu-93).
   *
   * [awaitSeek] closes it again once the reported position is near the requested one.
   */
  var isSliding = false
    private set

  /**
   * The settle job for the seek currently in flight, so a second seek can cancel the first.
   *
   * Without this, rapid taps released the guard too early: tap one starts waiting for target A,
   * tap two starts waiting for B, and whichever *arrives* first clears `isSliding` for both. The
   * A-waiter then also fires and clears the guard while B is still travelling, which is the
   * snap-back this guard exists to prevent — just narrower, so it survived the cu-93 fix.
   */
  private var seekSettleJob: Job? = null

  fun onSlideStart() {
    isSliding = true
  }

  /**
   * Holds the guard until playback reports a position near [target], or until [SEEK_SETTLE_TIMEOUT]
   * passes.
   *
   * The timeout matters: a seek that never lands — a dead network on a streamed book — must not
   * freeze the slider forever. Releasing late looks like a brief lag; never releasing looks broken.
   */
  private fun awaitSeek(target: Long) {
    // Only the newest seek may release the guard; see [seekSettleJob].
    seekSettleJob?.cancel()
    seekSettleJob =
      viewModelScope.launch {
        withTimeoutOrNull(SEEK_SETTLE_TIMEOUT) {
          currentlyPlaying.track.first { track ->
            abs(track.progress - target) < SEEK_SETTLE_TOLERANCE
          }
        }
        isSliding = false
      }
  }

  private var _isSleepTimerActive = MutableLiveData(false)
  val isSleepTimerActive: LiveData<Boolean>
    get() = _isSleepTimerActive

  private var sleepTimerTimeRemaining = MutableLiveData(0L)

  val sleepTimerTimeRemainingString =
    sleepTimerTimeRemaining.map {
      return@map DateUtils.formatElapsedTime(StringBuilder(), it / 1000)
    }

  /**
   * True while the player is buffering or connecting, i.e. play was asked for but no audio is
   * flowing yet.
   *
   * Mirrors `AudiobookDetailsViewModel.isAudioLoading` rather than inventing a second rule — the
   * two screens showing different things for the same state is what cu-94 was about. The player had
   * no such state at all: pressing play on a streamed book looked identical to pressing play on a
   * stalled one, with only the play/pause icon to go on (cu-95).
   *
   * Note this covers the *initial* buffer. Media3 reports a mid-book stall as STATE_PLAYING once
   * playback has started, so a starved stream partway through still shows as normal playback; that
   * is recorded in cu-95 as a separate question.
   */
  val isAudioLoading: LiveData<Boolean> =
    mediaServiceConnection.playbackState.map { state ->
      state.state == PlaybackStateCompat.STATE_BUFFERING ||
        state.state == PlaybackStateCompat.STATE_CONNECTING
    }

  val isPlaying: LiveData<Boolean> =
    mediaServiceConnection.playbackState.map { state ->
      return@map state.isPlaying
    }

  val trackProgress =
    currentTrack.map { track ->
      return@map DateUtils.formatElapsedTime(
        StringBuilder(),
        track.progress / 1000,
      )
    }

  val trackDuration =
    currentTrack.map { track ->
      return@map DateUtils.formatElapsedTime(StringBuilder(), track.duration / 1000)
    }

  val progressString =
    tracks.map { tracks: List<MediaItemTrack> ->
      if (tracks.isEmpty()) {
        return@map "0:00/0:00"
      }
      val progressStr =
        DateUtils.formatElapsedTime(
          StringBuilder(),
          tracks.getProgress() / 1000L,
        )
      val durationStr =
        DateUtils.formatElapsedTime(
          StringBuilder(),
          tracks.getDuration() / 1000L,
        )
      return@map "$progressStr/$durationStr"
    }

  /**
   * The book's completion percentage, derived from the **same** source as the timeline.
   *
   * It used to read `tracks` straight from Room, which the progress loop writes every second,
   * while the timeline reads `currentlyPlaying.track`, refreshed only by playback callbacks. The
   * database write lands first, so the percentage visibly moved before the timeline did — two
   * readouts of one fact, disagreeing (cu-94). Same split cu-87 fixed for the chapter list.
   *
   * The track list still supplies the *total* duration, which does not change during playback; only
   * the position now comes from `currentlyPlaying`.
   */
  val progressPercentageString =
    DoubleLiveData(
      tracks,
      currentlyPlaying.track.asLiveData(viewModelScope.coroutineContext),
    ) { _tracks: List<MediaItemTrack>?, playing: MediaItemTrack? ->
      val total = _tracks?.getDuration() ?: 0L
      if (_tracks.isNullOrEmpty() || total == 0L || playing == null) {
        return@DoubleLiveData "0%"
      }
      // Position of the playing track's start, plus how far into it playback has reached.
      val before = _tracks.sorted().takeWhile { it.id != playing.id }.sumOf { it.duration }
      val percent = (((before + playing.progress) / total.toDouble()) * 100).roundToInt()
      return@DoubleLiveData "${percent.coerceIn(0, 100)}%"
    }

  private var _isLoadingTracks = MutableLiveData(false)
  val isLoadingTracks: LiveData<Boolean>
    get() = _isLoadingTracks

  private var _bottomChooserState = MutableLiveData(EMPTY_BOTTOM_CHOOSER)
  val bottomChooserState: LiveData<BottomChooserState>
    get() = _bottomChooserState

  private var _sleepTimerChooserState = MutableLiveData(EMPTY_BOTTOM_CHOOSER)
  val sleepTimerChooserState: LiveData<BottomChooserState>
    get() = _sleepTimerChooserState

  private var _jumpForwardsIcon = MutableLiveData(makeJumpForwardsIcon())
  val jumpForwardsIcon: LiveData<Int>
    get() = _jumpForwardsIcon

  private var _jumpBackwardsIcon = MutableLiveData(makeJumpBackwardsIcon())
  val jumpBackwardsIcon: LiveData<Int>
    get() = _jumpBackwardsIcon

  private val prefsChangeListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      when (key) {
        PrefsRepo.KEY_JUMP_FORWARD_SECONDS -> _jumpForwardsIcon.value = makeJumpForwardsIcon()
        PrefsRepo.KEY_JUMP_BACKWARD_SECONDS -> _jumpBackwardsIcon.value = makeJumpBackwardsIcon()
      }
    }

  private val networkObserver =
    Observer<Boolean> { isConnected ->
      if (isConnected) {
        audiobookId.value?.let {
          refreshTracks(it)
        }
      }
    }

  private val playbackObserver =
    Observer<MediaMetadataCompat> { metadata ->
      if (metadata.id?.isEmpty() == false) {
        setAudiobook(metadata.id!!)
      }
    }

  private fun setAudiobook(trackId: String) {
    val previousAudiobookId = audiobook.value?.id ?: NO_AUDIOBOOK_FOUND_ID
    viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
      // only update [audiobookId] when we see a new audiobook
      val potentiallyNewAudiobookId = trackRepository.getBookIdForTrack(trackId)
      if (potentiallyNewAudiobookId != previousAudiobookId) {
        audiobookId.postValue(potentiallyNewAudiobookId)
      }
    }
  }

  init {
    mediaServiceConnection.nowPlaying.observeForever(playbackObserver)
    plexConfig.isConnected.observeForever(networkObserver)

    // Listen for changes in SharedPreferences that could effect playback
    prefsRepo.registerPrefsListener(prefsChangeListener)
  }

  private fun refreshTracks(bookId: String) {
    if (bookId == NO_AUDIOBOOK_FOUND_ID) {
      return
    }
    viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
      try {
        // Only replace track view w/ loading view if we have no tracks
        if (tracks.value?.size == null) {
          _isLoadingTracks.value = true
        }
        val tracks = trackRepository.loadTracksForAudiobook(bookId)
        if (tracks.isOk) {
          bookRepository.updateTrackData(
            bookId,
            tracks.value.getProgress(),
            tracks.value.getDuration(),
            tracks.value.size,
          )
          audiobook.value?.let {
            bookRepository.syncAudiobook(it, tracks.value)
          }
        }
        _isLoadingTracks.value = false
      } catch (e: Throwable) {
        Timber.e(e, "Failed to load tracks for audiobook $bookId")
        _isLoadingTracks.value = false
      }
    }
  }

  fun play() {
    if (mediaServiceConnection.isConnected.value == true) {
      if (audiobook.value == null) {
        Timber.e("Tried to play null audiobook!")
        _showUserMessage.postEvent(
          "Audiobook is null. Try restarting the app and trying again",
        )
        return
      }
      pausePlay(
        bookId = audiobook.value!!.id,
        // Neither a track id nor an offset is supplied: resume where the book left off.
        // This used to pass ACTIVE_TRACK — a *track id* sentinel — as startTimeOffset,
        // which is not a time at all.
        forcePlay = false,
      )
    }
  }

  private fun pausePlay(
    bookId: String,
    startTimeOffset: Long = USE_SAVED_TRACK_PROGRESS,
    forcePlay: Boolean = false,
    trackId: String? = null,
  ) {
    val transportControls = mediaServiceConnection.transportControls

    val extras =
      Bundle().apply {
        putLong(KEY_START_TIME_TRACK_OFFSET, startTimeOffset)
        // Only written when a specific track was asked for; absence means "resume active".
        trackId?.let { putString(KEY_SEEK_TO_TRACK_WITH_ID, it) }
      }
    if (transportControls != null) {
      mediaServiceConnection.playbackState.value?.let { playbackState ->
        when {
          forcePlay -> transportControls.playFromMediaId(bookId, extras)
          playbackState.state == STATE_PAUSED -> transportControls.play()
          playbackState.isPlaying -> transportControls.pause()
          else -> {
          } // do nothing?
        }
      }
    }
  }

  fun skipToNext() {
    skipToChapter(SKIP_TO_NEXT, forward = true)
  }

  fun skipToPrevious() {
    skipToChapter(SKIP_TO_PREVIOUS, forward = false)
  }

  private fun skipToChapter(
    action: PlaybackStateCompat.CustomAction,
    forward: Boolean,
  ) {
    val transportControls = mediaServiceConnection.transportControls
    mediaServiceConnection.let { connection ->
      if (connection.nowPlaying.value != NOTHING_PLAYING) {
        // Service will be alive, so we can let it handle the action
        Timber.i("Seeking!")
        // Predict where the service will land and hold the slider there. The chapter buttons
        // showed the same snap-back as the slider: the readout moved to the new chapter, reverted
        // for a tick, then moved again once the seek completed (cu-93).
        val chapters = currentlyPlaying.book.value.chapters
        val here = chapters.indexOf(currentlyPlaying.chapter.value)
        val target =
          if (forward) {
            chapters.getOrNull(here + 1)?.startTimeOffset
          } else {
            // Matches the service's rule: past the threshold, restart the current chapter.
            val current = currentlyPlaying.chapter.value
            val intoChapter = currentlyPlaying.track.value.progress - current.startTimeOffset
            if (intoChapter < SKIP_TO_PREVIOUS_CHAPTER_THRESHOLD_MILLIS) {
              chapters.getOrNull(here - 1)?.startTimeOffset
            } else {
              current.startTimeOffset
            }
          }
        transportControls?.sendCustomAction(action, null)
        target?.let { awaitSeek(it) }
      } else {
        val currentChapterIndex =
          currentlyPlaying.book.value.chapters.indexOf(
            currentlyPlaying.chapter.value,
          )
        var skipToChapterIndex: Int
        if (forward) {
          skipToChapterIndex = currentChapterIndex + 1
          if (skipToChapterIndex < currentlyPlaying.book.value.chapters.size) {
            val skipToChapter = currentlyPlaying.book.value.chapters[skipToChapterIndex]
            jumpToChapter(
              skipToChapter.startTimeOffset,
              currentlyPlaying.track.value.id,
              hasUserConfirmation = true,
            )
          } else {
            val toast =
              Toast.makeText(
                Injector.get().applicationContext(),
                R.string.skip_forwards_reached_last_chapter,
                Toast.LENGTH_LONG,
              )
            toast.setGravity(Gravity.BOTTOM, 0, 200)
            toast.show()
          }
        } else {
          skipToChapterIndex = currentChapterIndex - 1
          if (skipToChapterIndex < 0) skipToChapterIndex = 0
          val skipToChapter = currentlyPlaying.book.value.chapters[skipToChapterIndex]
          jumpToChapter(
            skipToChapter.startTimeOffset,
            currentlyPlaying.track.value.id,
            hasUserConfirmation = true,
          )
        }
      }
    }
  }

  fun makeJumpForwardsIcon(): Int {
    return when (prefsRepo.jumpForwardSeconds) {
      10L -> R.drawable.ic_forward_10_white
      15L -> R.drawable.ic_forward_15_white
      20L -> R.drawable.ic_forward_20_white
      30L -> R.drawable.ic_forward_30_white
      60L -> R.drawable.ic_forward_60_white
      90L -> R.drawable.ic_forward_90_white
      else -> R.drawable.ic_forward_30_white
    }
  }

  fun makeJumpBackwardsIcon(): Int {
    return when (prefsRepo.jumpBackwardSeconds) {
      10L -> R.drawable.ic_replay_10_white
      15L -> R.drawable.ic_replay_15_white
      20L -> R.drawable.ic_replay_20_white
      30L -> R.drawable.ic_replay_30_white
      60L -> R.drawable.ic_replay_60_white
      90L -> R.drawable.ic_replay_90_white
      else -> R.drawable.ic_replay_10_white
    }
  }

  fun skipForwards() {
    seekRelative(makeSkipForward(prefsRepo), prefsRepo.jumpForwardSeconds * MILLIS_PER_SECOND)
  }

  fun skipBackwards() {
    seekRelative(
      makeSkipBackward(prefsRepo),
      prefsRepo.jumpBackwardSeconds * MILLIS_PER_SECOND * -1,
    )
  }

  private fun seekRelative(
    action: PlaybackStateCompat.CustomAction,
    offset: Long,
  ) {
    val transportControls = mediaServiceConnection.transportControls
    mediaServiceConnection.let { connection ->
      if (connection.nowPlaying.value != NOTHING_PLAYING) {
        // Service will be alive, so we can let it handle the action
        Timber.i("Seeking!")
        transportControls?.sendCustomAction(action, null)
      } else {
        Timber.i("Updating DB progress!")
        // Service is not alive, so update track repo directly
        tracks.observeOnce { _tracks ->
          viewModelScope.launch(Injector.get().unhandledExceptionHandler()) {
            // don't bother seeking if there aren't any files
            if (_tracks.isEmpty()) {
              return@launch
            }
            val manager = TrackListStateManager()
            manager.trackList = _tracks
            manager.seekToActiveTrack()
            manager.seekByRelative(offset)
            val updatedTrack = _tracks[manager.currentTrackIndex]
            trackRepository.updateTrackProgress(
              manager.currentBookPosition,
              updatedTrack.id,
              System.currentTimeMillis(),
            )
          }
        }
      }
    }
  }

  /** Jumps to a given track with [MediaItemTrack.id] == [trackId] */
  fun jumpToChapter(
    startTimeOffset: Long = 0,
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
                FormattableString.yes ->
                  jumpToChapter(
                    startTimeOffset,
                    trackId,
                    true,
                  )
                FormattableString.no -> Unit
                else -> throw NoWhenBranchMatchedException()
              }
              hideOptionsMenu()
            }
          },
      )
      return
    }

    val jumpToChapterAction = {
      audiobook.value?.let { book ->
        pausePlay(
          book.id,
          startTimeOffset = startTimeOffset,
          trackId = trackId,
          forcePlay = true,
        )
      }
    }
    if (mediaServiceConnection.isConnected.value != true) {
      mediaServiceConnection.connect(onConnected = jumpToChapterAction)
    } else {
      jumpToChapterAction()
    }
  }

  fun showSleepTimerOptions() {
    val title =
      if (isSleepTimerActive.value != true) {
        FormattableString.from(R.string.sleep_timer)
      } else {
        FormattableString.ResourceString(
          R.string.sleep_timer_active_title,
          placeHolderStrings = listOf(sleepTimerTimeRemainingString.value ?: "<Error>"),
        )
      }
    val options =
      if (isSleepTimerActive.value == true) {
        listOf(
          FormattableString.from(R.string.sleep_timer_append),
          FormattableString.from(R.string.sleep_timer_duration_end_of_chapter),
          FormattableString.from(R.string.cancel),
        )
      } else {
        listOf(
          FormattableString.from(R.string.sleep_timer_duration_5_minutes),
          FormattableString.from(R.string.sleep_timer_duration_15_minutes),
          FormattableString.from(R.string.sleep_timer_duration_30_minutes),
          FormattableString.from(R.string.sleep_timer_duration_40_minutes),
          FormattableString.from(R.string.sleep_timer_duration_60_minutes),
          FormattableString.from(R.string.sleep_timer_duration_90_minutes),
          FormattableString.from(R.string.sleep_timer_duration_120_minutes),
          FormattableString.from(R.string.sleep_timer_duration_end_of_chapter),
        )
      }
    val listener =
      object : BottomChooserListener {
        override fun onItemClicked(formattableString: FormattableString) {
          check(formattableString is FormattableString.ResourceString)

          val actionPair: Pair<SleepTimerAction, Long> =
            when (formattableString.stringRes) {
              R.string.sleep_timer_duration_5_minutes -> {
                val duration = 5 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                BEGIN to duration
              }
              R.string.sleep_timer_duration_15_minutes -> {
                val duration = 15 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                BEGIN to duration
              }
              R.string.sleep_timer_duration_30_minutes -> {
                val duration = 30 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                BEGIN to duration
              }
              R.string.sleep_timer_duration_40_minutes -> {
                val duration = 40 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                BEGIN to duration
              }
              R.string.sleep_timer_duration_60_minutes -> {
                val duration = 60 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                BEGIN to duration
              }
              R.string.sleep_timer_duration_90_minutes -> {
                val duration = 90 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                BEGIN to duration
              }
              R.string.sleep_timer_duration_120_minutes -> {
                val duration = 120 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                BEGIN to duration
              }
              R.string.sleep_timer_duration_end_of_chapter -> {
                val duration =
                  (
                    (
                      (chapterDuration.value ?: 0L) - (
                        chapterProgress.value
                          ?: 0L
                      )
                    ) / prefsRepo.playbackSpeed
                  ).toLong()
                BEGIN to duration
              }
              R.string.sleep_timer_append -> {
                val additionalTime = 5 * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
                EXTEND to additionalTime
              }
              R.string.cancel -> {
                setSleepTimerTitle(FormattableString.from(R.string.sleep_timer))
                CANCEL to 0L
              }
              else -> throw NoWhenBranchMatchedException(
                "Unknown duration picked for sleep timer",
              )
            }
          hideSleepTimerChooser()
          val sleepTimerIntent =
            Intent(SleepTimer.ACTION_SLEEP_TIMER_CHANGE).apply {
              putExtra(ARG_SLEEP_TIMER_ACTION, actionPair.first)
              putExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, actionPair.second)
            }
          localBroadcastManager.sendBroadcast(sleepTimerIntent)
        }

        override fun onChooserClosed(wasBackgroundClicked: Boolean) {
          if (wasBackgroundClicked) {
            hideSleepTimerChooser()
          }
        }
      }

    _sleepTimerChooserState.postValue(
      BottomChooserState(
        title = title,
        options = options,
        listener = listener,
        shouldShow = true,
      ),
    )
  }

  fun showPlaybackSpeedChooser() {
    _showModalBottomSheetSpeedChooser.postEvent(Unit)
  }

  private fun hideSleepTimerChooser() {
    _sleepTimerChooserState.postValue(
      _sleepTimerChooserState.value?.copy(shouldShow = false) ?: EMPTY_BOTTOM_CHOOSER,
    )
  }

  private fun hideOptionsMenu() {
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

  val onUpdateSleepTimer =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent == null || !intent.hasExtra(ARG_SLEEP_TIMER_DURATION_MILLIS)) {
          return
        }
        val timeLeftMillis = intent.getLongExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, 0L)
        val shouldSleepSleepTimerBeActive = timeLeftMillis > 0L
        _isSleepTimerActive.postValue(shouldSleepSleepTimerBeActive)
        sleepTimerTimeRemaining.value = timeLeftMillis

        if (shouldSleepSleepTimerBeActive) {
          setSleepTimerTitle(
            FormattableString.ResourceString(
              stringRes = R.string.sleep_timer_active_title,
              placeHolderStrings =
                listOf(
                  sleepTimerTimeRemainingString.value ?: "<Error>",
                ),
            ),
          )
        } else {
          setSleepTimerTitle(FormattableString.from(R.string.sleep_timer))
        }
      }
    }

  private fun setSleepTimerTitle(formattableString: FormattableString) {
    _sleepTimerChooserState.postValue(
      _sleepTimerChooserState.value?.copy(title = formattableString) ?: EMPTY_BOTTOM_CHOOSER,
    )
  }

  override fun onCleared() {
    mediaServiceConnection.nowPlaying.removeObserver(playbackObserver)
    prefsRepo.unregisterPrefsListener(prefsChangeListener)
    plexConfig.isConnected.removeObserver(networkObserver)
    super.onCleared()
  }

  fun seekTo(percentProgress: Double) {
    val id: String = (audiobookId.value ?: TRACK_NOT_FOUND).toString()
    if (currentChapter.value == EMPTY_CHAPTER) {
      // Seeking by track length
      currentTrack.value?.let { curr ->
        val extras =
          Bundle().apply {
            putString(KEY_SEEK_TO_TRACK_WITH_ID, curr.id)
          }
        mediaServiceConnection.transportControls?.playFromMediaId(id, extras)
        // Same hold as the chapter branch; without it the guard is released while the restart is
        // still in flight.
        awaitSeek(curr.progress)
      }
    } else {
      // Seeking by chapter length
      currentChapter.value?.let { chapter ->
        // seek relative to start of current track
        val chapterDuration = chapter.endTimeOffset - chapter.startTimeOffset
        val offset = chapter.startTimeOffset + (percentProgress * chapterDuration).toLong()
        mediaServiceConnection.transportControls?.seekTo(offset)
        // Keep the slider on the requested position until playback reports it. Without this the
        // next progress tick overwrites the thumb with the pre-seek position (cu-93).
        awaitSeek(offset)
      }
    }
  }

  companion object {
    /** Minimal and maximal allowed playback speed. */
    const val PLAYBACK_SPEED_MIN = 0.5f
    const val PLAYBACK_SPEED_DEFAULT = 1.0f
    const val PLAYBACK_SPEED_MAX = 3.0f

    /** How close a reported position must be to the requested one to count as "landed". */
    private const val SEEK_SETTLE_TOLERANCE = 2_000L

    /** Never hold the slider hostage to a seek that will not complete. */
    private const val SEEK_SETTLE_TIMEOUT = 5_000L

    /**
     * Mirrors `SKIP_TO_PREVIOUS_CHAPTER_THRESHOLD_SECONDS` in the service: within this far into a
     * chapter, "previous" means the chapter before; past it, restart the current one. Duplicated
     * deliberately rather than shared, because the two are allowed to diverge — but they should be
     * changed together, so both carry this note.
     */
    private const val SKIP_TO_PREVIOUS_CHAPTER_THRESHOLD_MILLIS = 30_000L
  }
}
