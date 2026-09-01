package io.github.mattpvaughn.chronicle.features.player

import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.Builder
import android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.injection.scopes.ActivityScope
import timber.log.Timber
import javax.inject.Inject

@ActivityScope
class MediaServiceConnection
  @Inject
  constructor(
    applicationContext: Context,
    serviceComponent: ComponentName,
  ) {
    val isConnected = MutableLiveData(false)
    val playbackState = MutableLiveData(EMPTY_PLAYBACK_STATE)
    val nowPlaying = MutableLiveData(NOTHING_PLAYING)

    /**
     * True between calling [MediaBrowserCompat.connect] and one of its three terminal callbacks.
     *
     * `MediaBrowserCompat.connect()` **throws** if it is called while already connecting or
     * connected, and it exposes no "connecting" state to check — `isConnected` is false for the
     * whole handshake. Two `MainActivity.onCreate`s close together therefore crashed the app with
     * "connect() called while neither disconnecting nor disconnected", which is what an Activity
     * recreation does: a rotation, a theme change, or coming back to a process the system kept
     * (cu-54 found it via `ActivityScenario.recreate`).
     */
    private var isConnecting = false

    private val connectionCallbacks =
      object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
          isConnecting = false
          isConnected.postValue(true)

          // Create a MediaControllerCompat from the session token
          mediaController =
            MediaControllerCompat(
              applicationContext,
              mediaBrowser.sessionToken,
            ).apply {
              registerCallback(mediaControllerCallback)
              this@MediaServiceConnection.transportControls = transportControls
            }

          // If the service already exists, bind the state right now
          if (mediaController?.playbackState?.state ?: STATE_NONE != STATE_NONE) {
            playbackState.postValue(
              mediaController?.playbackState ?: EMPTY_PLAYBACK_STATE,
            )
            nowPlaying.postValue(mediaController?.metadata ?: NOTHING_PLAYING)
          }
        }

        override fun onConnectionSuspended() {
          // The Service has crashed. Disable transport controls until it automatically reconnects
          Timber.i("Service connection suspended")
          isConnecting = false
          isConnected.postValue(false)
        }

        override fun onConnectionFailed() {
          // The Service has refused our connection
          Timber.i("Service connection failed")
          isConnecting = false
          isConnected.postValue(false)
        }
      }

    val mediaControllerCallback = MediaControllerCallback()

    val mediaBrowser: MediaBrowserCompat =
      MediaBrowserCompat(
        applicationContext,
        serviceComponent,
        connectionCallbacks,
        null,
      )

    var mediaController: MediaControllerCompat? = null

    var transportControls: MediaControllerCompat.TransportControls? = null

    inner class MediaControllerCallback : MediaControllerCompat.Callback() {
      // Dangerous- easy to leak this lambda as [MediaServiceConnection] is application-scoped
      var onConnected: () -> Unit? = {}

      override fun onSessionReady() {
        Timber.i("MediaController session ready")
        onConnected.invoke()
        super.onSessionReady()
      }

      override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
        onConnected = {}
        Timber.i("MediaController state: $state")
        playbackState.postValue(state ?: EMPTY_PLAYBACK_STATE)
      }

      override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
        onConnected = {}
        Timber.i("MediaController metadata: ${metadata?.describe()}")
        if (metadata?.id == null || metadata.title == null) {
          nowPlaying.postValue(NOTHING_PLAYING)
        } else {
          nowPlaying.postValue(metadata)
        }
      }

      override fun onSessionDestroyed() {
        onConnected = {}
        Timber.i("MediaController callback is kill")
        isConnected.postValue(false)
        super.onSessionDestroyed()
      }
    }

    fun disconnect() {
      Timber.i("Disconnecting MediaServiceConnection")
      isConnecting = false
      isConnected.postValue(false)
      mediaControllerCallback.onConnected = {}
      mediaController?.unregisterCallback(mediaControllerCallback)
      mediaBrowser.disconnect()
    }

    fun connect() {
      connectIfIdle()
    }

    fun connect(onConnected: () -> Unit?) {
      // The callback is registered even when a connection is already in flight, so a caller
      // waiting on it is not stranded by another caller having asked first.
      mediaControllerCallback.onConnected = onConnected
      connectIfIdle()
    }

    /**
     * Starts a connection only when one is neither established nor in flight.
     *
     * See [isConnecting]: `MediaBrowserCompat.connect()` throws rather than ignoring a redundant
     * call, so this guard is what keeps an Activity recreation from crashing the app.
     */
    private fun connectIfIdle() {
      if (isConnecting || isConnected.value == true) {
        Timber.i("Already connected or connecting; skipping redundant connect()")
        return
      }
      isConnecting = true
      mediaBrowser.connect()
    }
  }

val EMPTY_PLAYBACK_STATE: PlaybackStateCompat =
  Builder()
    .setState(STATE_NONE, 0, 0f)
    .build()

val NOTHING_PLAYING: MediaMetadataCompat =
  MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "")
    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "")
    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
    .build()
