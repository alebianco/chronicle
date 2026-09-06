package io.github.mattpvaughn.chronicle.injection.modules

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.support.v4.media.RatingCompat.RATING_NONE
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.MediaSessionCompat.*
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.scopes.ServiceScoped
import io.github.mattpvaughn.chronicle.BuildConfig
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.MainActivity
import io.github.mattpvaughn.chronicle.data.sources.plex.APP_NAME
import io.github.mattpvaughn.chronicle.data.sources.plex.PlaybackSession
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.features.player.*
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_BACK_BUFFER_DURATION_MILLIS
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_MAX_BUFFER_DURATION_MILLIS
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.EXOPLAYER_MIN_BUFFER_DURATION_MILLIS
import io.github.mattpvaughn.chronicle.features.player.artworkFreeExtractorsFactory
import io.github.mattpvaughn.chronicle.util.PackageValidator
import kotlinx.coroutines.CompletableJob
import kotlin.time.ExperimentalTime

/**
 * Player-service bindings (cu-185).
 *
 * An `object` taking `Service` rather than a class holding the concrete service: Hilt builds the
 * module, so there is no constructor to pass one to. Six of these bindings genuinely need the
 * **subclass** — `serviceJob`, `serviceScope`, `sessionToken`, and the three interfaces
 * `MediaPlayerService` itself implements — so those cast, which is safe because Hilt only builds
 * this component for that service.
 */
