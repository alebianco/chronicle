package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
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
 * cu-127's acceptance criterion: **two sources cannot merge into one list**.
 *
 * Against real databases and through the real repositories, because the claim is about what a
 * *read* returns and a mocked DAO would simply return whatever it was told to. The task file says
 * as much: "a missed filter fails silently by showing a union, exactly the symptom this removes,
 * so the acceptance criterion is 'two sources cannot merge', tested — not 'filters were added'."
 *
 * Every case seeds rows from two servers into one database and asserts the reads see only one.
 * A test seeding a single source would pass whether the scoping worked or not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SourceIsolationTest {
  private lateinit var bookDb: BookDatabase
  private lateinit var trackDb: TrackDatabase
  private lateinit var collectionsDb: CollectionsDatabase

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { offlineMode } returns false
    }

  /** Connected to [TEST_SOURCE]'s server. [OTHER_TEST_SOURCE]'s rows must stay invisible. */
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

  private fun collectionsRepository() =
    CollectionsRepository(
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      collectionsDao = collectionsDb.collectionsDao,
      dispatchers = TestDispatcherProvider(),
    )

  /**
   * Note the ids differ.
   *
   * Two servers *can* both hold rating key `1001`, and that is decision-21's motivating case — but
   * `Audiobook.id` is still the sole primary key, so such a pair collides on insert and the second
   * row replaces the first. **Scoping the reads does not fix that**, and this task does not claim
   * to: a composite `(id, source)` key was considered and rejected in decision-21's "why not
   * composite ids". What scoping fixes is the union — the two servers' *distinct* books appearing
   * in one list — which is what these cases assert. The colliding-key case is pinned separately
   * below so the limitation is recorded rather than discovered later.
   */
  private fun seedTwoServers() {
    bookDb.bookDao.insertAll(
      listOf(
        Audiobook(id = "1001", source = TEST_SOURCE, title = "Ours", titleSort = "Ours", isCached = true),
        Audiobook(id = "1002", source = OTHER_TEST_SOURCE, title = "Theirs", titleSort = "Theirs"),
        Audiobook(id = "2002", source = OTHER_TEST_SOURCE, title = "Also theirs", titleSort = "Also theirs"),
      ),
    )
  }

  @Test
  fun `the library list shows only the connected server's books`() =
    runTest {
      seedTwoServers()

      val titles = bookRepository().getAllBooks().first().map { it.title }

      assertEquals(listOf("Ours"), titles)
    }

  @Test
  fun `the book count counts only the connected server's books`() =
    runTest {
      seedTwoServers()

      assertEquals(1, bookRepository().getBookCount())
    }

  @Test
  fun `search does not reach another server's books`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          Audiobook(id = "1", source = TEST_SOURCE, title = "Dune", author = "Herbert"),
          Audiobook(id = "2", source = OTHER_TEST_SOURCE, title = "Dune Messiah", author = "Herbert"),
        ),
      )

      val titles = bookRepository().search("Dune").first().map { it.title }

      assertEquals(listOf("Dune"), titles)
    }

  @Test
  fun `the downloaded list does not reach another server's books`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          Audiobook(id = "1", source = TEST_SOURCE, title = "Ours", isCached = true),
          Audiobook(id = "2", source = OTHER_TEST_SOURCE, title = "Theirs", isCached = true),
        ),
      )

      val titles = bookRepository().getCachedAudiobooksAsync().map { it.title }

      assertEquals(listOf("Ours"), titles)
    }

  /**
   * The home screen's shelves. These order by a timestamp and take a fixed number, so another
   * server's book does not merely appear — it can *displace* one of ours out of the list.
   */
  @Test
  fun `the recently-added shelf does not reach another server's books`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          Audiobook(id = "1", source = TEST_SOURCE, title = "Ours", addedAt = 100L),
          Audiobook(id = "2", source = OTHER_TEST_SOURCE, title = "Theirs", addedAt = 999L),
        ),
      )

      val titles = bookRepository().getRecentlyAddedAsync().map { it.title }

      assertEquals("a newer book on another server must not displace ours", listOf("Ours"), titles)
    }

  @Test
  fun `the most recently played book is never another server's`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          Audiobook(id = "1", source = TEST_SOURCE, title = "Ours", lastViewedAt = 100L),
          Audiobook(id = "2", source = OTHER_TEST_SOURCE, title = "Theirs", lastViewedAt = 999L),
        ),
      )

      assertEquals("Ours", bookRepository().getMostRecentlyPlayed().title)
    }

  /**
   * Tracks carry their own scope rather than inheriting it through `parentKey`, because this read
   * filters on no book at all — see the KDoc on [MediaItemTrack.source].
   */
  @Test
  fun `the track list does not reach another server's tracks`() =
    runTest {
      trackDb.trackDao.insertAll(
        listOf(
          MediaItemTrack(id = "2001", parentKey = "1001", source = TEST_SOURCE, title = "Ours"),
          MediaItemTrack(id = "3001", parentKey = "9999", source = OTHER_TEST_SOURCE, title = "Theirs"),
        ),
      )

      val titles = trackRepository().getAllTracksAsync().map { it.title }

      assertEquals(listOf("Ours"), titles)
    }

  @Test
  fun `the collections list does not reach another server's collections`() =
    runTest {
      collectionsDb.collectionsDao.insertAll(
        listOf(
          Collection(id = "c1", source = TEST_SOURCE, title = "Ours"),
          Collection(id = "c2", source = OTHER_TEST_SOURCE, title = "Theirs"),
        ),
      )

      val titles = collectionsRepository().getAllCollections().first().map { it.title }

      assertEquals(listOf("Ours"), titles)
    }

  /**
   * The legacy scope is a real scope, not a wildcard.
   *
   * Rows migrated from before cu-127 carry [SourceId.LEGACY_PLEX]. A connected server must not see
   * them as its own until a refresh adopts them — otherwise the migration would silently merge an
   * upgrading user's old library into whichever server they happen to connect to next.
   */
  @Test
  fun `legacy rows are not visible to a resolved source`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(Audiobook(id = "1", source = SourceId.LEGACY_PLEX, title = "From before the upgrade")),
      )

      assertEquals(emptyList<Audiobook>(), bookRepository().getAllBooks().first())
    }

  /**
   * The limitation this task does **not** remove, pinned so it is recorded rather than rediscovered.
   *
   * `Audiobook.id` remains the sole primary key, so two servers holding the same Plex rating key
   * still collide: the second insert replaces the first, whatever its source. decision-21 chose
   * this deliberately over a composite `(id, source)` key, whose cost is that every id parse and
   * format site becomes load-bearing — cu-71's lesson.
   *
   * Scoping the reads is still worth having on its own: it removes the *union*, which is what a
   * user actually sees. Making two same-key books coexist is a separate, larger change, and this
   * test is the note that says so.
   */
  @Test
  fun `two servers sharing a rating key still collide on the primary key`() {
    bookDb.bookDao.insertAll(
      listOf(
        Audiobook(id = "1001", source = TEST_SOURCE, title = "Ours"),
        Audiobook(id = "1001", source = OTHER_TEST_SOURCE, title = "Theirs"),
      ),
    )

    val all = bookDb.bookDao.getAudiobooks(TEST_SOURCE) + bookDb.bookDao.getAudiobooks(OTHER_TEST_SOURCE)
    assertEquals("a composite key would keep both; today the later insert wins", 1, all.size)
    assertEquals(OTHER_TEST_SOURCE, all.single().source)
  }

  /**
   * Deleting downloads and forgetting them must agree on scope.
   *
   * `CachedFileManager.uncacheAllInLibrary` deletes only files whose names come from
   * `getCachedTracks()`, which is source-scoped — so clearing the `cached` flag for *every*
   * source would report another server's downloads as absent while they sat on disk, invisible
   * both to the user and to cu-81's prune, which only removes files it can account for.
   */
  @Test
  fun `forgetting downloads leaves another source's downloads alone`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          Audiobook(id = "1", source = TEST_SOURCE, title = "Ours", isCached = true),
          Audiobook(id = "2", source = OTHER_TEST_SOURCE, title = "Theirs", isCached = true),
        ),
      )

      bookRepository().uncacheAll()

      assertEquals(
        "another server's book must still know it is downloaded",
        true,
        bookDb.bookDao.getAudiobooks(OTHER_TEST_SOURCE).single().isCached,
      )
      assertEquals(false, bookDb.bookDao.getAudiobooks(TEST_SOURCE).single().isCached)
    }

  /**
   * Why cu-127 does **not** move downloads to a per-source directory.
   *
   * decision-21 specified `<cachedMediaDir>/<sourceId>/<trackId>.<ext>`, on the grounds that two
   * servers can mint the same track id and so the same filename. That is true of the *ids*, but
   * the collision is unreachable: `MediaItemTrack.id` is the sole primary key, so two tracks
   * sharing an id cannot both exist as rows — the second insert replaces the first — and a file is
   * only ever written for a row that exists. One row means one filename.
   *
   * So the path change would buy nothing today while touching four file paths that each carry
   * documented data-loss history: cu-85 (an unreadable directory must change nothing), cu-81 (the
   * prune only scans the active directory), cu-153 (a partial and a finished download are
   * indistinguishable by name, so a move must carry both), and cu-76 (a partial must not be
   * promoted to "downloaded"). Its failure mode is deleted audio, not a wrong list.
   *
   * This test is the record of that reasoning, and the tripwire: it fails the moment the primary
   * key stops being the thing that prevents the collision — which is exactly when the per-source
   * path becomes necessary.
   */
  @Test
  fun `a track id collision is prevented by the primary key, not by the download path`() =
    runTest {
      trackDb.trackDao.insertAll(
        listOf(
          MediaItemTrack(id = "2001", parentKey = "1", source = TEST_SOURCE, media = "/a/x.mp3"),
          MediaItemTrack(id = "2001", parentKey = "9", source = OTHER_TEST_SOURCE, media = "/b/y.mp3"),
        ),
      )

      val both =
        trackDb.trackDao.getAllTracksAsync(TEST_SOURCE) +
          trackDb.trackDao.getAllTracksAsync(OTHER_TEST_SOURCE)
      assertEquals(
        "if two same-id tracks ever coexist, they can also collide on disk and the per-source " +
          "download path decision-21 describes becomes necessary",
        1,
        both.size,
      )
    }
}
