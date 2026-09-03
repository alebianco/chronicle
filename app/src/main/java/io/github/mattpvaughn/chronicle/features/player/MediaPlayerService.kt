package io.github.mattpvaughn.chronicle.features.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_MEDIA_STOP
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.ChronicleApplication
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.getActiveTrack
import io.github.mattpvaughn.chronicle.data.model.getProgress
import io.github.mattpvaughn.chronicle.data.model.toMediaItem
import io.github.mattpvaughn.chronicle.data.sources.plex.*
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.*
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_ACTION
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.Companion.ARG_SLEEP_TIMER_DURATION_MILLIS
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.SleepTimerAction
import io.github.mattpvaughn.chronicle.injection.components.DaggerServiceComponent
import io.github.mattpvaughn.chronicle.injection.modules.ServiceModule
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import io.github.mattpvaughn.chronicle.util.PackageValidator
import io.github.mattpvaughn.chronicle.util.ServiceUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** The service responsible for media playback, notification */
@ExperimentalCoroutinesApi
@OptIn(ExperimentalTime::class)
class MediaPlayerService :
  MediaBrowserServiceCompat(),
  ForegroundServiceController,
  ServiceController,
  SleepTimer.SleepTimerBroadcaster {
  val serviceJob: CompletableJob = SupervisorJob()

  // Keeps `Dispatchers.Main` rather than an injected provider (cu-72). `ServiceModule` provides this
  // very scope to the Dagger graph (`fun serviceScope() = service.serviceScope`), so it must exist
  // *before* injection runs — a field initialiser cannot read an injected dispatcher without a
  // circular dependency. Main is also correct here regardless: this scope drives MediaSession and
  // notification updates, which must be on the main thread. The work inside it that does not
  // belong there hops via `withContext(dispatchers.io)`, and those sites *are* injected.
  val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

  @Inject
  lateinit var onMediaChangedCallback: OnMediaChangedCallback

  @Inject
  lateinit var packageValidator: PackageValidator

  @Inject
  lateinit var notificationBuilder: NotificationBuilder

  @Inject
  lateinit var plexConfig: PlexConfig

  @Inject
  lateinit var becomingNoisyReceiver: BecomingNoisyReceiver

  @Inject
  lateinit var mediaSession: MediaSessionCompat

  @Inject
  lateinit var mediaController: MediaControllerCompat

  @Inject
  lateinit var exoPlayer: ExoPlayer

  @Inject
  lateinit var bookRepository: IBookRepository

  @Inject
  lateinit var trackRepository: ITrackRepository

  @Inject
  lateinit var dispatchers: DispatcherProvider

  @Inject
  lateinit var trackListManager: TrackListStateManager

  @Inject
  lateinit var mediaSessionCallback: AudiobookMediaSessionCallback

  @Inject
  lateinit var prefsRepo: PrefsRepo

  @Inject
  lateinit var plexLoginRepo: IPlexLoginRepo

  @Inject
  lateinit var currentlyPlaying: CurrentlyPlaying

  companion object {
    /** Strings used by plex to indicate playback state */
    const val PLEX_STATE_PLAYING = "playing"
    const val PLEX_STATE_STOPPED = "stopped"
    const val PLEX_STATE_PAUSED = "paused"

    /** Strings used to indicate playback errors */
    const val ACTION_PLAYBACK_ERROR = "playback error action intent"
    const val PLAYBACK_ERROR_MESSAGE = "playback error message"

    /**
     * Key indicating playback start time offset relative to the start of the track being
     * played (only use for, m4b chapters, as mp3 durations are generally too imprecise)
     */
    const val KEY_START_TIME_TRACK_OFFSET = "track index bundle 2939829 tubers"

    // Key indicating the ID of the track to begin playback at
    const val KEY_SEEK_TO_TRACK_WITH_ID = "MediaPlayerService.key_seek_to_track_with_id"

    // Value indicating to begin playback at the most recently listened position
    const val USE_SAVED_TRACK_PROGRESS = Long.MIN_VALUE + 22250L

    private const val CHRONICLE_MEDIA_ROOT_ID = "chronicle_media_root_id"
    private const val CHRONICLE_MEDIA_EMPTY_ROOT = "empty root"
    private const val CHRONICLE_MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

    /**
     * Exoplayer back-buffer (millis to keep of playback prior to current location)
     *
     * @see DefaultLoadControl.Builder.setBufferDurationsMs
     */
    val EXOPLAYER_BACK_BUFFER_DURATION_MILLIS: Int = 120.seconds.inWholeMilliseconds.toInt()

    /**
     * Exoplayer min-buffer (the minimum millis of buffer which exo will attempt to keep in
     * memory)
     *
     * @see DefaultLoadControl.Builder.setBufferDurationsMs
     */
    val EXOPLAYER_MIN_BUFFER_DURATION_MILLIS: Int = 10.seconds.inWholeMilliseconds.toInt()

    /**
     * Exoplayer max-buffer (the maximum duration of buffer which Exoplayer will store in memory)
     *
     * @see DefaultLoadControl.Builder.setBufferDurationsMs
     */
    val EXOPLAYER_MAX_BUFFER_DURATION_MILLIS: Int = 360.seconds.inWholeMilliseconds.toInt()
  }

  @Inject
  lateinit var sleepTimer: SleepTimer

  @Inject
  lateinit var progressUpdater: ProgressUpdater

  private fun mediaBrowserCompatStringField(name: String): String? {
    return runCatching { MediaBrowserCompat::class.java.getField(name).get(null) as? String }
      .getOrElse {
        runCatching {
          MediaBrowserCompat.MediaItem::class.java.getField(name).get(null) as? String
        }.getOrNull()
      }
  }

  private fun mediaBrowserCompatIntField(name: String): Int? {
    return runCatching { MediaBrowserCompat::class.java.getField(name).getInt(null) }
      .getOrElse {
        runCatching { MediaBrowserCompat.MediaItem::class.java.getField(name).getInt(null) }.getOrNull()
      }
  }

  @Inject
  lateinit var mediaSource: PlexMediaRepository

  @Inject
  lateinit var localBroadcastManager: LocalBroadcastManager

  var currentPlayer: Player? = null

  private var sessionErrorMessage: String? = null
  private var sessionCustomActions: List<PlaybackStateCompat.CustomAction> = emptyList()
  private val timelineWindow = Timeline.Window()

  /**
   * Fetches the cover art and re-posts the notification with it attached.
   *
   * The second half of the cu-137 split: [NotificationBuilder.buildNotificationWithoutArtwork]
   * satisfies the foreground deadline, this fills in the picture whenever the network gets round
   * to it. Re-posting through `startForeground` with the same id updates the existing notification
   * — the service is already foreground by the time this runs, so this is an update, not a second
   * promotion, and it needs no notification-manager dependency of its own.
   *
   * Failure here is deliberately not fatal: a missing cover is cosmetic, and the notification the
   * user already has stays valid. `getBitmapFromServer` swallows its own network errors, so this
   * guards only against the session going away underneath us.
   */
  private suspend fun postNotificationWithArtwork() {
    val token = mediaSession.sessionToken ?: return
    startForeground(NOW_PLAYING_NOTIFICATION, notificationBuilder.buildNotification(token))
  }

  override fun onCreate() {
    super.onCreate()

    DaggerServiceComponent.builder()
      .appComponent((application as ChronicleApplication).appComponent)
      .serviceModule(ServiceModule(this))
      .build()
      .inject(this)

    ServiceUtils.notifyServiceStarted(this)

    Timber.i("Service created! $this")

    updateAudioAttrs(exoPlayer)

    prefsRepo.registerPrefsListener(prefsListener)

    serviceScope.launch(Injector.get().unhandledExceptionHandler()) { mediaSource.load() }

    mediaSession.setPlaybackState(PlaybackStateCompat.Builder().build())
    mediaSession.setCallback(mediaSessionCallback)

    updateCustomActions()
    switchToPlayer(exoPlayer)

    mediaController.registerCallback(onMediaChangedCallback)

    // startForeground has to be called within 5 seconds of starting the service or the app
    // will ANR (on Android 9.0 and above, maybe earlier). Built and posted *synchronously* —
    // the artwork-bearing build awaits a network fetch that can outlast the deadline by 15 s
    // (cu-137), so the cover is attached by the follow-up below instead.
    startForeground(
      NOW_PLAYING_NOTIFICATION,
      notificationBuilder.buildNotificationWithoutArtwork(mediaSession.sessionToken),
    )
    serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
      postNotificationWithArtwork()
    }

    localBroadcastManager.registerReceiver(
      sleepTimerBroadcastReceiver,
      IntentFilter(SleepTimer.ACTION_SLEEP_TIMER_CHANGE),
    )

    invalidatePlaybackParams()
    observeBookSpeedOverride()
    progressUpdater.startRegularProgressUpdates()

    plexConfig.connectionState.observeForever(serverChangedListener)
  }

  /**
   * Re-applies playback params when the book changes or its speed override does (cu-20).
   *
   * Maps to just the two fields that matter and `distinctUntilChanged`s before acting.
   * [CurrentlyPlaying.book] re-emits whenever the `Audiobook` value differs, and `ProgressUpdater`
   * writes progress **once a second** during playback — so collecting the book itself would call
   * `setPlaybackParameters` at tick rate for a value that had not changed, which is the exact
   * shape cu-110 was about.
   */
  private fun observeBookSpeedOverride() {
    serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
      currentlyPlaying.book
        .map { it.id to it.playbackSpeed }
        .distinctUntilChanged()
        .collect { invalidatePlaybackParams() }
    }
  }

  private fun updateAudioAttrs(exoPlayer: ExoPlayer) {
    exoPlayer.setAudioAttributes(
      AudioAttributes.Builder()
        .setContentType(
          if (prefsRepo.pauseOnFocusLost) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC,
        )
        .setUsage(C.USAGE_MEDIA)
        .build(),
      true,
    )
  }

  private fun updateCustomActions() {
    sessionCustomActions = buildCustomActions(prefsRepo)
    updateSessionPlaybackState()
  }

  private fun setSessionCustomErrorMessage(message: String?) {
    sessionErrorMessage = message
    updateSessionPlaybackState()
  }

  override fun broadcastUpdate(
    sleepTimerAction: SleepTimerAction,
    durationMillis: Long,
    isActive: Boolean,
  ) {
    val broadcastIntent =
      Intent(SleepTimer.ACTION_SLEEP_TIMER_CHANGE).apply {
        putExtra(ARG_SLEEP_TIMER_ACTION, sleepTimerAction)
        putExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, durationMillis)
        putExtra(SleepTimer.ARG_SLEEP_TIMER_IS_ACTIVE, isActive)
      }
    localBroadcastManager.sendBroadcast(broadcastIntent)
  }

  private val sleepTimerBroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent != null) {
          val durationMillis = intent.getLongExtra(ARG_SLEEP_TIMER_DURATION_MILLIS, 0L)
          val action =
            IntentCompat.getSerializableExtra(
              intent,
              ARG_SLEEP_TIMER_ACTION,
              SleepTimerAction::class.java,
            )
          // UPDATE travels the *other* way: it is what the timer publishes to the UI, on this same
          // action. Feeding it back into the timer is a loop — harmless while `update` only
          // reassigned a Long to itself, but it silently overwrote the timer's mode once the state
          // carried one, turning an end-of-chapter timer into a zero-length countdown that expired
          // on the next tick (cu-21). The timer is told what to do by the UI; it is never told
          // what it just said.
          if (action != null && action != SleepTimerAction.UPDATE) {
            sleepTimer.handleAction(action, durationMillis)
          }
        }
      }
    }

  private val prefsListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      when (key) {
        PrefsRepo.KEY_SKIP_SILENCE, PrefsRepo.KEY_PLAYBACK_SPEED -> {
          invalidatePlaybackParams()
        }
        PrefsRepo.KEY_PAUSE_ON_FOCUS_LOST -> {
          updateAudioAttrs(exoPlayer)
        }
        PrefsRepo.KEY_JUMP_FORWARD_SECONDS, PrefsRepo.KEY_JUMP_BACKWARD_SECONDS -> {
          updateCustomActions()
          sessionToken?.let {
            startForeground(
              NOW_PLAYING_NOTIFICATION,
              notificationBuilder.buildNotificationWithoutArtwork(it),
            )
          }
        }
      }
    }

  private val serverChangedListener =
    Observer<PlexConfig.ConnectionState> {
      if (mediaController.playbackState.isPrepared) {
        // Only can change server when playback is prepared because otherwise we would be
        // attempting to load data on a null/empty tracklist
        onChangeConnection()
      }
    }

  /**
   * Change the tracks in the player to refer to the new server url. Because [PlexConfig] is a
   * Singleton we don't need to keep track of state here
   */
  private fun onChangeConnection() {
    when (mediaController.playbackState.state) {
      PlaybackStateCompat.STATE_PLAYING -> {
        mediaSessionCallback.onPlayFromMediaId(
          trackListManager.trackList.map { it.id }.firstOrNull { true }.toString(),
          // No KEY_SEEK_TO_TRACK_WITH_ID: its absence means "resume the most recently
          // listened track", which is what ACTIVE_TRACK used to say (cu-71).
          Bundle().apply {
            putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
          },
        )
      }
      PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.STATE_BUFFERING -> {
        mediaSessionCallback.onPrepareFromMediaId(
          trackListManager.trackList.map { it.id }.firstOrNull { true }.toString(),
          // No KEY_SEEK_TO_TRACK_WITH_ID: its absence means "resume the most recently
          // listened track", which is what ACTIVE_TRACK used to say (cu-71).
          Bundle().apply {
            putLong(KEY_START_TIME_TRACK_OFFSET, USE_SAVED_TRACK_PROGRESS)
          },
        )
      }
      else -> {
        // if there isn't playback, there's nothing to change
      }
    }
  }

  /**
   * Applies speed and skip-silence to the active player.
   *
   * The single writer of [PlaybackParameters], which is why the per-book speed override is
   * resolved here (cu-20) rather than at the load path: this already runs on service start, on a
   * player switch and on a pref change, so one resolution covers every case. The book comes from
   * [currentlyPlaying] because the load path publishes it *after* `player.prepare()` — reading the
   * book at load time would give the outgoing one.
   */
  private fun invalidatePlaybackParams() {
    val book = currentlyPlaying.book.value
    val speed = book.effectiveSpeed(prefsRepo.playbackSpeed)
    Timber.i(
      "Playback params: speed = $speed (book override = ${book.hasSpeedOverride}), " +
        "skip silence = ${prefsRepo.skipSilence}",
    )
    currentPlayer?.setPlaybackParameters(PlaybackParameters(speed, 1.0f))
    (currentPlayer as? ExoPlayer)?.skipSilenceEnabled = prefsRepo.skipSilence
  }

  private fun updateSessionPlaybackState() {
    val player = currentPlayer
    val playbackState =
      if (player != null) {
        buildPlaybackState(player)
      } else {
        buildEmptyPlaybackState()
      }
    mediaSession.setPlaybackState(playbackState)
  }

  private fun buildPlaybackState(player: Player): PlaybackStateCompat {
    val playbackState = mapPlayerState(player)
    val playbackSpeed = player.playbackParameters.speed
    val position = if (player.playbackState == Player.STATE_IDLE) 0L else player.currentPosition
    val builder =
      PlaybackStateCompat.Builder()
        .setActions(basePlaybackActions())
        .setBufferedPosition(player.bufferedPosition)
        .setState(playbackState, position, playbackSpeed)

    sessionCustomActions.forEach(builder::addCustomAction)
    sessionErrorMessage?.let {
      builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it)
    }

    return builder.build()
  }

  private fun basePlaybackActions(): Long =
    PlaybackStateCompat.ACTION_PLAY or
      PlaybackStateCompat.ACTION_PLAY_PAUSE or
      PlaybackStateCompat.ACTION_PAUSE or
      PlaybackStateCompat.ACTION_STOP or
      PlaybackStateCompat.ACTION_SEEK_TO or
      PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
      PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
      PlaybackStateCompat.ACTION_PREPARE or
      PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
      PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
      PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
      PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED

  private fun buildEmptyPlaybackState(): PlaybackStateCompat {
    val builder =
      PlaybackStateCompat.Builder()
        .setActions(basePlaybackActions())
        .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
    sessionCustomActions.forEach(builder::addCustomAction)
    sessionErrorMessage?.let {
      builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it)
    }
    return builder.build()
  }

  private fun mapPlayerState(player: Player): Int =
    when (player.playbackState) {
      Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
      Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
      Player.STATE_READY ->
        if (player.playWhenReady) {
          PlaybackStateCompat.STATE_PLAYING
        } else {
          PlaybackStateCompat.STATE_PAUSED
        }
      Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
      else -> PlaybackStateCompat.STATE_NONE
    }

  private fun updateSessionMetadataFromPlayer(player: Player) {
    val description =
      player.currentMediaItem?.localConfiguration?.tag as? MediaDescriptionCompat
        ?: extractDescriptionFromTimeline(player)
    description?.let { mediaSession.setMetadata(it.toMediaMetadataCompat()) }
  }

  private fun extractDescriptionFromTimeline(player: Player): MediaDescriptionCompat? {
    val timeline = player.currentTimeline
    if (timeline.isEmpty) {
      return null
    }
    timeline.getWindow(player.currentMediaItemIndex, timelineWindow)
    return timelineWindow.mediaItem?.localConfiguration?.tag as? MediaDescriptionCompat
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)

    // Ensures that players will not block being removed as a foreground service
    exoPlayer.stop()
    exoPlayer.clearMediaItems()
  }

  override fun onDestroy() {
    Timber.i("Service destroyed")
    // Send one last update to local/remote servers that playback has stopped
    val trackId = mediaController.metadata.id
    if (trackId != null && trackId != TRACK_NOT_FOUND) {
      val finalPosition = currentPlayer?.currentPosition ?: 0L
      // runBlocking, deliberately. onDestroy has no continuation to suspend into and the
      // process may die the moment it returns, so the write has to finish here. This
      // previously called the fire-and-forget updateProgress and then cancelled
      // serviceJob on the next line, which cancelled the write before it landed — the
      // swipe-away half of the position-loss family.
      runBlocking {
        progressUpdater.updateProgressBlocking(
          trackId,
          PLEX_STATE_STOPPED,
          finalPosition,
        )
      }
    }
    progressUpdater.cancel()
    serviceJob.cancel()

    plexConfig.connectionState.removeObserver(serverChangedListener)
    prefsRepo.unregisterPrefsListener(prefsListener)
    localBroadcastManager.unregisterReceiver(sleepTimerBroadcastReceiver)
    sleepTimer.cancel()

    mediaSession.run {
      isActive = false
      release()
      val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
      intent.setPackage(packageName)
      intent.component =
        ComponentName(
          packageName,
          MediaPlayerService::class.qualifiedName
            ?: "io.github.mattpvaughn.chronicle.features.player.MediaPlayerService",
        )
      intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, 312202))
      // Allow the system to restart app past death on media button click. See onStartCommand
      setMediaButtonReceiver(
        PendingIntent.getService(
          this@MediaPlayerService,
          KeyEvent.KEYCODE_MEDIA_PLAY,
          intent,
          PendingIntent.FLAG_IMMUTABLE,
        ),
      )
    }
    mediaSession.setCallback(null)
    mediaController.unregisterCallback(onMediaChangedCallback)
    becomingNoisyReceiver.unregister()
    serviceJob.cancel()

    exoPlayer.removeListener(playerEventListener)

    ServiceUtils.notifyServiceStopped(this)
    super.onDestroy()
  }

  /** Handle hardware commands from notifications and custom actions from UI as intents */
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    // No need to parse actions if none were provided
    Timber.i("Start command!")

    // Handle intents sent from notification clicks as media button events
    val ke: KeyEvent? =
      intent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
    Timber.i("Key event: $ke")
    if (ke != null) {
      mediaSessionCallback.onMediaButtonEvent(intent)
    }

    // startForeground has to be called within 5 seconds of starting the service or the app
    // will ANR (on Android 9.0+). Even if we don't have full metadata here for unknown reasons,
    // we should launch with whatever it is we have, assuming the event isn't the notification
    // itself being removed (KEYCODE_MEDIA_STOP)
    if (ke?.keyCode != KEYCODE_MEDIA_STOP) {
      // Synchronous for the same reason as in onCreate (cu-137).
      startForeground(
        NOW_PLAYING_NOTIFICATION,
        notificationBuilder.buildNotificationWithoutArtwork(mediaSession.sessionToken),
      )
      serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
        postNotificationWithArtwork()
      }
    }

    /**
     * Return [START_NOT_STICKY] to instruct the system not to restart the
     * service upon death by the OS. This will prevent an empty notification
     * from appearing on service restart
     */
    return START_NOT_STICKY
  }

  override fun onLoadChildren(
    parentId: String,
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
  ) {
    if (parentId == CHRONICLE_MEDIA_EMPTY_ROOT || !prefsRepo.allowAuto) {
      result.sendResult(mutableListOf())
      return
    }

    result.detach()
    serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
      withContext(dispatchers.io) {
        // Categories are matched by their stable id, never by the localized label (cu-99).
        when (AutoBrowseCategory.fromId(parentId)) {
          null ->
            if (parentId == CHRONICLE_MEDIA_ROOT_ID) {
              result.sendResult(
                AutoBrowseCategory.entries
                  .map { makeBrowsable(it.id, getString(it.labelRes), it.iconRes) }
                  .toMutableList(),
              )
            } else {
              // An unknown parent is a bug or a stale id held across an app update. An empty list
              // is the honest answer; sendResult must still be called, since result was detached.
              Timber.w("Unknown Android Auto browse parent: $parentId")
              result.sendResult(mutableListOf())
            }
          AutoBrowseCategory.RecentlyListened ->
            result.sendResult(
              bookRepository.getRecentlyListenedAsync().map { it.toMediaItem(plexConfig) }
                .toMutableList(),
            )
          AutoBrowseCategory.RecentlyAdded ->
            result.sendResult(
              bookRepository.getRecentlyAddedAsync().map { it.toMediaItem(plexConfig) }
                .toMutableList(),
            )
          AutoBrowseCategory.Library ->
            result.sendResult(
              bookRepository.getAllBooksAsync().map { it.toMediaItem(plexConfig) }
                .toMutableList(),
            )
          AutoBrowseCategory.Offline ->
            result.sendResult(
              bookRepository.getCachedAudiobooksAsync().map { it.toMediaItem(plexConfig) }
                .toMutableList(),
            )
        }
      }
    }
  }

  override fun onSearch(
    query: String,
    extras: Bundle?,
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
  ) {
    Timber.i("Searching! Query = $query")
    serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
      val books = bookRepository.searchAsync(query)
      result.sendResult(books.map { it.toMediaItem(plexConfig) }.toMutableList())
    }
    result.detach()
  }

  override fun onGetRoot(
    clientPackageName: String,
    clientUid: Int,
    rootHints: Bundle?,
  ): BrowserRoot? {
    Timber.i("Getting root!")

    val isClientLegal = packageValidator.isKnownCaller(clientPackageName, clientUid) || BuildConfig.DEBUG

    val extras =
      Bundle().apply {
        putBoolean(
          CHRONICLE_MEDIA_SEARCH_SUPPORTED,
          isClientLegal && prefsRepo.allowAuto && plexLoginRepo.loginEvent.value?.peekContent() == LOGGED_IN_FULLY,
        )
        mediaBrowserCompatStringField("EXTRA_MEDIA_SEARCH_SUPPORTED")?.let { putBoolean(it, true) }
        mediaBrowserCompatStringField("EXTRA_SUGGESTED_PRESENTATION_DISPLAY_HINT")?.let { putBoolean(it, true) }
        val focusKey = mediaBrowserCompatStringField("EXTRA_MEDIA_FOCUS")
        val focusValue = mediaBrowserCompatIntField("FOCUS_FULL")
        if (focusKey != null && focusValue != null) {
          putInt(focusKey, focusValue)
        }
      }

    return when {
      !prefsRepo.allowAuto -> {
        setSessionCustomErrorMessage(
          getString(R.string.auto_access_error_auto_is_disabled),
        )
        BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
      }
      !isClientLegal -> {
        setSessionCustomErrorMessage(
          getString(R.string.auto_access_error_invalid_client),
        )
        BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
      }
      plexLoginRepo.loginEvent.value?.peekContent() == NOT_LOGGED_IN -> {
        setSessionCustomErrorMessage(
          getString(R.string.auto_access_error_not_logged_in),
        )
        BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
      }
      plexLoginRepo.loginEvent.value?.peekContent() == LOGGED_IN_NO_USER_CHOSEN -> {
        setSessionCustomErrorMessage(
          getString(R.string.auto_access_error_no_user_chosen),
        )
        BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
      }
      plexLoginRepo.loginEvent.value?.peekContent() == LOGGED_IN_NO_SERVER_CHOSEN -> {
        setSessionCustomErrorMessage(
          getString(R.string.auto_access_error_no_server_chosen),
        )
        BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
      }
      plexLoginRepo.loginEvent.value?.peekContent() == LOGGED_IN_NO_LIBRARY_CHOSEN -> {
        setSessionCustomErrorMessage(
          getString(R.string.auto_access_error_no_library_chosen),
        )
        BrowserRoot(CHRONICLE_MEDIA_EMPTY_ROOT, extras)
      }
      else -> {
        setSessionCustomErrorMessage(null)
        BrowserRoot(CHRONICLE_MEDIA_ROOT_ID, extras)
      }
    }
  }

  private val playerEventListener =
    object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        // `error.message` is only ever the generic "Source error"; the useful part is the cause
        // chain, which names the HTTP status or IO failure that actually stopped playback. Logging
        // the message alone is what made a mid-listen stall undiagnosable from a log dump (cu-103).
        val diagnosis = describePlaybackError(error)
        Timber.e(error, "Exoplayer playback error: $diagnosis")
        val errorIntent = Intent(ACTION_PLAYBACK_ERROR)
        errorIntent.putExtra(PLAYBACK_ERROR_MESSAGE, diagnosis)
        localBroadcastManager.sendBroadcast(errorIntent)
        setSessionCustomErrorMessage(diagnosis)
        updateSessionPlaybackState()
      }

      override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
      ) {
        updateSessionPlaybackState()
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateSessionPlaybackState()
      }

      override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
      ) {
        currentPlayer?.let {
          updateSessionMetadataFromPlayer(it)
          updateSessionPlaybackState()
        }
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
      ) {
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
          if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            Timber.i("Playing next track")
            // Update track progress
            val trackId = mediaController.metadata.id
            if (trackId != null && trackId != TRACK_NOT_FOUND) {
              val plexState = PLEX_STATE_PLAYING
              withContext(dispatchers.io) {
                val bookId = trackRepository.getBookIdForTrack(trackId)
                val track = trackRepository.getTrackAsync(trackId)
                val tracks = trackRepository.getTracksForAudiobookAsync(bookId)

                if (tracks.getDuration() == tracks.getProgress().millis) {
                  mediaController.transportControls.stop()
                }
                progressUpdater.updateProgress(
                  trackId,
                  plexState,
                  track?.duration ?: 0L,
                  true,
                )
              }
            }
          }
        }
        currentPlayer?.let { updateSessionMetadataFromPlayer(it) }
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_IDLE) {
          setSessionCustomErrorMessage(null)
        }
        updateSessionPlaybackState()
        if (playbackState != Player.STATE_ENDED) {
          return
        }
        Timber.i("Player STATE ENDED")
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
          withContext(dispatchers.io) {
            // get track through tracklistmanager b/c metadata will be empty
            val activeTrack = trackListManager.trackList.getActiveTrack()
            if (activeTrack.id != MediaItemTrack.EMPTY_TRACK.id) {
              progressUpdater.updateProgress(
                activeTrack.id,
                PLEX_STATE_STOPPED,
                activeTrack.duration,
                true,
              )
            }
          }
        }
      }
    }

  private fun switchToPlayer(player: Player) {
    if (player == currentPlayer) {
      Timber.i("NOT SWITCHING PLAYER")
      return
    }
    Timber.i("SWITCHING PLAYER to $player")

    val prevPlayer: Player? = currentPlayer

    prevPlayer?.removeListener(playerEventListener)
    if (prevPlayer?.playbackState == Player.STATE_ENDED) {
      prevPlayer.stop()
    }

    currentPlayer = player
    mediaSessionCallback.currentPlayer = player

    prevPlayer?.let {
      val previousIndex = it.currentMediaItemIndex
      if (previousIndex != C.INDEX_UNSET) {
        player.seekTo(previousIndex, it.currentPosition)
      } else {
        player.seekTo(it.currentPosition)
      }
      player.playWhenReady = it.playWhenReady
    }

    player.addListener(playerEventListener)

    prevPlayer?.takeIf { it != player }?.let {
      if (it.playbackState != Player.STATE_ENDED) {
        it.stop()
      }
      it.clearMediaItems()
    }

    updateSessionMetadataFromPlayer(player)
    updateSessionPlaybackState()
    invalidatePlaybackParams()
  }

  override fun stopService() {
    stopForegroundCompat(removeNotification = true)
    stopSelf()
  }

  override fun stopForegroundService(removeNotification: Boolean) {
    stopForegroundCompat(removeNotification)
  }

  private fun stopForegroundCompat(removeNotification: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      val stopMode =
        if (removeNotification) {
          Service.STOP_FOREGROUND_REMOVE
        } else {
          Service.STOP_FOREGROUND_DETACH
        }
      stopForeground(stopMode)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(removeNotification)
    }
  }
}

interface ServiceController {
  fun stopService()
}

interface ForegroundServiceController {
  fun startForeground(
    nowPlayingNotification: Int,
    notification: Notification,
  )

  fun stopForegroundService(removeNotification: Boolean)
}
