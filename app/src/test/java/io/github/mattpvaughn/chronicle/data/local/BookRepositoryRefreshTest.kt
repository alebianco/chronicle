package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
