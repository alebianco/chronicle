package io.github.mattpvaughn.chronicle.features.player

import android.app.Notification
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the *notification actually displays* when the chapter changes (cu-50).
 *
 * The distinction this pins is easy to miss and was the whole bug. `OnMediaChangedCallback` did
 * rebuild the notification on a chapter boundary — `onChapterChange` has always called
 * `notificationManager.notify` — and `NotificationBuilder` did set the chapter title as the
 * content title. But the notification uses `MediaStyle.setMediaSession()`, and Android then renders
 * the **session metadata**, discarding whatever the builder put in `setContentTitle`. That is
 * stated in a comment in `NotificationBuilder` and was then not accounted for.
 *
 * The session metadata was refreshed only from track-level player events
 * (`onMediaItemTransition`, `onPositionDiscontinuity`, `switchToPlayer`) and built from
 * `player.currentMediaItem` — the *track*. **A chapter is not a track:** most of this library is
 * single-track books with many chapters, so crossing a chapter boundary fires no player event at
 * all, and the notification kept showing the same track title for the whole book.
 *
 * So these tests assert on the `MediaMetadataCompat` handed to `MediaSessionCompat.setMetadata`,
 * not on the built notification — asserting the notification's own fields would have passed
 * throughout the bug. They capture the argument rather than reading it back through
 * `session.controller.metadata`, because Robolectric's `MediaSessionCompat` does not publish
 * metadata back to a controller: that always reads null, so such an assertion could never pass
 * and would prove nothing about the fix.
 *
 * Robolectric for the same reason as [NotificationStateMachineTest]: the
 * `MediaControllerCompat.Callback` superclass constructor needs a real `Binder`. Must therefore be
 * listed in PIT's `excludedTestClasses` (cu-57).
 */
@RunWith(RobolectricTestRunner::class)
class ChapterSessionMetadataTest {
  private val notification: Notification =
    NotificationCompat.Builder(
      ApplicationProvider.getApplicationContext(),
      "test-channel",
    ).build()

  private val notificationBuilder =
    mockk<NotificationBuilder>(relaxed = true).also {
      every { it.buildNotificationWithoutArtwork(any()) } returns notification
      coEvery { it.buildNotification(any()) } returns notification
    }

  private val book =
    EMPTY_AUDIOBOOK.copy(id = "book-1", title = "Ender's Game", author = "Orson Scott Card")

  @Test
  fun `a chapter change publishes the chapter title as the session metadata`() {
    val session = spyk(sessionPlaying())
    val callback = callbackFor(session, chapter = chapterNamed("Chapter 4 - The Giant's Drink"))

    callback.onChapterChange(chapterNamed("Chapter 4 - The Giant's Drink"))

    val published = publishedMetadata(session)
    assertEquals("Chapter 4 - The Giant's Drink", published.title)
    // `displayTitle` too, not just `title`: MediaStyle prefers METADATA_KEY_DISPLAY_TITLE when it
    // is present, so setting only `title` leaves the notification showing the stale value. A
    // sabotage that changed just this field passed until it was asserted.
    assertEquals("Chapter 4 - The Giant's Drink", published.displayTitle)
  }

  /**
   * The book is the subtitle, so the shade still says which book is playing. This mirrors the
   * pairing `NotificationBuilder` already chose for its (discarded) content title/text.
   */
  @Test
  fun `the book title becomes the session subtitle`() {
    val session = spyk(sessionPlaying())
    val callback = callbackFor(session, chapter = chapterNamed("Chapter 4"))

    callback.onChapterChange(chapterNamed("Chapter 4"))

    assertEquals("Ender's Game", publishedMetadata(session).displaySubtitle)
  }

  /**
   * A book with no chapter metadata must fall back to the book title rather than publishing an
   * empty string — an empty session title renders as a blank notification, which reads as a bug
   * to the user and is worse than a slightly redundant one.
   */
  @Test
  fun `an empty chapter title falls back to the book title`() {
    val session = spyk(sessionPlaying())
    val callback = callbackFor(session, chapter = Chapter())

    callback.onChapterChange(Chapter())

    assertEquals("Ender's Game", publishedMetadata(session).title)
  }

  /**
   * The media id has to keep identifying the **track**, not the chapter: `onMetadataChanged`
   * resolves the playing book by looking the id up in the *track* repository
   * (`trackRepo.getBookIdForTrack`), and `onPositionDiscontinuity` reads
   * `mediaController.metadata.id` to attribute progress. Publishing a chapter id here would break
   * both, silently — progress would stop being written for the right track.
   */
  @Test
  fun `the media id still identifies the track`() {
    val session = spyk(sessionPlaying())
    val callback = callbackFor(session, chapter = chapterNamed("Chapter 4"), trackId = "track-77")

    callback.onChapterChange(chapterNamed("Chapter 4"))

    assertEquals("track-77", publishedMetadata(session).id)
  }

