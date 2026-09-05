package io.github.mattpvaughn.chronicle.data.sources.plex

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.testing.FakePlexServer
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Route B — one multi-id request answering narrator **and** series — against a fixture captured
 * from a real Plex 1.43 server (cu-156).
 *
 * The fixture is `multi-id-real-shape.json`, five books chosen to cover every tag combination:
 * both tags, narrator only, series only, neither. Captured rather than hand-written, because a
 * fixture written to match the code proves nothing — that is exactly how `plexGenres` carried the
 * wrong `@Json` name for the life of the project (cu-24).
 */
class MultiIdTagSeedingTest {
  @get:Rule
  val plex = FakePlexServer()

  private val service: PlexMediaService by lazy {
    Retrofit.Builder()
      .baseUrl(plex.url)
      .client(OkHttpClient())
      .addConverterFactory(
        MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
      )
      .build()
      .create(PlexMediaService::class.java)
  }

  private fun seeder(): TagIndexSeeder {
    val prefs = mockk<PlexPrefsRepo>(relaxed = true)
    every { prefs.library } returns mockk(relaxed = true) { every { id } returns "14" }
    return TagIndexSeeder(service, prefs, TestDispatcherProvider())
  }

  private companion object {
    /** Every book in `multi-id-real-shape.json`: both tags, narrator only, series only, neither. */
    val ALL_FIXTURE_IDS = listOf("151171", "151314", "151053", "151794", "155645")
  }

  @Test
  fun `one pass reads both narrator and series from the real response shape`() =
    runTest {
      val associations = seeder().readAssociationsByIds(ALL_FIXTURE_IDS)

      val narrators = associations.filter { it.filter == TagFilter.STYLE }
      val series = associations.filter { it.filter == TagFilter.MOOD }

      assertTrue("the captured response must yield narrators", narrators.isNotEmpty())
      assertTrue("and series, from the same pass", series.isNotEmpty())
    }

  /** The `Series:` prefix the Audnexus convention writes must be stripped, as on the detail path. */
  @Test
  fun `the series prefix is stripped`() =
    runTest {
      val series = seeder().readAssociationsByIds(ALL_FIXTURE_IDS).filter { it.filter == TagFilter.MOOD }
      assertTrue(
        "a seeded series must not keep the 'Series:' prefix, got ${series.map { it.value }}",
        series.none { it.value.startsWith("Series", ignoreCase = true) },
      )
    }

  /** The merge rule cu-143 established, restated for this route: never overwrite a known value. */
  @Test
  fun `seeding never overwrites a value the book already knows`() =
    runTest {
      val associations = seeder().readAssociationsByIds(ALL_FIXTURE_IDS)
      val known =
        Audiobook(
          id = "151171",
          source = TEST_SOURCE,
          title = "The Wisdom of Crowds",
          narrator = "A Real Narrator",
          series = "A Real Series",
        )

      val seeded = listOf(known).withSeededTags(associations).single()

      assertEquals("a known narrator must survive seeding", "A Real Narrator", seeded.narrator)
      assertEquals("a known series must survive seeding", "A Real Series", seeded.series)
    }

  @Test
  fun `a book that knows nothing takes what the index offers`() =
    runTest {
      // The fake server answers the whole captured fixture whatever ids are asked for, so this
      // asks for the full set and asserts on 151171, which really does carry both tags.
      val associations = seeder().readAssociationsByIds(ALL_FIXTURE_IDS)
      val blank = Audiobook(id = "151171", source = TEST_SOURCE, title = "The Wisdom of Crowds")

      val seeded = listOf(blank).withSeededTags(associations).single()

      assertTrue("an unknown narrator must be filled in", seeded.narrator.isNotEmpty())
      assertTrue("an unknown series must be filled in", seeded.series.isNotEmpty())
    }

  @Test
  fun `no ids means no requests and no associations`() =
    runTest {
      assertEquals(emptyList<TagAssociation>(), seeder().readAssociationsByIds(emptyList()))
    }
}
