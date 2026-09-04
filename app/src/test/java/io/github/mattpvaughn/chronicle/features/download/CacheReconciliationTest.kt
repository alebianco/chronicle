package io.github.mattpvaughn.chronicle.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

  // ---- pruning abandoned partials (cu-81) ----

  /**
   * The one case that should delete: bytes nobody is coming back for.
   *
   * Incomplete, unknown to Fetch2, not claimed by the database — a download that was cancelled or
   * exhausted its retries and was never returned to.
   */
  @Test
  fun `an abandoned partial is prunable`() {
    val prunable =
      partialsSafeToPrune(
        incompleteOnDisk = listOf("2001"),
        knownToFetch = emptyList(),
        reportedCached = emptyList(),
      )

    assertEquals(listOf("2001"), prunable)
  }

  /**
   * A resume candidate is never touched.
   *
   * Deleting a PAUSED or FAILED download's bytes turns a cheap HTTP Range resume into a full
   * re-download — the exact cost cu-76 left those bytes on disk to avoid.
   */
  @Test
  fun `a partial Fetch still knows about is kept`() {
    val prunable =
      partialsSafeToPrune(
        incompleteOnDisk = listOf("2001"),
        knownToFetch = listOf("2001"),
        reportedCached = emptyList(),
      )

    assertEquals(emptyList<String>(), prunable)
  }

  /**
   * A file the database calls cached is never touched, even if it measures short.
   *
   * The two facts disagreeing is a bug worth finding, but deleting the user's download is not how
   * to resolve it — the reconciliation marks it uncached and it can be fetched again.
   */
  @Test
  fun `a file the database calls cached is kept`() {
    val prunable =
      partialsSafeToPrune(
        incompleteOnDisk = listOf("2001"),
        knownToFetch = emptyList(),
        reportedCached = listOf("2001"),
      )

    assertEquals(emptyList<String>(), prunable)
  }

  /**
   * A complete file is not a candidate at all.
   *
   * It never reaches `incompleteOnDisk`, which is the input's contract — a full-length file with a
   * stale row is adopted by `reconcileCachedTracks`, never deleted.
   */
  @Test
  fun `a complete file is never offered for pruning`() {
    val prunable =
      partialsSafeToPrune(
        incompleteOnDisk = emptyList(),
        knownToFetch = emptyList(),
        reportedCached = emptyList(),
      )

    assertEquals(emptyList<String>(), prunable)
  }

  @Test
  fun `only the abandoned partial is pruned from a mixed set`() {
    val prunable =
      partialsSafeToPrune(
        incompleteOnDisk = listOf("resumable", "abandoned", "claimed"),
        knownToFetch = listOf("resumable"),
        reportedCached = listOf("claimed"),
      )

    assertEquals(listOf("abandoned"), prunable)
  }

  /** Either reason alone is enough to keep a file. */
  @Test
  fun `a partial both known and claimed is kept`() {
    val prunable =
      partialsSafeToPrune(
        incompleteOnDisk = listOf("2001"),
        knownToFetch = listOf("2001"),
        reportedCached = listOf("2001"),
      )

    assertEquals(emptyList<String>(), prunable)
  }

  @Test
  fun `nothing on disk prunes nothing`() {
    assertEquals(
      emptyList<String>(),
      partialsSafeToPrune(emptyList(), listOf("2001"), listOf("2002")),
    )
  }

  // ---- actually deleting them, against real files ----

  @get:Rule
  val pruneFolder = TemporaryFolder()

  private fun partialFile(
    name: String,
    bytes: Int,
  ): java.io.File = pruneFolder.newFile(name).apply { writeBytes(ByteArray(bytes)) }

  /** The whole point: the bytes are gone and the space is reported. */
  @Test
  fun `pruning deletes the file and reports the bytes reclaimed`() {
    val file = partialFile("2001.mp3", 1024)

    val outcome = prunePartialFiles(listOf("2001"), mapOf("2001" to file))

    assertEquals(1, outcome.deleted)
    assertEquals(1024L, outcome.reclaimedBytes)
    assertFalse("the partial should be gone", file.exists())
  }

  /**
   * Only the chosen file is touched.
   *
   * The failure that would matter most: a prune that takes a neighbour with it.
   */
  @Test
  fun `pruning leaves every other file alone`() {
    val doomed = partialFile("2001.mp3", 512)
    val keep = partialFile("2002.mp3", 2048)

    prunePartialFiles(listOf("2001"), mapOf("2001" to doomed, "2002" to keep))

    assertFalse(doomed.exists())
    assertTrue("an unchosen file must survive", keep.exists())
  }

  @Test
  fun `an id with no file is skipped rather than failing`() {
    val outcome = prunePartialFiles(listOf("missing"), emptyMap())

    assertEquals(0, outcome.deleted)
    assertEquals(emptyList<String>(), outcome.failedIds)
  }

  @Test
  fun `pruning nothing reports nothing`() {
    val keep = partialFile("2001.mp3", 128)

    val outcome = prunePartialFiles(emptyList(), mapOf("2001" to keep))

    assertEquals(0, outcome.deleted)
    assertEquals(0L, outcome.reclaimedBytes)
    assertTrue(keep.exists())
  }

  @Test
  fun `several partials are pruned and their sizes summed`() {
    val a = partialFile("2001.mp3", 100)
    val b = partialFile("2002.mp3", 200)

    val outcome = prunePartialFiles(listOf("2001", "2002"), mapOf("2001" to a, "2002" to b))

    assertEquals(2, outcome.deleted)
    assertEquals(300L, outcome.reclaimedBytes)
  }
}
