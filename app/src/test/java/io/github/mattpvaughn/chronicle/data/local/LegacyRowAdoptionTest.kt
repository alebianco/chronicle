package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.model.SourceId
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainer
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.testing.OTHER_TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.TEST_SERVER_ID
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
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
 * Rows migrated from before the source-scoping change are adopted by the first server that connects.
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
  private lateinit var collectionsDb: CollectionsDatabase

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
    collectionsDb =
      Room.inMemoryDatabaseBuilder(context, CollectionsDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    bookDb.close()
    trackDb.close()
    collectionsDb.close()
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

  private fun emptyContainer() = PlexMediaContainerWrapper(PlexMediaContainer())

  /** One collection, shaped as `/library/sections/N/collections` returns it. */
  private fun collectionsResponse(
    id: String,
    title: String,
  ) = PlexMediaContainerWrapper(
    PlexMediaContainer(
      metadata = listOf(PlexDirectory(ratingKey = id, title = title, type = "collection")),
      size = 1,
      totalSize = 1,
      offset = 0,
    ),
  )

  private fun collectionsRepository() =
    CollectionsRepository(
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      collectionsDao = collectionsDb.collectionsDao,
      dispatchers = TestDispatcherProvider(),
    )

  // ---- collections ----

  /**
   * The hardcoded-`SourceId.UNKNOWN` bug, as a test.
   *
   * `Collection.from` hardcoded `SourceId.UNKNOWN` and the repository never resolved a real one,
   * while `getAllCollections` and `hasCollections` both filter by `currentSourceId`. So every
   * stored collection was invisible to every read of it and the Collections tab — gated on
   * `hasCollections` — was hidden for every user on every library. Measured on the household
   * tablet: four real collections, all with an empty source.
   */
  @Test
  fun `an unscoped collection becomes visible once adopted`() =
    runTest {
      collectionsDb.collectionsDao.insertAll(
        listOf(Collection(id = "c1", source = SourceId.UNKNOWN, title = "Darkover")),
      )
      val repo = collectionsRepository()
      assertEquals(
        "before adoption an UNKNOWN-scoped row must be invisible",
        emptyList<Collection>(),
        repo.getAllCollections().first(),
      )

      repo.adoptUnscopedRows()

      assertEquals(listOf("Darkover"), repo.getAllCollections().first().map { it.title })
      assertEquals("the tab is gated on this", true, repo.hasCollections().first())
    }

  /** The migration's marker is claimed too, exactly as books and tracks do. */
  @Test
  fun `a legacy collection is adopted as well`() =
    runTest {
      collectionsDb.collectionsDao.insertAll(
        listOf(Collection(id = "c1", source = SourceId.LEGACY_PLEX, title = "From before")),
      )
      val repo = collectionsRepository()

      repo.adoptUnscopedRows()

      assertEquals(listOf("From before"), repo.getAllCollections().first().map { it.title })
    }

  /**
   * The same narrowness books enforce: adoption claims rows belonging to *no* server, never rows
   * belonging to another one. An unscoped row has no owner to steal it from; a resolved one does.
   */
  @Test
  fun `adoption never claims another source's collections`() =
    runTest {
      collectionsDb.collectionsDao.insertAll(
        listOf(
          Collection(id = "c1", source = SourceId.UNKNOWN, title = "Unscoped"),
          Collection(id = "c2", source = OTHER_TEST_SOURCE, title = "Another server's"),
        ),
      )

      collectionsRepository().adoptUnscopedRows()

      assertEquals(
        "a second server must not be able to steal the first's collections",
        OTHER_TEST_SOURCE,
        collectionsDb.collectionsDao.getCollections(OTHER_TEST_SOURCE).single().source,
      )
      assertEquals(
        listOf("Unscoped"),
        collectionsDb.collectionsDao.getCollections(TEST_SOURCE).map { it.title },
      )
    }

  /**
   * The fix itself, not just the repair: a refresh must **stamp** the connected server's scope.
   *
   * Adoption alone would make this test suite pass over a still-broken write path — every launch
   * would rescue rows the previous refresh had just orphaned. This asserts the row is written
   * correctly in the first place, which is what stops the bug recurring the moment adoption is
   * removed.
   */
  @Test
  fun `a refresh stamps collections with the connected server's scope`() =
    runTest {
      val service =
        mockk<PlexMediaService>(relaxed = true) {
          coEvery { retrieveCollectionsPaginated(any(), any(), any()) } returns
            collectionsResponse(id = "c1", title = "Darkover")
          coEvery { fetchBooksInCollection(any()) } returns emptyContainer()
        }
      val repo =
        CollectionsRepository(
          plexMediaService = service,
          prefsRepo = prefsRepo,
          plexPrefsRepo = plexPrefsRepo,
          collectionsDao = collectionsDb.collectionsDao,
          dispatchers = TestDispatcherProvider(),
        )

      repo.refreshCollectionsPaginated()

      assertEquals(
        "a refresh must write the resolved scope, not SourceId.UNKNOWN",
        TEST_SOURCE,
        collectionsDb.collectionsDao.getCollections(TEST_SOURCE).single().source,
      )
      assertEquals("and the tab must light up", true, repo.hasCollections().first())
    }

  /**
   * An unresolved scope writes nothing rather than filing rows under a key no later refresh can
   * match — the same rule `planIngestion` applies to books. `UNKNOWN` reaches here
   * mid-login or after a `clear()`.
   */
  @Test
  fun `a refresh with no server resolved writes nothing`() =
    runTest {
      val noServer =
        mockk<PlexPrefsRepo>(relaxed = true) {
          every { server } returns null
          every { library } returns PlexLibrary(name = "Books", type = MediaType.ARTIST, id = "1")
        }
      val service =
        mockk<PlexMediaService>(relaxed = true) {
          coEvery { retrieveCollectionsPaginated(any(), any(), any()) } returns
            collectionsResponse(id = "c1", title = "Darkover")
          coEvery { fetchBooksInCollection(any()) } returns emptyContainer()
        }

      CollectionsRepository(
        plexMediaService = service,
        prefsRepo = prefsRepo,
        plexPrefsRepo = noServer,
        collectionsDao = collectionsDb.collectionsDao,
        dispatchers = TestDispatcherProvider(),
      ).refreshCollectionsPaginated()

      assertEquals(
        "an unresolved scope must store nothing, not an UNKNOWN-scoped row",
        emptyList<Collection>(),
        collectionsDb.collectionsDao.getCollections(SourceId.UNKNOWN),
      )
    }

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
