package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.data.sources.SourceCapabilities
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.testing.OTHER_TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `BookRepository.ingest` — the repository half of the seam cu-80 opened.
 *
 * `SourceManager.refreshBooks` was a `check` that threw, on the grounds that *"neither
 * bookRepository nor trackRepository accepts a caller-supplied list"*. This is that method, so
 * these assertions are the proof the blocker is gone rather than moved.
 *
 * The DAO is a mock on purpose: what matters is precisely *which* rows are inserted and removed,
 * and the danger in this change is deleting too many.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryIngestTest {
  private val bookDao = mockk<BookDao>(relaxed = true)
  private val chapterDao = mockk<ChapterDao>(relaxed = true)
  private val prefsRepo = mockk<PrefsRepo>(relaxed = true)
  private val plexMediaService = mockk<PlexMediaService>(relaxed = true)
  private val plexPrefsRepo =
    mockk<PlexPrefsRepo>(relaxed = true) { every { library } returns null }

  private val plex = TEST_SOURCE
  private val other = OTHER_TEST_SOURCE

  private fun book(
    id: String,
    source: SourceId = plex,
    progress: Long = 0L,
  ) = Audiobook(id = id, source = source, title = "Book $id", progress = progress)

  private fun repository(): BookRepository =
    BookRepository(
      bookDao = bookDao,
      chapterDao = chapterDao,
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      plexMediaService = plexMediaService,
      dispatchers = TestDispatcherProvider(),
    )

  @Test
  fun `ingested books are written to the database`() =
    runTest {
      every { bookDao.getAudiobooks(any()) } returns emptyList()
      val inserted = slot<List<Audiobook>>()
      every { bookDao.insertAll(capture(inserted)) } returns Unit

      repository().ingest(listOf(book("a"), book("b")), plex, SourceCapabilities())

      assertEquals(listOf("a", "b"), inserted.captured.map { it.id })
    }

  /**
   * The rule worth a test of its own: one source's refresh must never delete another's books.
   * Before ingestion existed, removal was "every local row absent from the fetch" — safe with one
   * source, and a library-wipe with two.
   */
  @Test
  fun `ingesting one source leaves another source's books alone`() =
    runTest {
      every { bookDao.getAudiobooks(any()) } returns listOf(book("a"), book("z", source = other))
      val removed = slot<List<String>>()
      every { bookDao.removeAll(capture(removed)) } returns 0

      repository().ingest(listOf(book("a")), plex, SourceCapabilities())

      assertEquals(emptyList<String>(), removed.captured)
    }

  @Test
  fun `a book this source no longer lists is removed and counted`() =
    runTest {
      every { bookDao.getAudiobooks(any()) } returns listOf(book("a"), book("gone"))
      every { bookDao.removeAll(any()) } returns 1

      val count = repository().ingest(listOf(book("a")), plex, SourceCapabilities())

      assertEquals(1, count)
      coVerify { bookDao.removeAll(listOf("gone")) }
    }

  /**
   * A source declaring neither narrator nor series must not pay for a tag-seeding pass — the
   * endpoints are Plex-specific and would cost `1 + N` requests to learn nothing.
   */
  @Test
  fun `a source with no tag capabilities does not hit the tag endpoints`() =
    runTest {
      every { bookDao.getAudiobooks(any()) } returns emptyList()

      repository().ingest(listOf(book("a")), other, SourceCapabilities())

      coVerify(exactly = 0) { plexMediaService.retrieveFilterChoices(any(), any()) }
    }

  /** Listening position is never overwritten by an incoming copy (decision-16). */
  @Test
  fun `local progress survives an ingest`() =
    runTest {
      every { bookDao.getAudiobooks(any()) } returns listOf(book("a", progress = 900_000L))
      val inserted = slot<List<Audiobook>>()
      every { bookDao.insertAll(capture(inserted)) } returns Unit

      repository().ingest(listOf(book("a", progress = 0L)), plex, SourceCapabilities())

      assertEquals(900_000L, inserted.captured.single().progress)
    }
}
