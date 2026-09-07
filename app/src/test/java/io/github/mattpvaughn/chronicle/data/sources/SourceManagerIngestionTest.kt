package io.github.mattpvaughn.chronicle.data.sources

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * A library round-trips from a `MediaSource` into the repository, with **no Plex fixtures**.
 *
 * `SourceManager.refreshBooks` was a `check` that threw until the multi-backend seam was wired up,
 * so nothing here was reachable: a source could be registered but never ingested. These assertions
 * are what the seam exists to make possible.
 */
class SourceManagerIngestionTest {
  /** A backend that is not Plex, declaring only what a file-based source can answer. */
  private class FakeSource(
    override val id: SourceId,
    private val books: Result<List<Audiobook>, Throwable>,
    override val hasNarrator: Boolean = false,
    override val hasSeries: Boolean = false,
    override val hasServerProgress: Boolean = false,
  ) : MediaSource {
    override val dataSourceFactory: androidx.media3.datasource.DefaultDataSource.Factory
      get() = throw UnsupportedOperationException("not needed to ingest")
    override val isDownloadable: Boolean = false

    override suspend fun fetchAudiobooks(): Result<List<Audiobook>, Throwable> = books

    override suspend fun fetchTracks(): Result<List<MediaItemTrack>, Throwable> = Ok(emptyList())
  }

  private fun book(id: String) = EMPTY_AUDIOBOOK.copy(id = id, title = "Book $id")

  private fun manager(repo: IBookRepository) = SourceManager(bookRepository = repo, trackRepository = mockk(relaxed = true))

  @Test
  fun `a fetched library reaches the repository, attributed to its source`() =
    runTest {
      val repo = mockk<IBookRepository>(relaxed = true)
      val books = slot<List<Audiobook>>()
      val sourceId = slot<SourceId>()
      coEvery { repo.ingest(capture(books), capture(sourceId), any()) } returns 0

      manager(repo).apply { addSource(FakeSource(id = SourceId.forPlexServer("fake-7"), books = Ok(listOf(book("a"), book("b"))))) }

      assertEquals(listOf("a", "b"), books.captured.map { it.id })
      assertEquals(SourceId.forPlexServer("fake-7"), sourceId.captured)
    }

  /** The capability flags reach the repository as declared, rather than being assumed true. */
  @Test
  fun `a source's capabilities are passed to ingestion`() =
    runTest {
      val repo = mockk<IBookRepository>(relaxed = true)
      val caps = slot<SourceCapabilities>()
      coEvery { repo.ingest(any(), any(), capture(caps)) } returns 0

      manager(repo).apply {
        addSource(FakeSource(id = SourceId.forPlexServer("fake-7"), books = Ok(listOf(book("a"))), hasNarrator = true))
      }

      assertEquals(true, caps.captured.hasNarrator)
      assertEquals(false, caps.captured.hasSeries)
      assertEquals(false, caps.captured.hasServerProgress)
    }

  /**
   * A source whose fetch fails must not reach ingestion at all.
   *
   * Passing an empty list through on failure would be the worst possible outcome: `planIngestion`
   * treats "this source lists nothing" as authoritative for *its own* books, so a transient network
   * error would delete the user's library rather than skip a refresh.
   */
  @Test
  fun `a failed fetch does not ingest`() =
    runTest {
      val repo = mockk<IBookRepository>(relaxed = true)

      manager(repo).apply { addSource(FakeSource(id = SourceId.forPlexServer("fake-7"), books = Err(IOException("offline")))) }

      coVerify(exactly = 0) { repo.ingest(any(), any(), any()) }
    }

  /** One failing source does not stop the others. */
  @Test
  fun `a healthy source still ingests when another fails`() =
    runTest {
      val repo = mockk<IBookRepository>(relaxed = true)
      val ids = mutableListOf<SourceId>()
      coEvery { repo.ingest(any(), capture(ids), any()) } returns 0

      manager(repo).apply {
        addSource(FakeSource(id = SourceId.forPlexServer("fake-1"), books = Err(IOException("offline"))))
        addSource(FakeSource(id = SourceId.forPlexServer("fake-2"), books = Ok(listOf(book("a")))))
      }

      assertEquals("only the healthy source should ingest", listOf(SourceId.forPlexServer("fake-2")), ids.distinct())
    }
}
