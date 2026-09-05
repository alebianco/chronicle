package io.github.mattpvaughn.chronicle.features.player

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [MediaServiceConnection]'s connection guard and its published state — 418 instructions at 0%.
 *
 * The guard is the interesting part, and it has a crash behind it. `MediaBrowserCompat.connect()`
 * **throws** rather than ignoring a redundant call:
 *
 * ```
 * IllegalStateException: connect() called while neither disconnecting nor disconnected
 * ```
 *
 * and it exposes no "connecting" state to check — `isConnected` is false for the whole handshake.
 * Two `MainActivity.onCreate`s close together therefore killed the app, which is an ordinary
 * Activity recreation. `connectIfIdle` asks the browser's **own synchronous state** rather than a
 * flag this class publishes, because the browser's state moves *during* `connect()`, before any
 * callback runs (cu-110).
 *
 * Robolectric supplies a real `MediaBrowserCompat`; there is no service to bind to, so the
 * connection never establishes — which is precisely the state a redundant `connect()` has to
 * survive, and the one that used to crash.
 */
@RunWith(RobolectricTestRunner::class)
class MediaServiceConnectionTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun connection() =
    MediaServiceConnection(
      applicationContext = context,
      serviceComponent = ComponentName(context, MediaPlayerService::class.java),
    )

  @Test
  fun `a new connection starts disconnected with nothing playing`() {
    val connection = connection()

    assertFalse("must not claim a connection it has not made", connection.isConnected.value)
    assertEquals(EMPTY_PLAYBACK_STATE.state, connection.playbackState.value.state)
    assertEquals(NOTHING_PLAYING.description.mediaId, connection.nowPlaying.value.description.mediaId)
  }

  /**
   * The regression that matters: a second `connect()` while the first is in flight must be a
   * no-op, not a throw. This is the Activity-recreation path.
   */
  @Test
  fun `a redundant connect while one is in flight does not throw`() {
    val connection = connection()

    connection.connect()
    connection.connect()
    connection.connect()
  }

  /**
   * `connect(onConnected)` registers the callback **even when a connection is already in flight**,
   * so a caller that asked second is not stranded waiting on a handshake it did not start.
   */
  @Test
  fun `a callback registered during an in-flight connection does not throw`() {
    val connection = connection()
    var ran = false

    connection.connect()
    connection.connect { ran = true }

    assertFalse("nothing to bind to in a unit test, so it cannot have run", ran)
  }

  /**
   * Disconnecting must clear the published state rather than leave a stale `isConnected` behind —
   * a caller reading it would otherwise skip a reconnect it needs.
   */
  @Test
  fun `disconnecting clears the connected flag`() {
    val connection = connection()
    connection.connect()

    connection.disconnect()

    assertFalse(connection.isConnected.value)
  }

  /**
   * Disconnect then reconnect is the configuration-change sequence. It must leave the connection
   * usable rather than latched, which is what the `isConnecting` flag being cleared in
   * `disconnect()` is for.
   */
  @Test
  fun `a connection can be re-established after disconnecting`() {
    val connection = connection()
    connection.connect()
    connection.disconnect()

    connection.connect()

    assertFalse(connection.isConnected.value)
  }

  @Test
  fun `the browser is exposed for callers that need its own state`() {
    assertNotNull(
      "connectIfIdle asks the browser directly, so it must be reachable",
      connection().mediaBrowser,
    )
  }

  /**
   * Published state is `MutableStateFlow`, deliberately public and deliberately not `postValue`
   * (cu-52). An assignment lands immediately, which is what makes a read-after-write correct —
   * `postValue` deferred to the next main-loop pass and was the shape of the `connectIfIdle`
   * crash.
   */
  @Test
  fun `published state is readable immediately after it is written`() {
    val connection = connection()

    connection.isConnected.value = true

    assertEquals(true, connection.isConnected.value)
  }
}
