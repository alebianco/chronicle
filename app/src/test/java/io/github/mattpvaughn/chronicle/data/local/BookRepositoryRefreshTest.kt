package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `BookRepository.refreshData` — the library sync.
 *
 * At 4.8% instruction coverage it was among the least-tested code on the trust surface, while
 * deciding what happens to the user's library on every refresh: which side of a conflict wins,
 * and what is deleted.
 *
 * The cases that matter are the failure paths. A network error mid-refresh must leave the local
 * library alone, and offline mode must not touch the network at all — get either wrong and a
 * refresh empties someone's library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryRefreshTest {
  private val bookDao = mockk<BookDao>(relaxed = true)
  private val plexMediaService = mockk<PlexMediaService>(relaxed = true)

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { offlineMode } returns false
      every { lastRefreshTimeStamp = any() } returns Unit
    }

  private val plexPrefsRepo =
    mockk<PlexPrefsRepo>(relaxed = true) {
      every { library } returns PlexLibrary(name = "Books", type = MediaType.ARTIST, id = "1")
    }

  @Test
  fun `offline mode skips the refresh entirely`() =
    runTest {
      every { prefsRepo.offlineMode } returns true

      repository().refreshData()

      coVerify(exactly = 0) { plexMediaService.retrieveAllAlbums(any()) }
      coVerify(exactly = 0) { bookDao.insertAll(any()) }
    }

  @Test
  fun `offline mode does not stamp a refresh time it never performed`() =
    runTest {
      every { prefsRepo.offlineMode } returns true

      repository().refreshData()

      coVerify(exactly = 0) { prefsRepo.lastRefreshTimeStamp = any() }
    }

  /**
   * The important one: a failed fetch must not be read as "the server has no books", which
   * would delete the whole local library.
   */
  @Test
  fun `a network failure leaves the local library untouched`() =
    runTest {
      coEvery { plexMediaService.retrieveAllAlbums(any()) } throws IOException("offline")

      repository().refreshData()

      coVerify(exactly = 0) { bookDao.removeAll(any()) }
      coVerify(exactly = 0) { bookDao.insertAll(any()) }
    }

  private fun serverHas(vararg books: PlexDirectory) {
    coEvery { plexMediaService.retrieveAllAlbums(any()) } returns
      PlexMediaContainerWrapper(PlexMediaContainer(metadata = books.toList()))
  }

  private fun serverBook(
    id: String,
    title: String = "Dune",
    lastViewedAtSeconds: Long = 0L,
  ) = PlexDirectory(ratingKey = id, title = title, lastViewedAt = lastViewedAtSeconds)

  private fun localBook(
    id: String,
    title: String = "Dune",
    progress: Long = 0L,
    lastViewedAt: Long = 0L,
    isCached: Boolean = false,
  ) = Audiobook(
    id = id,
    source = 1L,
    title = title,
    progress = progress,
    lastViewedAt = lastViewedAt,
    isCached = isCached,
  )

  private fun insertedBooks(): List<Audiobook> {
    val inserted = slot<List<Audiobook>>()
    coVerify { bookDao.insertAll(capture(inserted)) }
    return inserted.captured
  }

  /**
   * The deletion rule. A book the server no longer lists is removed — but *only* that book, and
   * only when the fetch actually succeeded (the failure case is covered above).
   */
  @Test
  fun `a book missing from the server is removed, and only that book`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns
        listOf(localBook("1001"), localBook("1002", title = "Neuromancer"))
      serverHas(serverBook("1001"))

      repository().refreshData()

      val removed = slot<List<String>>()
      coVerify { bookDao.removeAll(capture(removed)) }
      assertEquals(listOf("1002"), removed.captured)
    }

  /** Nothing removed when the server still lists everything held locally. */
  @Test
  fun `no book is removed when the server still lists them all`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns listOf(localBook("1001"))
      serverHas(serverBook("1001"))

      repository().refreshData()

      val removed = slot<List<String>>()
      coVerify { bookDao.removeAll(capture(removed)) }
      assertTrue("nothing should be deleted", removed.captured.isEmpty())
    }

  /**
   * The decision-16 invariant at the library level: `refreshData` merges **without loading
   * tracks**, so it must carry local progress through untouched. Zeroing here would blank every
   * book's progress on every refresh.
   */
  @Test
  fun `a refresh preserves local progress`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns
        listOf(localBook("1001", progress = 4_000L, lastViewedAt = 9_000L))
      serverHas(serverBook("1001", lastViewedAtSeconds = 3L))

      repository().refreshData()

      assertEquals(
        "a library refresh must never blank the position it cannot recompute",
        4_000L,
        insertedBooks().single().progress,
      )
    }

  /** Local-only fields survive a refresh too — the server has no concept of them. */
  @Test
  fun `a refresh preserves the cached flag`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns listOf(localBook("1001", isCached = true))
      serverHas(serverBook("1001"))

      repository().refreshData()

      assertEquals(true, insertedBooks().single().isCached)
    }

  /** A book the local DB has never seen is inserted as the server describes it. */
  @Test
  fun `a new book from the server is added`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns emptyList()
      serverHas(serverBook("1001"), serverBook("1002", title = "Neuromancer"))

      repository().refreshData()

      assertEquals(listOf("1001", "1002"), insertedBooks().map { it.id })
    }

  // ---------------------------------------------------------------------------------------
  // refreshDataPaginated — the method the app actually calls.
  //
  // `LibrarySyncRepository.refreshLibrary` is the only refresh entry point in the app, and it
  // calls *this* method; `refreshData` above is unreferenced outside tests. So every failure
  // case proven above was proven about code no user ever runs. These are the same cases against
  // the live path.
  // ---------------------------------------------------------------------------------------

  private fun serverPage(
    books: List<PlexDirectory>,
    totalSize: Long = books.size.toLong(),
    offset: Long = 0,
  ) = PlexMediaContainerWrapper(
    PlexMediaContainer(
      metadata = books,
      size = books.size.toLong(),
      totalSize = totalSize,
      offset = offset,
    ),
  )

  private fun serverHasPaginated(vararg books: PlexDirectory) {
    coEvery { plexMediaService.retrieveAlbumPage(any(), any(), any()) } returns
      serverPage(books.toList())
  }

  /**
   * The one that matters. A fetch that throws must not be read as "the server has no books" —
   * that deletes the entire local library, taking every book's listening position with it.
   */
  @Test
  fun `a paginated network failure leaves the local library untouched`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns
        listOf(localBook("1001"), localBook("1002", title = "Neuromancer"))
      coEvery { plexMediaService.retrieveAlbumPage(any(), any(), any()) } throws
        IOException("offline")

      repository().refreshDataPaginated()

      coVerify(exactly = 0) { bookDao.removeAll(any()) }
      coVerify(exactly = 0) { bookDao.insertAll(any()) }
    }

  /**
   * The partial-failure case, which is the realistic one: pagination succeeds for a few pages
   * and then the network drops. Every book not in the pages that arrived must survive.
   */
  @Test
  fun `a failure part way through pagination deletes nothing`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns
        listOf(localBook("1001"), localBook("1002"), localBook("1003"))
      // Page 1 arrives and reports more to come; page 2 throws.
      coEvery { plexMediaService.retrieveAlbumPage(any(), 0, any()) } returns
        serverPage(listOf(serverBook("1001")), totalSize = 3, offset = 0)
      coEvery { plexMediaService.retrieveAlbumPage(any(), 100, any()) } throws
        IOException("connection reset")

      repository().refreshDataPaginated()

      coVerify(exactly = 0) { bookDao.removeAll(any()) }
      coVerify(exactly = 0) { bookDao.insertAll(any()) }
    }

  /**
   * A missing library is a configuration failure, not an empty server. It used to `return` from
   * the inner `withContext` lambda only, so it fell through to the deletion as well.
   */
  @Test
  fun `no configured library deletes nothing`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns listOf(localBook("1001"))
      every { plexPrefsRepo.library } returns null

      repository().refreshDataPaginated()

      coVerify(exactly = 0) { bookDao.removeAll(any()) }
      coVerify(exactly = 0) { bookDao.insertAll(any()) }
    }

  /** Offline mode must not reach the network on the live path either. */
  @Test
  fun `offline mode skips the paginated refresh entirely`() =
    runTest {
      every { prefsRepo.offlineMode } returns true

      repository().refreshDataPaginated()

      coVerify(exactly = 0) { plexMediaService.retrieveAlbumPage(any(), any(), any()) }
      coVerify(exactly = 0) { bookDao.insertAll(any()) }
    }

  /** The success path still prunes exactly what the server dropped, and nothing more. */
  @Test
  fun `a successful paginated refresh removes only the book the server dropped`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns
        listOf(localBook("1001"), localBook("1002", title = "Neuromancer"))
      serverHasPaginated(serverBook("1001"))

      repository().refreshDataPaginated()

      val removed = slot<List<String>>()
      coVerify { bookDao.removeAll(capture(removed)) }
      assertEquals(listOf("1002"), removed.captured)
    }

  /** decision-16 at the library level, on the live path: local progress is carried through. */
  @Test
  fun `a successful paginated refresh preserves local progress`() =
    runTest {
      coEvery { bookDao.getAudiobooks() } returns
        listOf(localBook("1001", progress = 4_000L, lastViewedAt = 9_000L))
      serverHasPaginated(serverBook("1001", lastViewedAtSeconds = 3L))

      repository().refreshDataPaginated()

      assertEquals(
        "a library refresh must never blank the position it cannot recompute",
        4_000L,
        insertedBooks().single().progress,
      )
    }

  private val chapterDao = mockk<ChapterDao>(relaxed = true)

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
