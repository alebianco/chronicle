package io.github.mattpvaughn.chronicle.data.sources

import io.github.mattpvaughn.chronicle.data.sources.local.LocalMediaSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the seam contract from decision-11: the interface carries capability flags so
 * the UI can degrade per backend instead of rendering empty narrator/series fields.
 *
 * [LocalMediaSource] is deliberately kept rather than deleted — it is the cu-33.2
 * target. The older C6 analysis recommended removing it, but decision-11 supersedes
 * that. What matters is that it cannot masquerade as working.
 */
class MediaSourceSeamTest {
  @Test
  fun `interface declares the decision-11 capability flags`() {
    val declared = MediaSource::class.members.map { it.name }.toSet()

    assertEquals(
      "decision-11 requires capability flags on the seam",
      setOf("hasNarrator", "hasSeries", "hasServerProgress"),
      setOf("hasNarrator", "hasSeries", "hasServerProgress").intersect(declared),
    )
  }

  @Test
  fun `local source declares no server-side capabilities`() {
    val local = LocalMediaSource()

    assertFalse("local files are already on disk", local.isDownloadable)
    assertFalse("no server to hold progress", local.hasServerProgress)
    assertFalse("no narrator metadata until a tag reader lands", local.hasNarrator)
    assertFalse("no series metadata until a tag reader lands", local.hasSeries)
  }

  /**
   * The stub must fail loudly. A silent empty list would look like an empty library
   * and send someone hunting through sync code for a bug that is really "this backend
   * does not exist yet".
   */
  @Test
  fun `local source fetches throw rather than returning empty`() {
    val local = LocalMediaSource()

    assertThrows(NotImplementedError::class.java) {
      runBlocking { local.fetchAudiobooks() }
    }
    assertThrows(NotImplementedError::class.java) {
      runBlocking { local.fetchTracks() }
    }
  }
}
