package io.github.mattpvaughn.chronicle.data.model

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
 * and the book played truncated while the UI insisted it was available offline (cu-76).
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
}
