package io.github.mattpvaughn.chronicle.testing

import com.squareup.moshi.Moshi
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
  private val moshi = Moshi.Builder().build()

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
    assertEquals("listening progress survives deserialization", 54_000L, first.progress)
    // 180_000 = the real length of the generated tone. The fixture durations are deliberately
    // kept equal to the audio (cu-64), so progress percentages and chapter boundaries are
    // computed against a length the audio actually has. Extended from 5s to 3min so playback
    // sustains long enough to measure (cu-110).
    assertEquals(180_000L, first.duration)
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
    val track = trackWithChapters("2001")
    val chapters =
      track.plexChapters.map {
        it.toChapter(trackId = "2001", trackDiscNumber = 1, downloaded = false, bookId = "1001")
      }

    assertEquals(3, chapters.size)
    assertEquals("Chapter 1: An Unexpected Party", chapters[0].title)
    assertEquals(0L, chapters[0].bookStartTimeOffset.millis)
    assertEquals(75_000L, chapters[0].bookEndTimeOffset.millis)
  }

  /**
   * The last chapter has an empty `tag` on purpose, exercising the "Chapter $index" fallback in
   * `PlexChapter.toChapter`. It lives on the final track now that the fixture spans three.
   */
  @Test
  fun `an untitled chapter falls back to its index`() {
    val chapters =
      trackWithChapters("2003").plexChapters.map {
        it.toChapter(trackId = "2003", trackDiscNumber = 1, downloaded = false, bookId = "1001")
      }

    assertEquals("Chapter 8", chapters.last().title)
  }

  /**
   * The property cu-115 needs from this fixture: chapters whose spans **cross a track boundary**.
   *
   * The book is 3 x 180 s and the chapters are 75 s, which does not divide evenly — so chapter 3
   * straddles 180000 and chapter 5 straddles 360000. Plex reports such a chapter on *both* tracks
   * it overlaps, with offsets absolute within the **book**, which is what makes the in-track vs
   * book-absolute confusion reachable here. Every earlier version of this fixture had a single
   * track, where the two frames are the same number and the bug class is invisible.
   */
  @Test
  fun `chapters cross track boundaries with book-absolute offsets`() {
    val trackDuration = 180_000L

    val onFirst = trackWithChapters("2001").plexChapters
    val onSecond = trackWithChapters("2002").plexChapters
    val onThird = trackWithChapters("2003").plexChapters

    // Chapter 3 spans 150000..225000, so it belongs to both track 1 and track 2.
    val straddler = onFirst.single { it.index == 3L }
    assertEquals(150_000L, straddler.startTimeOffset)
    assertEquals(225_000L, straddler.endTimeOffset)
    assertTrue(
      "a chapter crossing the boundary must be reported on the following track too",
      onSecond.any { it.index == 3L },
    )
    assertTrue(
      "and it must actually cross it, or the fixture proves nothing",
      straddler.startTimeOffset < trackDuration && straddler.endTimeOffset > trackDuration,
    )

    // The same at the second boundary, so the property is not an accident of the first.
    val second = onSecond.single { it.index == 5L }
    assertTrue(
      second.startTimeOffset < trackDuration * 2 && second.endTimeOffset > trackDuration * 2,
    )
    assertTrue(onThird.any { it.index == 5L })

    // Offsets are book-absolute: a later track's chapters start beyond its own duration.
    assertTrue(
      "track 3's chapters must be numbered from the start of the book, not of the track",
      onThird.all { it.startTimeOffset >= trackDuration * 2 - 75_000 },
    )
  }

  /** The fixture now carries all three tracks, so a lookup by ratingKey is needed. */
  private fun trackWithChapters(ratingKey: String) =
    container("track-with-chapters.json").plexMediaContainer.metadata
      .single { it.ratingKey == ratingKey }

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

  /**
   * The leniency question cu-62 turned on: generated adapters are stricter than reflection about
   * absent and null fields, and these models parse live Plex responses whose shape varies by server
   * version. A missing key must fall back to the Kotlin default, not throw.
   */
  @Test
  fun `a directory with most fields absent still parses`() {
    val sparse =
      """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1001","title":"Sparse Book"}]}}"""

    val parsed =
      moshi.adapter(PlexMediaContainerWrapper::class.java).fromJson(sparse)
        ?: error("sparse container failed to parse")

    val book = parsed.plexMediaContainer.metadata.single()
    assertEquals("1001", book.ratingKey)
    assertEquals("Sparse Book", book.title)
    // Absent, so the Kotlin defaults must apply rather than the parse failing.
    assertEquals(0L, book.duration)
    assertTrue(book.plexChapters.isEmpty())
  }

  /**
   * An explicit JSON `null` on a non-null Kotlin field is the case where codegen and reflection
   * genuinely differ — codegen throws. Pinning the behaviour so a server that starts sending nulls
   * produces a known failure rather than a mystery.
   */
  @Test
  fun `an explicit null on a non-null field is rejected, not silently defaulted`() {
    val withNull =
      """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1001","title":null}]}}"""

    val failure =
      runCatching {
        moshi.adapter(PlexMediaContainerWrapper::class.java).fromJson(withNull)
      }.exceptionOrNull()

    assertTrue(
      "a null on a non-null field must fail loudly, not parse as empty: $failure",
      failure != null,
    )
  }

  /** An empty container must parse to nothing rather than throwing — the offline/empty-library case. */
  @Test
  fun `a container with no metadata parses to an empty list`() {
    val empty = """{"MediaContainer":{"size":0}}"""

    val parsed =
      moshi.adapter(PlexMediaContainerWrapper::class.java).fromJson(empty)
        ?: error("empty container failed to parse")

    assertTrue(parsed.plexMediaContainer.metadata.isEmpty())
  }

  /**
   * The album-detail route must answer with an **album**, not tracks.
   *
   * `retrieveAlbum` and `retrieveChapterInfo` hit the same URL with the same query parameters, and
   * the fixture server used to answer `track-with-chapters.json` for both. `fetchBookAsync` then
   * received tracks for an album request and `bookDao.update` — an `@Insert(REPLACE)` — inserted
   * one into the Audiobook table, which surfaced on the home shelves as a phantom book with a
   * track's title and its book's name in the author field (cu-18).
   *
   * Pinned as a contract because the routing exists **twice**, in `FakePlexServer` here and in
   * `MockPlexServer` for the debug app, and both had the same defect.
   */
  @Test
  fun `each book id has an album detail fixture containing exactly that album`() {
    FakePlexServer.ALBUM_FIXTURE_IDS.forEach { id ->
      val books = container("album-$id.json").plexMediaContainer.asAudiobooks()

      assertEquals("album-$id.json must hold one album", 1, books.size)
      assertEquals("album-$id.json must hold album $id", id, books.first().id)
    }
  }

  /** And the ids it lists are exactly the ones the library listing offers. */
  @Test
  fun `the album detail fixtures cover every book in the library listing`() {
    val listed = container("albums.json").plexMediaContainer.asAudiobooks().map { it.id }.toSet()

    assertEquals(listed, FakePlexServer.ALBUM_FIXTURE_IDS)
  }

  /**
   * The track fixture must still yield no books now that [asAudiobooks] filters on type — this is
   * the guard that makes a future routing mistake cosmetic rather than corrupting.
   */
  @Test
  fun `the track fixture yields no books`() {
    val books = container("track-with-chapters.json").plexMediaContainer.asAudiobooks()

    assertEquals(emptyList<String>(), books.map { it.id })
  }

  /**
   * Each track's chapter fixture holds **that track's own** chapters, and only its own.
   *
   * `retrieveChapterInfo(trackId)` is read with `metadata.firstOrNull()`, so a fixture holding
   * every track answers the *first* one's chapters for every request — every track then got track
   * 2001's three chapters and the player read "Ch 1 of 9" for a 7-chapter book, each chapter
   * tripled. The album half of this was cu-18; this is the track half (cu-19).
   */
  @Test
  fun `each track chapter fixture holds only that track's chapters`() {
    FakePlexServer.TRACK_FIXTURE_IDS.forEach { id ->
      val metadata = container("track-$id-chapters.json").plexMediaContainer.metadata

      assertEquals("track-$id-chapters.json must hold one track", 1, metadata.size)
      assertEquals("track-$id-chapters.json must hold track $id", id, metadata.first().ratingKey)
      assertTrue(
        "track $id must carry chapters",
        metadata.first().plexChapters.isNotEmpty(),
      )
    }
  }

  /**
   * The three tracks' chapter lists differ, which is what makes the fixture able to catch the bug
   * — three identical lists would pass the test above and still be wrong.
   */
  @Test
  fun `the tracks do not all report the same chapters`() {
    val firstTags =
      FakePlexServer.TRACK_FIXTURE_IDS.map { id ->
        container("track-$id-chapters.json")
          .plexMediaContainer.metadata.first().plexChapters.first().tag
      }

    assertEquals("each track must start at a different chapter", firstTags.size, firstTags.toSet().size)
  }

  /**
   * The album *detail* fixtures carry Audnexus tags (cu-24).
   *
   * They exist so narrator and series are reachable in mock mode at all — the criterion is about
   * "Audnexus-tagged libraries", and without tags in the fixtures there is nothing to browse. Pinned
   * here because the tags live in three separate files and a silent loss would make the facet
   * screens look empty rather than broken.
   */
  @Test
  fun `each album detail fixture carries a narrator and a series`() {
    val expected =
      mapOf(
        "1001" to ("Rob Inglis" to "Middle-earth"),
        "1002" to ("Scott Brick, Simon Vance" to "Dune"),
        "1003" to ("Michael Kramer" to "Mistborn"),
      )

    expected.forEach { (id, narratorAndSeries) ->
      val book =
        container("album-$id.json").plexMediaContainer.asAudiobooks().single()
      val (narrator, series) = narratorAndSeries
      assertEquals("narrator for $id", narrator, book.narrator)
      assertEquals("series for $id", series, book.series)
    }
  }

  /** And the series position, which is what orders a series list. */
  @Test
  fun `album detail fixtures carry a series position`() {
    assertEquals(1, container("album-1001.json").plexMediaContainer.asAudiobooks().single().seriesIndex)
    assertEquals(1, container("album-1002.json").plexMediaContainer.asAudiobooks().single().seriesIndex)
    assertEquals(
      "double digits, so a string sort would put this before book 2",
      10,
      container("album-1003.json").plexMediaContainer.asAudiobooks().single().seriesIndex,
    )
  }
}
