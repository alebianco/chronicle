package io.github.mattpvaughn.chronicle.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision behind *"book reports no cache even if I'm sure I have downloaded it"*.
 *
 * This logic sat inside `CachedFileManager.refreshTrackDownloadedStatus`, unreachable from a JVM
 * test because the class needs Fetch, a Context, a BroadcastReceiver and `Injector.get()` merely to
 * construct. Extracting it is what makes these cases expressible at all.
 */
class CacheReconciliationTest {
  @Test
  fun `a track on disk that the database does not know is marked cached`() {
    val result = reconcileCachedTracks(onDisk = listOf("2001"), reportedCached = emptyList())

    assertEquals(listOf("2001"), result.toMarkCached)
    assertEquals(emptyList<String>(), result.toMarkUncached)
  }

  @Test
  fun `a track the database calls cached but is gone from disk is marked uncached`() {
    val result = reconcileCachedTracks(onDisk = emptyList(), reportedCached = listOf("2001"))

    assertEquals(listOf("2001"), result.toMarkUncached)
    assertEquals(emptyList<String>(), result.toMarkCached)
  }

  /** The steady state: agreement means no writes, which is what keeps a launch scan cheap. */
  @Test
  fun `a track present on both sides changes nothing`() {
    val result = reconcileCachedTracks(onDisk = listOf("2001"), reportedCached = listOf("2001"))

    assertFalse(result.hasChanges)
    assertEquals(emptyList<String>(), result.alteredTrackIds)
  }

  /** Both directions at once, which is the ordinary case after a partial delete. */
  @Test
  fun `additions and removals are reported together`() {
    val result =
      reconcileCachedTracks(
        onDisk = listOf("2001", "2002"),
        reportedCached = listOf("2002", "2003"),
      )

    assertEquals(listOf("2003"), result.toMarkUncached)
    assertEquals(listOf("2001"), result.toMarkCached)
    assertTrue(result.hasChanges)
  }

  /**
   * Ids are Strings since cu-71 and need not be numeric — a non-Plex backend may use anything
   * (decision-11). Comparison must be by value, with no parsing.
   */
  @Test
  fun `non-numeric ids reconcile by value`() {
    val result =
      reconcileCachedTracks(
        onDisk = listOf("abc-123", "local:/music/x.mp3"),
        reportedCached = listOf("abc-123"),
      )

    assertEquals(listOf("local:/music/x.mp3"), result.toMarkCached)
    assertEquals(emptyList<String>(), result.toMarkUncached)
  }

  /** A duplicate on either side must not produce a duplicate write. */
  @Test
  fun `duplicate ids are collapsed`() {
    val result =
      reconcileCachedTracks(onDisk = listOf("2001", "2001"), reportedCached = emptyList())

    assertEquals(listOf("2001"), result.toMarkCached)
  }

  /**
   * An empty disk list means the directory was read and found empty — a real answer. The caller
   * must not reach this at all for an unreadable directory (cu-85); `scanCachedMediaDir` returns
   * `Unavailable` for that, and `refreshTrackDownloadedStatus` returns early.
   */
  @Test
  fun `an empty disk with cached tracks in the database uncaches them`() {
    val result =
      reconcileCachedTracks(onDisk = emptyList(), reportedCached = listOf("2001", "2002"))

    assertEquals(setOf("2001", "2002"), result.toMarkUncached.toSet())
  }

  @Test
  fun `a book is cached only when every track is`() {
    assertTrue(isBookFullyCached(cachedTrackCount = 3, totalTrackCount = 3))
    assertFalse(isBookFullyCached(cachedTrackCount = 2, totalTrackCount = 3))
  }

  /**
   * The `> 0` guard. A book whose tracks have not loaded reports 0 of 0, and `0 == 0` would
   * otherwise mark an empty book fully downloaded.
   */
  @Test
  fun `a book with no tracks is not cached`() {
    assertFalse(
      "0 of 0 tracks must not read as fully downloaded",
      isBookFullyCached(cachedTrackCount = 0, totalTrackCount = 0),
    )
  }
}
