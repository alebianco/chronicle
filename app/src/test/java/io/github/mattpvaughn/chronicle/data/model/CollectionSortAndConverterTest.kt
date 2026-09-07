package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two decisions [Collection] makes that a server can get wrong: how a Plex sort code maps to a
 * [Collection.SortType], and how a stored id list round-trips through Room.
 *
 * Both were uncovered. The sort mapping matters because `collectionSort` arrives from an
 * unofficial Plex field — a value outside 0-2 must land somewhere sane rather than throwing during
 * a library refresh, which would take the whole collections screen down. The converter matters
 * because it is the only thing standing between a stored string and a `List<String>`, and its
 * empty-string branch is the one a freshly-inserted row hits.
 */
class CollectionSortAndConverterTest {
  @Test
  fun `each documented plex sort code maps to its own sort type`() {
    assertEquals(
      Collection.SortType.RELEASE_DATE,
      Collection.SortType.fromPlexCode(Collection.PLEX_COLLECTION_SORT_TYPE_RELEASE_DATE),
    )
    assertEquals(
      Collection.SortType.ALPHABETICAL,
      Collection.SortType.fromPlexCode(Collection.PLEX_COLLECTION_SORT_TYPE_ALPHABETICAL),
    )
    assertEquals(
      Collection.SortType.CUSTOM,
      Collection.SortType.fromPlexCode(Collection.PLEX_COLLECTION_SORT_TYPE_CUSTOM),
    )
  }

  /**
   * `collectionSort` is a community-documented field, so an unrecognised code is a *when*, not an
   * *if*. Falling back rather than throwing keeps a refresh alive; Plex's own default ordering is
   * release date, so that is the safe landing spot.
   */
  @Test
  fun `an unknown or absent sort code falls back to release date rather than throwing`() {
    assertEquals(Collection.SortType.RELEASE_DATE, Collection.SortType.fromPlexCode(99))
    assertEquals(Collection.SortType.RELEASE_DATE, Collection.SortType.fromPlexCode(-1))
    assertEquals(Collection.SortType.RELEASE_DATE, Collection.SortType.fromPlexCode(null))
  }

  @Test
  fun `child ids survive a round trip through the stored form`() {
    val converter = CollectionIdConverter()
    val ids = listOf("1001", "1002", "1003")

    assertEquals(ids, converter.toList(converter.fromList(ids)))
  }

  /**
   * The stored form is a JSON array of strings and always has been (the id-retype migration changed the Kotlin type,
   * not the encoding). Pinned literally, because this converter builds its **own** `Moshi` rather
   * than the app's — so if the app's ever gains an adapter that writes a string list differently,
   * nothing else would notice the divergence.
   */
  @Test
  fun `the stored form is a plain json array of strings`() {
    assertEquals("""["1001","1002"]""", CollectionIdConverter().fromList(listOf("1001", "1002")))
  }

  /**
   * A freshly-inserted row reads back an empty string, not `"[]"`. Moshi throws on empty input, so
   * the guard is what stops a collection with no children from crashing the read.
   */
  @Test
  fun `an empty stored value reads back as an empty list`() {
    assertEquals(emptyList<String>(), CollectionIdConverter().toList(""))
  }

  @Test
  fun `an empty list round trips`() {
    val converter = CollectionIdConverter()

    assertEquals(emptyList<String>(), converter.toList(converter.fromList(emptyList())))
  }

  /**
   * Non-numeric ids are the whole point of the id-retype migration: `toLong()` would have thrown on
   * exactly the ids a non-Plex backend is expected to use (decision-11).
   */
  @Test
  fun `a non-numeric child id survives the round trip`() {
    val converter = CollectionIdConverter()
    val ids = listOf("abs:book:9f3c", "local/path/one.m4b")

    assertEquals(ids, converter.toList(converter.fromList(ids)))
  }
}
