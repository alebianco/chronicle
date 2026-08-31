package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexChapter
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainer
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `BookRepository.syncAudiobook` and the watched-state writers.
 *
 * `syncAudiobook` is, under decision-16, the **only** place a fresh `Audiobook.progress` is ever
 * written: it is the one path that has the tracks loaded, so it is the one path that can derive a
 * position. `merge` deliberately carries the local value through everywhere else. Nothing tested
 * that, so the invariant rested entirely on a comment.
 *
 * `setWatched`/`setUnwatched` are the owner's *"mark as read/unread is not consistent"*: both must
 * reset the stored position, or an "unread" book still reads as part-finished (cu-86).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositorySyncTest {
  private val bookDao = mockk<BookDao>(relaxed = true)
  private val chapterDao = mockk<ChapterDao>(relaxed = true)
  private val plexMediaService = mockk<PlexMediaService>(relaxed = true)
  private val plexPrefsRepo = mockk<PlexPrefsRepo>(relaxed = true)
  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { offlineMode } returns false
    }

  private val book =
    Audiobook(
      id = "1001",
      source = 1L,
      title = "Dune",
      progress = 0L,
      duration = 0L,
    )

  private fun track(
    id: String,
    duration: Long,
    progress: Long = 0L,
  ) = MediaItemTrack(
    id = id,
    parentKey = "1001",
    title = "Track $id",
    duration = duration,
    progress = progress,
    index = id.last().digitToInt(),
  )

  /** The server answers with a book, and with no chapters for any track. */
  private fun serverIsHealthy() {
    coEvery { plexMediaService.retrieveAlbum(any()) } returns
      wrapper(PlexDirectory(ratingKey = "1001", title = "Dune"))
    coEvery { plexMediaService.retrieveChapterInfo(any()) } returns wrapper()
  }

  private fun wrapper(vararg metadata: PlexDirectory) = PlexMediaContainerWrapper(PlexMediaContainer(metadata = metadata.toList()))

  private fun capturedBook(): Audiobook {
    val updated = slot<Audiobook>()
    coVerify { bookDao.update(capture(updated)) }
    return updated.captured
  }

  /**
   * The decision-16 invariant. `syncAudiobook` has the tracks, so it derives the position from
   * them — this is the only writer allowed to.
   */
  @Test
  fun `progress is derived from the tracks, not from the server book`() =
    runTest {
      serverIsHealthy()
      val tracks =
        listOf(
          track("2001", duration = 1_000L, progress = 1_000L),
          track("2002", duration = 2_000L, progress = 500L),
        )

      repository().syncAudiobook(book, tracks, forceNetwork = false)

      val written = capturedBook()
      assertEquals(
        "position is the furthest-started track's offset plus the tracks before it",
        1_500L,
        written.progress,
      )
      assertEquals("duration is the sum of the tracks", 3_000L, written.duration)
    }

  /** An unstarted book must derive zero, not inherit something stale. */
  @Test
  fun `an unstarted book syncs to zero progress`() =
    runTest {
      serverIsHealthy()

      repository().syncAudiobook(
        book.copy(progress = 9_999L),
        listOf(track("2001", duration = 1_000L), track("2002", duration = 2_000L)),
        forceNetwork = false,
      )

      assertEquals(0L, capturedBook().progress)
    }

  /**
   * A chapter fetch that fails must abandon the whole sync — not write a book with no chapters,
   * and not clear the chapter table it was about to repopulate.
   */
  @Test
  fun `a failed chapter fetch writes nothing`() =
    runTest {
      coEvery { plexMediaService.retrieveAlbum(any()) } returns
        wrapper(PlexDirectory(ratingKey = "1001", title = "Dune"))
      coEvery { plexMediaService.retrieveChapterInfo(any()) } throws IOException("offline")

      val synced =
        repository().syncAudiobook(book, listOf(track("2001", duration = 1_000L)), false)

      assertFalse("a failed sync must report failure", synced)
      coVerify(exactly = 0) { bookDao.update(any()) }
      coVerify(exactly = 0) { chapterDao.removeAllForBook(any()) }
      coVerify(exactly = 0) { chapterDao.insertAll(any()) }
    }

  /** Likewise if the book itself cannot be fetched. */
  @Test
  fun `a failed book fetch writes nothing`() =
    runTest {
      coEvery { plexMediaService.retrieveChapterInfo(any()) } returns wrapper()
      coEvery { plexMediaService.retrieveAlbum(any()) } throws IOException("offline")

      val synced =
        repository().syncAudiobook(book, listOf(track("2001", duration = 1_000L)), false)

      assertFalse(synced)
      coVerify(exactly = 0) { bookDao.update(any()) }
    }

  /** A server that returns no book at all is a failure, not an empty book to write. */
  @Test
  fun `a missing book on the server writes nothing`() =
    runTest {
      coEvery { plexMediaService.retrieveChapterInfo(any()) } returns wrapper()
      coEvery { plexMediaService.retrieveAlbum(any()) } returns wrapper()

      val synced =
        repository().syncAudiobook(book, listOf(track("2001", duration = 1_000L)), false)

      assertFalse(synced)
      coVerify(exactly = 0) { bookDao.update(any()) }
    }

  /**
   * Chapters are replaced, not merged: a book whose chapter count shrank must not keep the stale
   * extras, and the removal must be scoped to this book.
   */
  @Test
  fun `chapters are replaced for this book only`() =
    runTest {
      coEvery { plexMediaService.retrieveAlbum(any()) } returns
        wrapper(PlexDirectory(ratingKey = "1001", title = "Dune"))
      coEvery { plexMediaService.retrieveChapterInfo("2001") } returns
        wrapper(
          PlexDirectory(
            ratingKey = "2001",
            plexChapters = listOf(PlexChapter(index = 1L, startTimeOffset = 0L, endTimeOffset = 500L)),
          ),
        )

      repository().syncAudiobook(book, listOf(track("2001", duration = 1_000L)), false)

      coVerify(exactly = 1) { chapterDao.removeAllForBook("1001") }
      val inserted = slot<List<Chapter>>()
      coVerify { chapterDao.insertAll(capture(inserted)) }
      assertTrue("the fetched chapter must be written", inserted.captured.isNotEmpty())
    }

  /** Marking read must clear the position, or the book still shows part-finished (cu-86). */
  @Test
  fun `marking a book read resets its stored position`() =
    runTest {
      repository().setWatched("1001")

      coVerify { bookDao.setWatched("1001") }
      coVerify { bookDao.resetBookProgress("1001") }
    }

  /** And the inverse, which is the half of cu-86 that was actually broken. */
  @Test
  fun `marking a book unread resets its stored position`() =
    runTest {
      repository().setUnwatched("1001")

      coVerify { bookDao.setUnwatched("1001") }
      coVerify { bookDao.resetBookProgress("1001") }
    }

  /**
   * A server rejection must not leave the local state changed: the two sides would then disagree
   * permanently, which is the cross-device inconsistency the owner reported.
   */
  @Test
  fun `a rejected mark-as-read does not change local state`() =
    runTest {
      coEvery { plexMediaService.watched(any()) } throws IOException("offline")

      repository().setWatched("1001")

      coVerify(exactly = 0) { bookDao.setWatched(any()) }
      coVerify(exactly = 0) { bookDao.resetBookProgress(any()) }
    }

  private fun TestScope.repository() =
    BookRepository(
      bookDao = bookDao,
      chapterDao = chapterDao,
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      plexMediaService = plexMediaService,
      dispatchers = TestDispatcherProvider(testScheduler),
    )
}
