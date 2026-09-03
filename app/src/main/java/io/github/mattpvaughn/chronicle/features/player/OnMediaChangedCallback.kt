package io.github.mattpvaughn.chronicle.features.player

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import androidx.core.app.NotificationManagerCompat
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.currentlyplaying.OnChapterChangeListener
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/** Responsible for observing changes in media metadata */
@ExperimentalCoroutinesApi
class OnMediaChangedCallback
  @Inject
  constructor(
    private val mediaController: MediaControllerCompat,
    private val serviceScope: CoroutineScope,
    private val notificationBuilder: NotificationBuilder,
    private val mediaSession: MediaSessionCompat,
    private val becomingNoisyReceiver: BecomingNoisyReceiver,
    private val notificationManager: NotificationManagerCompat,
    private val foregroundServiceController: ForegroundServiceController,
    private val serviceController: ServiceController,
    private val currentlyPlaying: CurrentlyPlaying,
    private val trackRepo: ITrackRepository,
    private val bookRepo: IBookRepository,
    private val dispatchers: DispatcherProvider,
  ) : MediaControllerCompat.Callback(), OnChapterChangeListener {
    init {
      currentlyPlaying.setOnChapterChangeListener(this)
    }

    // Book ID, Track ID, Chapter ID

    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
      Timber.i("METADATA CHANGE")
      mediaController.playbackState?.let { state ->
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
          withContext(dispatchers.io) {
            val trackId = metadata?.id ?: TRACK_NOT_FOUND
            if (trackId == TRACK_NOT_FOUND) {
              return@withContext
            }
            val newBook =
              bookRepo.getAudiobookAsync(
                trackRepo.getBookIdForTrack(trackId),
              )
            val newBookId = newBook?.id ?: NO_AUDIOBOOK_FOUND_ID
            val newTracks = trackRepo.getTracksForAudiobookAsync(newBookId)
            val newTrack = trackRepo.getTrackAsync(trackId)
            if (newBook != null && newTrack != null && newTracks.isNotEmpty()) {
              currentlyPlaying.update(
                book = newBook,
                track = newTrack,
                tracks = newTracks,
              )
            }
            updateNotification(state.state)
          }
        }
      }
    }

    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
      Timber.i("Playback state changed to ${state?.stateName} ${System.currentTimeMillis()}")
      if (state == null) {
        return
      }
      serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
        updateNotification(state.state)
      }
    }

    override fun onChapterChange(chapter: Chapter) {
      Timber.d(
        "onChapterChange called: chapter = [${chapter.index}] ${chapter.title} || current: [${currentlyPlaying.chapter.value.index}] ${currentlyPlaying.chapter.value.title}",
      )

      mediaController.playbackState?.let { state ->
        serviceScope.launch(Injector.get().unhandledExceptionHandler()) {
          updateNotification(state.state)
        }
      }
    }

    private suspend fun updateNotification(state: Int) {
      // Built without touching the network: this runs on the path that can *promote* the service
      // to foreground, and awaiting the cover-art fetch first is what could blow the 5 s deadline
      // (cu-137). The art is attached by postArtwork() after the state machine has run.
      val notification =
        if (mediaController.sessionToken != null) {
          notificationBuilder.buildNotificationWithoutArtwork(mediaSession.sessionToken)
        } else {
          null
        }

      Timber.i("Created notif: $notification")

      when (state) {
        STATE_PLAYING, STATE_BUFFERING -> {
          becomingNoisyReceiver.register()
          if (notification != null) {
            notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification)
            foregroundServiceController.startForeground(
              NOW_PLAYING_NOTIFICATION,
              notification,
            )
          }
        }
        STATE_PAUSED -> {
          becomingNoisyReceiver.unregister()
          if (notification != null) {
            notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification)
            foregroundServiceController.startForeground(
              NOW_PLAYING_NOTIFICATION,
              notification,
            )
          }
          // Enables dismiss-on-swipe when paused- swiping triggers the delete
          // intent on the notification to be called, which kills the service
          foregroundServiceController.stopForegroundService(false)
        }
        STATE_STOPPED -> {
          // If playback has ended, fully stop the service.
          Timber.i("Playback has finished, stopping service!")
          notificationManager.cancel(NOW_PLAYING_NOTIFICATION)
          foregroundServiceController.stopForegroundService(true)
          serviceController.stopService()
        }
        else -> {
          // When not actively playing media, notification becomes cancellable on swipe and
          // we stop listening for audio interruptions
          becomingNoisyReceiver.unregister()
          foregroundServiceController.stopForegroundService(true)
        }
      }

      // Only the states above that leave a notification standing are worth re-posting with art.
      // STOPPED just cancelled it, and the else branch never showed one.
      if (state == STATE_PLAYING || state == STATE_BUFFERING || state == STATE_PAUSED) {
        postArtwork()
      }
    }

    /**
     * Re-posts the standing notification with its cover art attached.
     *
     * The second half of the cu-137 split. Deliberately does *not* call `startForeground` again:
     * the state machine above has already decided this state's foreground status — PAUSED
     * releases it on purpose to stay swipe-dismissable — and re-promoting here would undo that.
     * A plain `notify` updates the picture and nothing else.
     */
    private suspend fun postArtwork() {
      val token = mediaController.sessionToken ?: return
      val withArt = notificationBuilder.buildNotification(token)

      // POST_NOTIFICATIONS is revocable from API 33, and this call is new (the two above it are
      // long-standing and baselined). Attaching a cover is cosmetic, so a denial must not take
      // playback down with it — the notification posted by the state machine stays valid either
      // way. Caught rather than permission-checked because the check would have to be repeated
      // and can still race a revocation between check and call.
      try {
        notificationManager.notify(NOW_PLAYING_NOTIFICATION, withArt)
      } catch (e: SecurityException) {
        Timber.w(e, "Not permitted to post the notification artwork; keeping the plain one")
      }
    }
  }