@ExperimentalTime
@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {
  private fun Service.player(): MediaPlayerService = this as MediaPlayerService

  @Provides
  @ServiceScoped
  fun service(service: Service): Service = service

  @Provides
  @ServiceScoped
  fun serviceController(service: Service): ServiceController = service.player()

  @Provides
  @ServiceScoped
  fun serviceJob(service: Service): CompletableJob = service.player().serviceJob

  @Provides
  @ServiceScoped
  fun serviceScope(service: Service) = service.player().serviceScope

  @Provides
  @ServiceScoped
  // DefaultMediaSourceFactory and the extractor flags it carries are Media3 @UnstableApi, the same
  // opt-in AudiobookRenderersFactory already takes.
  @UnstableApi
  fun exoPlayer(
    service: Service,
  ): ExoPlayer =
    // AudiobookRenderersFactory retunes silence skipping for narration: ExoPlayer's defaults
    // collapse pauses shorter than the gaps between ordinary words (cu-88).
    ExoPlayer.Builder(service)
      .setRenderersFactory(AudiobookRenderersFactory(service))
      .setMediaSourceFactory(DefaultMediaSourceFactory(service, artworkFreeExtractorsFactory()))
      .setLoadControl(
        // increase buffer size across the board as ExoPlayer defaults are set for video
        DefaultLoadControl.Builder().setBackBuffer(EXOPLAYER_BACK_BUFFER_DURATION_MILLIS, true)
          .setBufferDurationsMs(
            EXOPLAYER_MIN_BUFFER_DURATION_MILLIS,
            EXOPLAYER_MAX_BUFFER_DURATION_MILLIS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
          )
          .build(),
      ).build()

  @Provides
  @ServiceScoped
  fun pendingIntent(service: Service): PendingIntent =
    service.packageManager.getLaunchIntentForPackage(service.packageName).let { sessionIntent ->
      sessionIntent?.putExtra(MainActivity.FLAG_OPEN_ACTIVITY_TO_CURRENTLY_PLAYING, true)
      PendingIntent.getActivity(
        service,
        MainActivity.REQUEST_CODE_OPEN_APP_TO_CURRENTLY_PLAYING,
        sessionIntent,
        PendingIntent.FLAG_IMMUTABLE,
      )
    }

  @Provides
  @ServiceScoped
  fun mediaSession(
    launchActivityPendingIntent: PendingIntent,
    service: Service,
  ): MediaSessionCompat =
    MediaSessionCompat(service, APP_NAME).apply {
      // All three deliberately, not just queue commands. The media-button and transport-control
      // flags are auto-enabled from API 28, but minSdk here is 27 — so on the oldest supported
      // release the session advertised neither, and a session that does not claim transport
      // controls is a candidate cause of Android Auto showing no media card (cu-89). Setting them
      // is a no-op on newer releases, so this rules the theory out cheaply rather than leaving it
      // as a maybe. It is *not* a confirmed fix: the remaining diagnosis needs a device.
      setFlags(
        FLAG_HANDLES_MEDIA_BUTTONS or
          FLAG_HANDLES_TRANSPORT_CONTROLS or
          FLAG_HANDLES_QUEUE_COMMANDS,
      )
      service.player().sessionToken = sessionToken
      setSessionActivity(launchActivityPendingIntent)
      setRatingType(RATING_NONE)
      isActive = true
    }

  @Provides
  @ServiceScoped
  fun localBroadcastManager(service: Service) = LocalBroadcastManager.getInstance(service)

  @Provides
  @ServiceScoped
  fun sleepTimerBroadcaster(service: Service): SleepTimer.SleepTimerBroadcaster = service.player()

  @Provides
  @ServiceScoped
  fun sleepTimer(simpleSleepTimer: SimpleSleepTimer): SleepTimer = simpleSleepTimer

  @Provides
  @ServiceScoped
  fun provideProgressUpdater(
    updater: SimpleProgressUpdater,
    mediaControllerCompat: MediaControllerCompat,
  ): ProgressUpdater =
    updater.apply {
      mediaController = mediaControllerCompat
    }

  @Provides
  @ServiceScoped
  fun notificationManager(service: Service): NotificationManagerCompat = NotificationManagerCompat.from(service)

  @Provides
  @ServiceScoped
  fun mediaController(
    session: MediaSessionCompat,
    service: Service,
  ) = MediaControllerCompat(service, session.sessionToken)

  @Provides
  @ServiceScoped
  fun becomingNoisyReceiver(
    session: MediaSessionCompat,
    service: Service,
  ) = BecomingNoisyReceiver(service, session.sessionToken)

  @Provides
  @ServiceScoped
  fun plexDataSourceFactory(
    plexPrefs: PlexPrefsRepo,
    playbackSession: PlaybackSession,
    service: Service,
  ): DefaultHttpDataSource.Factory {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
    dataSourceFactory.setUserAgent(Util.getUserAgent(service, APP_NAME))

    dataSourceFactory.setDefaultRequestProperties(
      mapOf(
        "X-Plex-Platform" to "Android",
        "X-Plex-Provides" to "player",
        "X-Plex_Client-Name" to APP_NAME,
        "X-Plex-Client-Identifier" to plexPrefs.uuid,
        "X-Plex-Version" to BuildConfig.VERSION_NAME,
        "X-Plex-Product" to APP_NAME,
        "X-Plex-Platform-Version" to Build.VERSION.RELEASE,
        "X-Plex-Device" to Build.MODEL,
        "X-Plex-Device-Name" to Build.MODEL,
        "X-Plex-Token" to playbackSession.authToken,
      ),
    )

    return dataSourceFactory
  }

  @Provides
  @ServiceScoped
  fun packageValidator(service: Service) = PackageValidator(service, R.xml.auto_allowed_callers)

  @Provides
  @ServiceScoped
  fun foregroundServiceController(service: Service): ForegroundServiceController = service.player()

  @Provides
  @ServiceScoped
  fun mediaSessionCallback(callback: AudiobookMediaSessionCallback): Callback = callback

  @Provides
  @ServiceScoped
  fun trackListManager(service: Service): TrackListStateManager = TrackListStateManager()

  @Provides
  @ServiceScoped
  fun sensorManager(service: Service): SensorManager =
    service.getSystemService(
      Context.SENSOR_SERVICE,
    ) as SensorManager

  @Provides
  @ServiceScoped
  fun toneManager(service: Service) = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
}
