package io.github.mattpvaughn.chronicle.features.download

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileFilter

/**
 * Keeps "the directory is empty" distinct from "the directory cannot be read".
 *
 * The cache scan used to do `listFiles(...) ?: emptyList()`. `listFiles` returns null for a missing
 * or unreadable directory, so both cases became "no files found" — and the scan then marked every
 * track uncached. An unmounted SD card, a sync directory that had moved, or a volume not yet mounted
 * at launch therefore wiped the cached status of a whole library while the audio was still on disk
 * (cu-85: *"book reports no cache even if I'm sure I have downloaded it"*).
 */
class CacheScanOutcomeTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val anyFile = FileFilter { true }
  private val cachedMedia = FileFilter { MediaItemTrack.cachedFilePattern.matches(it.name) }

  /** The legitimate case that *must* still un-cache: readable, and really empty. */
  @Test
  fun `an empty readable directory scans as empty, not unavailable`() {
    val outcome = scanCachedMediaDir(folder.newFolder("empty"), anyFile)

    assertTrue("an empty directory is a real answer", outcome is CacheScanOutcome.Scanned)
    assertEquals(emptyList<File>(), (outcome as CacheScanOutcome.Scanned).files)
  }

  /** The regression: a directory that is not there must never read as "nothing is downloaded". */
  @Test
  fun `a missing directory is unavailable, not empty`() {
    val missing = File(folder.root, "not-mounted")

    val outcome = scanCachedMediaDir(missing, anyFile)

    assertTrue(
      "a missing sync directory must not look like an empty one",
      outcome is CacheScanOutcome.Unavailable,
    )
  }

  /**
   * A path that exists but is a *file* also returns null from `listFiles`. Reported as unavailable
   * rather than empty, because it means the sync location is wrong, not that downloads are gone.
   */
  @Test
  fun `a path that is a file rather than a directory is unavailable`() {
    val notADir = folder.newFile("i-am-a-file")

    assertTrue(scanCachedMediaDir(notADir, anyFile) is CacheScanOutcome.Unavailable)
  }

  @Test
  fun `an unavailable outcome carries a reason naming the path`() {
    val missing = File(folder.root, "not-mounted")

    val outcome = scanCachedMediaDir(missing, anyFile) as CacheScanOutcome.Unavailable

    assertTrue(
      "the reason must name the path, or a bug report cannot be acted on",
      outcome.reason.contains("not-mounted"),
    )
  }

  @Test
  fun `only files matching the cached-media pattern are returned`() {
    val dir = folder.newFolder("media")
    File(dir, "3001.mp3").writeText("audio")
    File(dir, "3002.m4b").writeText("audio")
    File(dir, "notes.txt").writeText("not a track")
    File(dir, ".hidden").writeText("not a track")
    File(dir, "3003.mp3.part").writeText("partial download")

    val outcome = scanCachedMediaDir(dir, cachedMedia) as CacheScanOutcome.Scanned

    assertEquals(
      listOf("3001.mp3", "3002.m4b"),
      outcome.files.map { it.name }.sorted(),
    )
  }

  /**
   * A `.part` file must not be mistaken for a finished download — the pattern rejects a second
   * extension. Pinned here as well as in `CachedFilePatternTest` because this is the call site
   * where a loose pattern would promote a partial file to "available offline".
   */
  @Test
  fun `a partial download is not returned by the scan`() {
    val dir = folder.newFolder("partials")
    File(dir, "3001.mp3.part").writeText("half")

    val outcome = scanCachedMediaDir(dir, cachedMedia) as CacheScanOutcome.Scanned

    assertTrue(outcome.files.isEmpty())
  }
}
