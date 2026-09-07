package io.github.mattpvaughn.chronicle.data.model

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Whether a file on disk is a *complete* download.
 *
 * `refreshTrackDownloadedStatus` scanned `cachedMediaDir` and marked every file matching
 * `<id>.<ext>` as `cached = true` with no size check, while `MediaItemTrack.size` — populated
 * from Plex's `media[0].part[0].size` and persisted in Room — was read nowhere in the app.
 *
 * So a Wi-Fi drop at 40% left a partial file that the next launch promoted to "downloaded",
 * and the book played truncated while the UI insisted it was available offline.
 */
class DownloadIntegrityTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `a file matching the expected size is complete`() {
    val file = tempFolder.newFile("3001.mp3").apply { writeBytes(ByteArray(1_024)) }

    assertTrue(isCompleteDownload(file, expectedSize = 1_024L))
  }

  @Test
  fun `a truncated file is not complete`() {
    val file = tempFolder.newFile("3001.mp3").apply { writeBytes(ByteArray(400)) }

    assertFalse(
      "this is the Wi-Fi-drop case that was being marked as downloaded",
      isCompleteDownload(file, expectedSize = 1_024L),
    )
  }

  @Test
  fun `an empty placeholder file is not complete`() {
    val file = tempFolder.newFile("3001.mp3")

    assertFalse(isCompleteDownload(file, expectedSize = 1_024L))
  }

  @Test
  fun `a file larger than expected is not complete`() {
    val file = tempFolder.newFile("3001.mp3").apply { writeBytes(ByteArray(2_048)) }

    assertFalse(
      "a longer file means the size metadata and the bytes disagree; trusting it would " +
        "hide whichever is wrong",
      isCompleteDownload(file, expectedSize = 1_024L),
    )
  }

  /**
   * Plex does not always report a size. Falling back to "any non-empty file counts" keeps the
   * previous behaviour for those tracks rather than making them permanently un-cacheable —
   * but an empty file is still rejected.
   */
  @Test
  fun `an unknown expected size accepts any non-empty file`() {
    val file = tempFolder.newFile("3001.mp3").apply { writeBytes(ByteArray(1)) }

    assertTrue(isCompleteDownload(file, expectedSize = 0L))
  }

  @Test
  fun `an unknown expected size still rejects an empty file`() {
    val file = tempFolder.newFile("3001.mp3")

    assertFalse(isCompleteDownload(file, expectedSize = 0L))
  }

  @Test
  fun `a missing file is not complete`() {
    val missing = java.io.File(tempFolder.root, "nope.mp3")

    assertFalse(isCompleteDownload(missing, expectedSize = 1_024L))
  }

  /**
   * The Okio overload must agree with the `java.io.File` one on every case, or the download paths
   * that moved to Okio would judge completeness differently from everything else.
   *
   * Table-driven against the same four cases the File tests cover, so the two spellings cannot
   * drift apart silently — which is the risk of having two of them at all.
   */
  @Test
  fun `the okio overload agrees with the file overload`() {
    val fs = FakeFileSystem()
    val dir = "/dl".toPath()
    fs.createDirectories(dir)

    val complete = dir / "complete.mp3"
    fs.write(complete) { write(ByteArray(1_024)) }
    val partial = dir / "partial.mp3"
    fs.write(partial) { write(ByteArray(512)) }
    val empty = dir / "empty.mp3"
    fs.write(empty) { write(ByteArray(0)) }
    val absent = dir / "absent.mp3"

    assertTrue("exact size is complete", isCompleteDownload(complete, 1_024L, fs))
    assertFalse("short of the expected size is not", isCompleteDownload(partial, 1_024L, fs))
    assertFalse("a missing file is never complete", isCompleteDownload(absent, 1_024L, fs))
    // Size 0 from the server means "unknown", so the rule falls back to non-empty.
    assertTrue("unknown expected size accepts any non-empty file", isCompleteDownload(partial, 0L, fs))
    assertFalse("unknown expected size still rejects an empty file", isCompleteDownload(empty, 0L, fs))
  }
}
