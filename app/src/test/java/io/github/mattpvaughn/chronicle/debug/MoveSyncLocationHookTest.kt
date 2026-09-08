package io.github.mattpvaughn.chronicle.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The `move_sync_location` hook's refusal to accept a path that is not one of the app's own
 * external dirs.
 *
 * Worth a test rather than a glance: `PrefsRepo.cachedMediaDir` accepts **any** path, so an
 * unmatched one would silently point downloads at a directory the app cannot write, and that
 * surfaces much later as "downloads don't work" rather than as a bad argument here.
 */
class MoveSyncLocationHookTest {
  private val internal = File("/storage/emulated/0/Android/data/pkg/files")
  private val sdCard = File("/storage/79AF-CD2E/Android/data/pkg/files")
  private val candidates = listOf(internal, sdCard)

  @Test
  fun `a real external dir resolves`() {
    assertEquals(sdCard, resolveSyncTarget(sdCard.absolutePath, candidates))
    assertEquals(internal, resolveSyncTarget(internal.absolutePath, candidates))
  }

  @Test
  fun `a path outside the app's external dirs is refused`() {
    assertNull(resolveSyncTarget("/sdcard/Music", candidates))
    assertNull(resolveSyncTarget("/data/data/pkg/files", candidates))
  }

  /** A prefix of a real dir is not a real dir — this must be an exact match, not `startsWith`. */
  @Test
  fun `a parent of a real external dir is refused`() {
    assertNull(resolveSyncTarget("/storage/79AF-CD2E/Android/data/pkg", candidates))
    assertNull(resolveSyncTarget("/storage/79AF-CD2E", candidates))
  }

  @Test
  fun `no candidates means nothing resolves`() {
    assertNull(resolveSyncTarget(sdCard.absolutePath, emptyList()))
  }
}
