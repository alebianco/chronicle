package io.github.mattpvaughn.chronicle.testing

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.data.sources.plex.model.UsersResponse
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asAudiobooks
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asCollections
import io.github.mattpvaughn.chronicle.data.sources.plex.model.asTrackList
import io.github.mattpvaughn.chronicle.data.sources.plex.model.toChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the fixtures still deserialize into the domain objects the app uses.
 *
 * A fixture that has silently drifted from its model is worse than no fixture:
 * every test built on it keeps passing while asserting nothing real. Moshi runs
 * in reflection mode here and fills absent fields with defaults rather than
 * failing, so a renamed JSON key produces an empty list, not an exception —
 * which is exactly how that drift stays invisible. These tests assert on
 * *values*, so a mismatch fails loudly.
 */
class PlexFixtureContractTest {
  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

  private fun container(fixture: String): PlexMediaContainerWrapper =
    moshi.adapter(PlexMediaContainerWrapper::class.java)
      .fromJson(FakePlexServer.fixture(fixture))
      ?: error("Failed to parse $fixture")

  @Test
  fun `libraries fixture maps to library directories`() {
    val directories = container("libraries.json").plexMediaContainer.plexDirectories

    assertEquals(2, directories.size)
    assertEquals("Audiobooks", directories.first().title)
    assertEquals("1", directories.first().key)
  }

  @Test
  fun `albums fixture maps to audiobooks with metadata intact`() {
    val books = container("albums.json").plexMediaContainer.asAudiobooks()

    assertEquals(3, books.size)
    val hobbit = books.first { it.title == "The Hobbit" }
    assertEquals("1001", hobbit.id)
    assertEquals("J R R Tolkien", hobbit.author)
    assertEquals("Hobbit, The", hobbit.titleSort)
    assertEquals(1937, hobbit.year)
    // Audiobook.from deliberately does NOT read duration from the album
    // response — it is a local field derived from the track list, so it stays 0
    // until tracks are loaded. Asserted here so the next reader does not
    // "fix" the fixture to chase a non-bug.
    assertEquals("duration is derived from tracks, not the album", 0L, hobbit.duration)
    // Guards the plexGenres key: the model has no @Json rename, so a fixture
    // using Plex's wire name "Genre" would silently yield an empty string here.
    assertEquals("Fantasy", hobbit.genre)
  }

  @Test
  fun `albums fixture exercises natural sort ordering`() {
    // "Mistborn Book 10" exists specifically so cu-4's comparator has a
    // numeric-series case to work on in fixture-backed tests.
    val titles = container("albums.json").plexMediaContainer.asAudiobooks().map { it.title }
    assertTrue(titles.any { it.contains("Book 10") })
  }

  @Test
  fun `tracks fixture maps to a track list with progress and parts`() {
    val tracks = container("tracks.json").plexMediaContainer.asTrackList()

    assertEquals(3, tracks.size)
    val first = tracks.first()
    assertEquals("2001", first.id)
    assertEquals("1001", first.parentKey)
    assertEquals("An Unexpected Party", first.title)
    assertEquals("listening progress survives deserialization", 1500L, first.progress)
    assertEquals(5000L, first.duration)
    assertTrue("media part key is present", first.media.isNotEmpty())
  }

  @Test
  fun `tracks fixture carries a multi-disc track`() {
    // parentIndex 2 on the last track: disc handling is a real source of
    // ordering bugs, so the fixture set has to contain the case.
    val discNumbers = container("tracks.json").plexMediaContainer.asTrackList().map { it.discNumber }
    assertTrue("fixture must include more than one disc", discNumbers.toSet().size > 1)
  }

  @Test
  fun `chapter fixture maps to chapters including the untitled fallback`() {
    val directory = container("track-with-chapters.json").plexMediaContainer.metadata.single()
    val chapters =
      directory.plexChapters.map {
        it.toChapter(trackId = "2001", trackDiscNumber = 1, downloaded = false)
      }

    assertEquals(3, chapters.size)
    assertEquals("Chapter 1: An Unexpected Party", chapters[0].title)
    assertEquals(0L, chapters[0].startTimeOffset)
    assertEquals(1600L, chapters[0].endTimeOffset)
    // The third chapter has an empty tag on purpose: it exercises the
    // "Chapter $index" fallback in PlexChapter.toChapter.
    assertEquals("Chapter 3", chapters[2].title)
  }

  @Test
  fun `collections fixture maps to collections`() {
    val collections = container("collections.json").plexMediaContainer.asCollections()

    assertEquals(1, collections.size)
    assertEquals("Middle-earth", collections.first().title)
  }

  @Test
  fun `empty library fixture yields no books rather than failing`() {
    // The empty-library screen state is hard to produce against a live server
    // and easy to get wrong, so it is a first-class fixture.
    val books = container("albums-empty.json").plexMediaContainer.asAudiobooks()
    assertTrue(books.isEmpty())
  }

  @Test
  fun `home users fixture maps to users including a managed one`() {
    val users =
      moshi.adapter(UsersResponse::class.java)
        .fromJson(FakePlexServer.fixture("home-users.json"))
        ?: error("Failed to parse home-users.json")

    assertEquals(2, users.users.size)
    assertTrue("admin user is present", users.users.any { it.admin })
    // A PIN-protected managed user is the case the user picker must handle.
    assertTrue("pin-protected user is present", users.users.any { it.hasPassword })
  }
}