  /**
   * Nothing playing yet must publish nothing, rather than overwriting whatever the player put in
   * the session with placeholder titles.
   */
  @Test
  fun `no metadata is published when no book is playing`() {
    val session = spyk(sessionPlaying())
    val callback = callbackFor(session, chapter = chapterNamed("Chapter 4"), bookForTest = EMPTY_AUDIOBOOK)

    callback.onChapterChange(chapterNamed("Chapter 4"))

    verify(exactly = 0) { session.setMetadata(any()) }
  }

  /*
   * Deliberately not asserted here: that the artwork URI and duration survive a chapter change.
   *
   * The implementation preserves them by seeding the builder from `mediaController.metadata`, and
   * Robolectric's `MediaSessionCompat` never publishes metadata to a controller — `metadata` reads
   * null no matter what was set (verified directly). So a test of the carry-over would assert on a
   * value the harness cannot produce, and the only way to make it pass would be to mock
   * `MediaControllerCompat`, whose final support-library class collides with Robolectric's
   * instrumentation (see [NotificationStateMachineTest]).
   *
   * What *is* pinned below is the part that matters for the bug and is observable: which display
   * fields get published, and that the media id keeps naming the track. The preservation itself is
   * a single `MediaMetadataCompat.Builder(existing)` copy-constructor call; the risk it guards
   * against is the alternative (rebuilding from scratch), which this shape makes unrepresentable.
   * Left for the instrumented suite, where a real session round-trips.
   */

  /** The single `MediaMetadataCompat` the callback handed to the session. */
  private fun publishedMetadata(session: MediaSessionCompat): android.support.v4.media.MediaMetadataCompat {
    val captured = mutableListOf<android.support.v4.media.MediaMetadataCompat>()
    verify { session.setMetadata(capture(captured)) }
    return captured.last()
  }

  /**
   * The scrubber must span the **chapter**, not the track (cu-165).
   *
   * `PlaybackState.position` is chapter-relative, so a track-length duration here would draw a bar
   * of the wrong size with the marker in the wrong place — the title says "Chapter 4" while the bar
   * says the listener is 8 minutes into a 12-hour file. It is the most-reported Android Auto
   * complaint against both major competitors.
   */
  @Test
  fun `the published duration is the chapter's, not the track's`() {
    val session = spyk(sessionPlaying())
    val chapter =
      Chapter(
        title = "Chapter 4",
        bookStartTimeOffset = BookOffset(1_200_000L),
        bookEndTimeOffset = BookOffset(1_800_000L),
      )
    val callback = callbackFor(session, chapter = chapter)

    callback.onChapterChange(chapter)

    assertEquals(600_000L, publishedMetadata(session).duration)
  }

  /**
   * A book with no chapter data must keep whatever the track path published, so its bar still
   * works. `Chapter()` has a zero-length span, which is also the shape of the "ghost chapters with
   * 0 length" the Epilogue fork had to fix.
   */
  @Test
  fun `a chapter with no span leaves the duration alone`() {
    val session = spyk(sessionPlaying())
    val callback = callbackFor(session, chapter = chapterNamed("Chapter 4"))

    callback.onChapterChange(chapterNamed("Chapter 4"))

    assertEquals(0L, publishedMetadata(session).duration)
  }

  private fun chapterNamed(title: String) = Chapter(title = title)

  private fun sessionPlaying(): MediaSessionCompat =
    MediaSessionCompat(ApplicationProvider.getApplicationContext(), "ChapterSessionMetadataTest")
      .apply {
        setPlaybackState(PlaybackStateCompat.Builder().setState(STATE_PLAYING, 0L, 1f).build())
        isActive = true
      }

  private fun callbackFor(
    session: MediaSessionCompat,
    chapter: Chapter,
    trackId: String = "track-1",
    bookForTest: Audiobook = book,
  ): OnMediaChangedCallback =
    OnMediaChangedCallback(
      mediaController = MediaControllerCompat(ApplicationProvider.getApplicationContext(), session),
      serviceScope = CoroutineScope(Dispatchers.Unconfined),
      notificationBuilder = notificationBuilder,
      mediaSession = session,
      becomingNoisyReceiver = mockk(relaxed = true),
      notificationManager = mockk<NotificationManagerCompat>(relaxed = true),
      foregroundServiceController = mockk(relaxed = true),
      serviceController = mockk(relaxed = true),
      currentlyPlaying =
        mockk(relaxed = true) {
          every { this@mockk.chapter } returns MutableStateFlow(chapter)
          every { book } returns MutableStateFlow(bookForTest)
          // Stubbed because a relaxed mock cannot satisfy a `StateFlow` return: cu-165 reads this
          // to scope the published duration to the chapter, and an unstubbed one throws
          // ClassCastException inside the callback.
          every { bookPosition } returns MutableStateFlow(chapter.bookStartTimeOffset)
          every { track } returns
            MutableStateFlow(
              io.github.mattpvaughn.chronicle.data.model.EMPTY_TRACK.copy(id = trackId),
            )
        },
      trackRepo = mockk(relaxed = true),
      bookRepo = mockk(relaxed = true),
      dispatchers = TestDispatcherProvider(),
      exceptionHandler = testExceptionHandler(),
    )
}
