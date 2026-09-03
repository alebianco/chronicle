package io.github.mattpvaughn.chronicle.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.testing.FakePlexServer
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * A library refresh fills in narrator and series for books nobody has opened (cu-143).
 *
 * Fixture-backed and end-to-end through the real `refreshData`, because the claim is about what a
 * refresh *does to the database* — the pure merge rule is covered by `TagIndexSeederTest`, and a
 * mocked service would prove only that the seeder was called.
 *
 * The fixtures encode the route: `/library/sections/1/style` lists two narrators,
 * `/all?type=9&style=301` lists the books one of them read, and the same shape for `mood`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TagIndexSeedingRefreshTest {
  @get:Rule
  val plexServer = FakePlexServer()

  private lateinit var bookDb: BookDatabase
  private val chapterDao = mockk<ChapterDao>(relaxed = true)

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { offlineMode } returns false
      every { lastRefreshTimeStamp = any() } returns Unit
    }

  private val plexPrefsRepo =
    mockk<PlexPrefsRepo>(relaxed = true) {
      every { library } returns PlexLibrary(name = "Books", type = MediaType.ARTIST, id = "1")
    }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    bookDb =
      Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    bookDb.close()
  }

  private val mediaService: PlexMediaService by lazy {
    Retrofit.Builder()
      .baseUrl(plexServer.url)
      .client(OkHttpClient())
      .addConverterFactory(
        MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
      )
      .build()
      .create(PlexMediaService::class.java)
  }

  private fun TestScope.repository() =
    BookRepository(
      bookDao = bookDb.bookDao,
      chapterDao = chapterDao,
      prefsRepo = prefsRepo,
      plexPrefsRepo = plexPrefsRepo,
      plexMediaService = mediaService,
      dispatchers = TestDispatcherProvider(testScheduler),
    )

  /**
   * The headline: a book nobody opened comes back from a refresh knowing its narrator.
   *
   * Before cu-143 this was empty until the user opened the book, because `Style` is absent from
   * the listing the refresh reads.
   */
  @Test
  fun `a refresh seeds the narrator of a book that was never opened`() =
    runTest {
      repository().refreshData()

      val mistborn = bookDb.bookDao.getAudiobooks().first { it.id == "1003" }
      assertEquals("Michael Kramer", mistborn.narrator)
    }

  @Test
  fun `a refresh seeds the series, with the Series prefix stripped`() =
    runTest {
      repository().refreshData()

      val mistborn = bookDb.bookDao.getAudiobooks().first { it.id == "1003" }
      assertEquals("Mistborn", mistborn.series)
    }

  @Test
  fun `a book under a different narrator gets that one`() =
    runTest {
      repository().refreshData()

      val hobbit = bookDb.bookDao.getAudiobooks().first { it.id == "1001" }
      assertEquals("Rob Inglis", hobbit.narrator)
    }

  /** A book carrying no tag stays empty rather than inheriting someone else's. */
  @Test
  fun `a book in no tag listing keeps an empty narrator`() =
    runTest {
      repository().refreshData()

      val dune = bookDb.bookDao.getAudiobooks().first { it.id == "1002" }
      assertEquals("", dune.narrator)
      assertEquals("", dune.series)
    }

  /**
   * A narrator already learned from the book's own detail response survives the refresh.
   *
   * This is the refresh-blanking risk cu-24 documented, in a new guise: the index is coarser than
   * the detail response, so it must never overwrite it.
   */
  @Test
  fun `a refresh does not overwrite a narrator learned from the detail response`() =
    runTest {
      bookDb.bookDao.insertAll(
        listOf(
          Audiobook(id = "1003", source = 1L, title = "Mistborn Book 10", narrator = "Kate Reading"),
        ),
      )

      repository().refreshData()

      val mistborn = bookDb.bookDao.getAudiobooks().first { it.id == "1003" }
      assertEquals("Kate Reading", mistborn.narrator)
    }

  /** The refresh must still work when the server refuses the tag endpoints entirely. */
  @Test
  fun `a refresh succeeds when the tag endpoints are unavailable`() =
    runTest {
      plexServer.stubFailure("/library/sections/1/style")
      plexServer.stubFailure("/library/sections/1/mood")

      repository().refreshData()

      // The books are still there — only the seeding was skipped.
      assertEquals(3, bookDb.bookDao.getAudiobooks().size)
    }
}
