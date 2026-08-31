package io.github.mattpvaughn.chronicle.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PendingIntent request codes derived from a book id.
 *
 * Two notification actions build their request code arithmetically — `prefix + bookId` — which a
 * `String` id cannot do. The codes only need to be **unique per book and stable across processes**:
 * Android matches a PendingIntent by request code plus intent, so a code that changed between
 * launches would leave a stale notification action pointing at the wrong book, and a colliding one
 * would let two books share an action.
 *
 * [downloadGroupId] already provides a stable Int from a String id, so this reuses it rather than
 * introducing a second mapping with its own collision behaviour.
 */
class RequestCodeTest {
  @Test
  fun `the same book yields the same code`() {
    assertEquals(requestCodeFor(PREFIX, "1001"), requestCodeFor(PREFIX, "1001"))
  }

  @Test
  fun `different books yield different codes`() {
    assertNotEquals(requestCodeFor(PREFIX, "1001"), requestCodeFor(PREFIX, "1002"))
  }

  @Test
  fun `different prefixes yield different codes for the same book`() {
    assertNotEquals(
      "otherwise the cancel action and the open action would collide",
      requestCodeFor(PREFIX, "1001"),
      requestCodeFor(OTHER_PREFIX, "1001"),
    )
  }

  @Test
  fun `a non-numeric id yields a code`() {
    val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    assertEquals(requestCodeFor(PREFIX, uuid), requestCodeFor(PREFIX, uuid))
  }

  @Test
  fun `a numeric id keeps its historical code`() {
    // Preserves the old `prefix + bookId` arithmetic for Plex's numeric ids, so a notification
    // posted before the retype still matches its action afterwards.
    assertEquals(PREFIX + 1001, requestCodeFor(PREFIX, "1001"))
  }

  private companion object {
    const val PREFIX = -1001110
    const val OTHER_PREFIX = 79211
  }
}
