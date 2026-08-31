package io.github.mattpvaughn.chronicle.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping a backend-neutral book id to the `Int` group id Fetch2 requires.
 *
 * Fetch2's whole grouping API is `int` — `Request.groupId`, `cancelGroup(int)`,
 * `getDownloadsInGroup(int)` — and the app uses the book id as that group id. cu-71 retypes book
 * ids to `String` so a non-numeric backend (Audiobookshelf UUIDs, local file paths) can be
 * represented, so something has to bridge the two.
 *
 * The properties that matter are **stability** and **determinism**: the same book must map to the
 * same group id across process restarts, or `cancelGroup` after a relaunch would target the wrong
 * downloads — or nothing.
 */
class DownloadGroupIdTest {
  @Test
  fun `the same id always maps to the same group id`() {
    assertEquals(downloadGroupId("1001"), downloadGroupId("1001"))
  }

  @Test
  fun `a numeric Plex id maps to its own value`() {
    // Preserves today's behaviour for Plex, whose ids are numeric — so existing downloads keep
    // their group id across the retype and are not orphaned.
    assertEquals(1001, downloadGroupId("1001"))
    assertEquals(42, downloadGroupId("42"))
  }

  @Test
  fun `a non-numeric id still yields a group id`() {
    val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    // Only requirement: it produces something usable, deterministically.
    assertEquals(downloadGroupId(uuid), downloadGroupId(uuid))
  }

  @Test
  fun `different ids map to different group ids`() {
    assertNotEquals(downloadGroupId("1001"), downloadGroupId("1002"))
    assertNotEquals(downloadGroupId("abc"), downloadGroupId("abd"))
  }

  @Test
  fun `a group id is never negative`() {
    // Fetch2 uses -1 and other sentinels internally, and a negative group id has been observed
    // to behave inconsistently; keep the space positive.
    listOf("1001", "abc", "0f8fad5b-d9cb-469f-a165-70867728950e", "", "-5").forEach { id ->
      assertTrue("$id produced ${downloadGroupId(id)}", downloadGroupId(id) >= 0)
    }
  }

  @Test
  fun `an empty id is handled rather than throwing`() {
    assertTrue(downloadGroupId("") >= 0)
  }

  @Test
  fun `a numeric id too large for Int falls back to a hash`() {
    // A Long-sized id must not overflow into a wrong or negative group id.
    val huge = "99999999999999"

    assertTrue(downloadGroupId(huge) >= 0)
    assertEquals(downloadGroupId(huge), downloadGroupId(huge))
  }

  @Test
  fun `a negative numeric id does not become a negative group id`() {
    assertTrue(downloadGroupId("-5") >= 0)
  }
}
