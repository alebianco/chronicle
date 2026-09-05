package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.testing.OTHER_TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.TEST_SERVER_ID
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rows migrated from before cu-127 are adopted by the first server that connects.
 *
 * The migrations write [SourceId.LEGACY_PLEX] because the real server id is not knowable from
 * inside a `SupportSQLiteDatabase`. Without something to claim them, an upgrading user's whole
 * library would be invisible to every scoped read until a full refresh finished — books, tracks
 * and listening positions all present in the database and none of them on screen.
 *
 * The rule is deliberately narrow: adoption claims **only** rows carrying the legacy marker, never
 * rows belonging to a resolved source. A second server must not be able to steal the first's
 * library, which is the whole point of scoping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LegacyRowAdoptionTest {
  private lateinit var bookDb: BookDatabase
  private lateinit var trackDb: TrackDatabase

  private val prefsRepo = mockk<PrefsRepo>(relaxed = true) { every { offlineMode } returns false }

  private val plexPrefsRepo =
    mockk<PlexPrefsRepo>(relaxed = true) {
      every { server } returns ServerModel(name = "A", connections = emptyList(), serverId = TEST_SERVER_ID)
    }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    bookDb = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java).allowMainThreadQueries().build()
    trackDb = Room.inMemoryDatabaseBuilder(context, TrackDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    bookDb.close()
    trackDb.close()
  }

  private fun bookRepository() =
    BookRepository(
      bookDao = bookDb.bookDao,
      chapterDao = mockk(relaxed = true),
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      dispatchers = TestDispatcherProvider(),
    )

  private fun trackRepository() =
    TrackRepository(
      trackDao = trackDb.trackDao,
      prefsRepo = prefsRepo,
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      plexPrefs = plexPrefsRepo,
      dispatchers = TestDispatcherProvider(),
    )

  @Test
  fun `a legacy book becomes visible once adopted`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(Audiobook(id = "1", source = SourceId.LEGACY_PLEX, title = "From before", progress = 5000L)),
      )
      val repo = bookRepository()
      assertEquals("before adoption the row must be invisible", emptyList<Audiobook>(), repo.getAllBooks().first())

      repo.adoptLegacyRows()

      val adopted = repo.getAllBooks().first()
      assertEquals(listOf("From before"), adopted.map { it.title })
      assertEquals("adoption must not disturb the user's place", 5000L, adopted.single().progress)
    }

  @Test
  fun `adoption never claims another source's books`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          Audiobook(id = "1", source = SourceId.LEGACY_PLEX, title = "Legacy"),
          Audiobook(id = "2", source = OTHER_TEST_SOURCE, title = "Another server's"),
        ),
      )

      bookRepository().adoptLegacyRows()

      assertEquals(
        "a second server must not be able to steal the first's library",
        OTHER_TEST_SOURCE,
        bookDb.bookDao.getAudiobooks(OTHER_TEST_SOURCE).single().source,
      )
      assertEquals(listOf("Legacy"), bookDb.bookDao.getAudiobooks(TEST_SOURCE).map { it.title })
    }

  @Test
  fun `legacy tracks are adopted too`() =
    runTest {
      trackDb.trackDao.insertAll(
        listOf(
          MediaItemTrack(id = "2001", parentKey = "1", source = SourceId.LEGACY_PLEX, progress = 4242L),
          MediaItemTrack(id = "3001", parentKey = "9", source = OTHER_TEST_SOURCE),
        ),
      )
      val repo = trackRepository()
      assertEquals(emptyList<MediaItemTrack>(), repo.getAllTracksAsync())

      repo.adoptLegacyRows()

      val adopted = repo.getAllTracksAsync()
      assertEquals(listOf("2001"), adopted.map { it.id })
      assertEquals("a track's position must survive adoption", 4242L, adopted.single().progress)
    }

  /**
   * Running it twice must be harmless — it runs on every launch, and by the second one there is
   * nothing left carrying the marker.
   */
  @Test
  fun `adoption is idempotent`() =
    runTest {
      bookDb.bookDao.insertAll(listOf(Audiobook(id = "1", source = SourceId.LEGACY_PLEX, title = "Legacy")))
      val repo = bookRepository()

      repo.adoptLegacyRows()
      repo.adoptLegacyRows()

      assertEquals(listOf("Legacy"), repo.getAllBooks().first().map { it.title })
    }

  /** With no server chosen there is nothing to adopt *to*, so the rows must be left alone. */
  @Test
  fun `an unresolved source adopts nothing`() =
    runTest {
      every { plexPrefsRepo.server } returns null
      bookDb.bookDao.insertAll(listOf(Audiobook(id = "1", source = SourceId.LEGACY_PLEX, title = "Legacy")))

      bookRepository().adoptLegacyRows()

      assertEquals(
        "the marker must survive so a later launch can still adopt it",
        SourceId.LEGACY_PLEX,
        bookDb.bookDao.getAudiobooks(SourceId.LEGACY_PLEX).single().source,
      )
    }
}
