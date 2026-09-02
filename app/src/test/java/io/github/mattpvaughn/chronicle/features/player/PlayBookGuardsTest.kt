package io.github.mattpvaughn.chronicle.features.player

import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import androidx.test.core.app.ApplicationProvider
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * `playBook`'s two guards, as observable behaviour (DRAFT-72, second slice).
 *
 * Written before injecting dispatchers into `AudiobookMediaSessionCallback`, for the reason that
 * task gives: a wrong scope here drops or duplicates work rather than failing to compile, so the
 * conversion needs something that would notice.
 *
 * What is worth pinning is the **number of network fetches**, because that is what cu-97 was about:
 * an unbounded retry issued one request per pass against the user's Plex server, forever. A
 * dispatcher change that accidentally re-entered the coroutine, or ran it twice, would show up here
 * as a changed call count and nowhere else.
 *
 * Robolectric because `MediaSessionCompat.Callback`'s constructor reaches `android.os.Binder`, and
 * a real `MediaSessionCompat` because mocking final support-library classes under Robolectric
 * collides with mockk's instrumentation — both learned in the first slice and recorded in the task.
 */
@RunWith(RobolectricTestRunner::class)
class PlayBookGuardsTest {
  private val bookId = "1001"

  private val tracks =
    listOf(
      MediaItemTrack(id = "2001", parentKey = bookId, index = 1, duration = 600_000L),
    )

  /**
   * The cu-97 bound. A fetch that succeeds while yielding no tracks must be attempted **once**;
   * the old code recursed and re-fetched on every pass.
   */
  @Test
  fun `a book that resolves to no tracks is fetched once, not repeatedly`() {
    var fetches = 0
    val trackRepo =
      FakeEmptyTrackRepository(onFetch = {
        fetches++
        Ok(emptyList())
      })

    callback(trackRepo).onPlayFromMediaId(bookId, Bundle())

    assertEquals(
      "an unbounded retry issues one request per pass against the user's server (cu-97)",
      1,
      fetches,
    )
  }

  /** A failed fetch must not be retried either — it is a rejection, not a blip. */
  @Test
  fun `a failed fetch is not retried`() {
    var fetches = 0
    val trackRepo =
      FakeEmptyTrackRepository(onFetch = {
        fetches++
        Err(IOException("offline"))
      })

    callback(trackRepo).onPlayFromMediaId(bookId, Bundle())

    assertEquals(1, fetches)
  }

  /**
   * The ordinary path must not fetch at all: tracks are already local. Asserting the absence
   * because a needless fetch on every play is exactly the kind of regression a dispatcher change
   * can introduce silently.
   */
  @Test
  fun `a book with local tracks triggers no network fetch`() {
    var fetches = 0
    val trackRepo =
      FakeEmptyTrackRepository(onFetch = {
        fetches++
        Ok(emptyList())
      }, localTracks = tracks)

    callback(trackRepo).onPlayFromMediaId(bookId, Bundle())

    assertEquals("tracks are already local; fetching again is waste", 0, fetches)
  }

  /**
   * A hand-written fake rather than a mock.
   *
   * `loadTracksForAudiobook` returns `Result`, a **value class**, and mockk mangles the method name
   * (`loadTracksForAudiobook-ta8aW1Q`) so `coVerify` cannot match the call — the verification fails
   * while the call really did happen. Counting invocations directly is unambiguous.
   */
  private class FakeEmptyTrackRepository(
    private val onFetch: () -> Result<List<MediaItemTrack>, Throwable>,
    private val localTracks: List<MediaItemTrack> = emptyList(),
  ) : ITrackRepository by mockk(relaxed = true) {
    override suspend fun getTracksForAudiobookAsync(bookId: String) = localTracks

    override suspend fun loadTracksForAudiobook(
      bookId: String,
      forceUseNetwork: Boolean,
    ): Result<List<MediaItemTrack>, Throwable> = onFetch()
  }

  private fun callback(trackRepo: ITrackRepository): AudiobookMediaSessionCallback {
    val session =
      MediaSessionCompat(ApplicationProvider.getApplicationContext(), "PlayBookGuardsTest")
    val bookRepo =
      mockk<IBookRepository>(relaxed = true) {
        coEvery { getAudiobookAsync(any()) } returns Audiobook(id = bookId, source = 1L, title = "Book")
      }

    return AudiobookMediaSessionCallback(
      plexPrefsRepo = mockk(relaxed = true),
      prefsRepo = mockk(relaxed = true),
      plexConfig = mockk(relaxed = true),
      mediaController = mockk(relaxed = true),
      dataSourceFactory = mockk(relaxed = true),
      trackRepository = trackRepo,
      bookRepository = bookRepo,
      // Unconfined so the launched work runs inline; the point of these tests is the call counts,
      // not the scheduling.
      serviceScope = CoroutineScope(Dispatchers.Unconfined),
      trackListStateManager = TrackListStateManager(),
      foregroundServiceController = mockk(relaxed = true),
      serviceController = mockk(relaxed = true),
      mediaSession = session,
      appContext = ApplicationProvider.getApplicationContext(),
      // Same trap as the first slice: a relaxed mock cannot satisfy `StateFlow<MediaItemTrack>`,
      // and `flushOutgoingBookProgress` reads `.value` first thing. The cast failure happens
      // *inside* the launched coroutine, so it vanishes and the test just sees zero fetches.
      currentlyPlaying =
        mockk(relaxed = true) {
          every { track } returns MutableStateFlow(MediaItemTrack.EMPTY_TRACK)
        },
      progressUpdater = mockk(relaxed = true),
      defaultPlayer = mockk(relaxed = true),
      dispatchers = TestDispatcherProvider(),
    )
  }
}
