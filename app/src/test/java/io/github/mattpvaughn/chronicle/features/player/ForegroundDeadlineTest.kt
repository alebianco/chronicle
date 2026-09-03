package io.github.mattpvaughn.chronicle.features.player

import android.app.Notification
import android.graphics.Bitmap
import android.support.v4.media.session.MediaSessionCompat
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The foreground-service deadline (cu-137).
 *
 * `startForeground` must be called within 5 s of the service starting or Android 12+ throws
 * `ForegroundServiceDidNotStartInTimeException` (an ANR on older releases). Every call site posted
 * the notification only *after* `buildNotification` returned, and `buildNotification` awaited
 * `plexConfig.getBitmapFromServer` — a Coil request on the media OkHttp client, which carries a
 * 5 s connect + 15 s read timeout (`AppModule.CONNECT_TIMEOUT_SECONDS` / `READ_TIMEOUT_SECONDS`).
 * So cold artwork on a slow route could block for up to 20 s against a 5 s budget.
 *
 * The fix splits the build: everything except the artwork is synchronous, so a notification good
 * enough to satisfy the deadline is available immediately, and the art is attached by a second
 * `notify()` once it arrives.
 *
 * The test that matters is [a notification is available before the artwork resolves]: it hands the
 * builder a bitmap fetch that **never completes**. Before the split this could not return at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ForegroundDeadlineTest {
  private val book = Audiobook(id = "1", source = 0L, title = "Ender's Game", author = "Card")

  private val currentlyPlaying =
    mockk<CurrentlyPlaying>(relaxed = true).also {
      every { it.book } returns MutableStateFlow(book)
      every { it.track } returns MutableStateFlow(MediaItemTrack(id = "2", parentKey = "1"))
      every { it.chapter } returns MutableStateFlow(Chapter())
    }

  /** A fetch that never resolves — the slow-relay case, in its most extreme form. */
  private val neverResolves = CompletableDeferred<Bitmap?>()

  private val plexConfig =
    mockk<PlexConfig>(relaxed = true).also {
      coEvery { it.getBitmapFromServer(any(), any()) } coAnswers { neverResolves.await() }
    }

  private fun builder(): NotificationBuilder =
    NotificationBuilder(
      context = ApplicationProvider.getApplicationContext(),
      plexConfig = plexConfig,
      controller = mockk(relaxed = true),
      currentlyPlaying = currentlyPlaying,
      prefsRepo = mockk(relaxed = true),
    )

  private fun sessionToken(): MediaSessionCompat.Token =
    MediaSessionCompat(
      ApplicationProvider.getApplicationContext(),
      "cu-137",
    ).sessionToken

  /**
   * The deadline property: a notification must be obtainable while the artwork is still in flight.
   *
   * `runTest` fails the test on a hang rather than blocking the suite, so an implementation that
   * awaits the bitmap fails here instead of timing out the build.
   */
  @Test
  fun `a notification is available before the artwork resolves`() =
    runTest {
      val notification: Notification? = builder().buildNotificationWithoutArtwork(sessionToken())

      assertNotNull(
        "startForeground must not wait on the cover-art fetch (cu-137)",
        notification,
      )
    }

  /** The synchronous build must still carry the small icon, or the notification is invalid. */
  @Test
  fun `the immediate notification carries the small icon`() =
    runTest {
      val notification = builder().buildNotificationWithoutArtwork(sessionToken())

      assertNotNull(notification?.smallIcon)
    }

  /**
   * The second phase still attaches artwork when it does arrive, so the split does not quietly
   * cost the user their cover art.
   */
  @Test
  fun `the full build attaches artwork once it resolves`() =
    runTest {
      val art = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
      val resolving = mockk<PlexConfig>(relaxed = true)
      coEvery { resolving.getBitmapFromServer(any(), any()) } returns art

      val withArt =
        NotificationBuilder(
          context = ApplicationProvider.getApplicationContext(),
          plexConfig = resolving,
          controller = mockk(relaxed = true),
          currentlyPlaying = currentlyPlaying,
          prefsRepo = mockk(relaxed = true),
        ).buildNotification(sessionToken())

      assertNotNull(withArt)
      assertNotNull(
        "the artwork phase must set a large icon",
        withArt?.getLargeIcon(),
      )
    }
}
