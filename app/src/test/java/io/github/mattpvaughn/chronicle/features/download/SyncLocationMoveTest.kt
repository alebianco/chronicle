package io.github.mattpvaughn.chronicle.features.download

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Whether changing the sync location leaves partial downloads behind in the **source** directory.
 *
 * The question the downloads-going-missing investigation raised and could not answer: its prune
 * only ever looks at the *current* `cachedMediaDir`, so anything the move fails to bring across
 * is orphaned on a volume nothing scans again.
 *
 * The answer is decided entirely by naming. Fetch2 downloads **in place** and resumes over HTTP
 * Range — there is no `.part`/`.tmp` suffix — so a partial file is named `<trackId>.<ext>` exactly
 * like a complete one, and `MoveSyncLocationWorker`'s `cachedFilePattern` filter cannot tell them
 * apart. It moves both. That is the *correct* behaviour, and this pins it, because a future change
 * that gave partials a distinguishing suffix would silently start orphaning them.
 */
class SyncLocationMoveTest {
  @get:Rule
  val temp = TemporaryFolder()

  @Test
  fun `a partial download is selected for the move, exactly like a complete one`() {
    val from = temp.newFolder("from")
    // Both named as Fetch2 names them: the track id, then the extension. The partial is simply
    // shorter — nothing in the *name* distinguishes it.
    val complete = java.io.File(from, "900001.mp3").apply { writeBytes(ByteArray(200_000)) }
    val partial = java.io.File(from, "900002.mp3").apply { writeBytes(ByteArray(37_000)) }

    val selected =
      from.listFiles { f: java.io.File -> MediaItemTrack.cachedFilePattern.matches(f.name) }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    assertEquals(
      "the move must select the partial as well as the complete file",
      listOf(complete.name, partial.name).sorted(),
      selected,
    )
  }

  /**
   * The stray files the pattern must keep *out* of the move are still excluded — this is the other
   * half of the same filter, and widening it to catch partials would be the wrong fix.
   */
  @Test
  fun `non-media files are not swept along by the move`() {
    val from = temp.newFolder("from2")
    java.io.File(from, "900003.mp3").writeBytes(ByteArray(10))
    java.io.File(from, ".nomedia").writeBytes(ByteArray(0))

    val selected =
      from.listFiles { f: java.io.File -> MediaItemTrack.cachedFilePattern.matches(f.name) }
        ?.map { it.name }
        .orEmpty()

    assertEquals(listOf("900003.mp3"), selected)
    assertTrue(java.io.File(from, ".nomedia").exists())
  }
}
