package io.github.mattpvaughn.chronicle.data.sources.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the multi-id metadata route is split into requests (cu-156).
 *
 * cu-150 measured the route against the household server: 196 books in **one** request, 0.2 s,
 * 449 KB, carrying both `Style` and `Mood` — versus Route A's 185 requests for narrators alone.
 * Re-verified here while capturing the fixture: 196 returned, 1371-character id string, 0.196 s.
 *
 * **Batch by URL length, not by count.** Plex itself tolerated a 22 KB URL in cu-150's probe, but
 * the relay is one of the three connection tiers (cu-11) and a reverse proxy in front of a server
 * commonly caps a request line at 8 KB. So the cap is deliberately conservative and expressed in
 * characters of ids, which is the part that actually grows.
 */
class MultiIdBatchingTest {
  @Test
  fun `a household-sized library is a single request`() {
    // 196 ids averaging 6 characters -> ~1.4 KB, comfortably inside one batch.
    val ids = (1..196).map { "15%04d".format(it) }
    val batches = batchIdsByUrlLength(ids)
    assertEquals("196 books must not need splitting", 1, batches.size)
    assertEquals(196, batches.single().size)
  }

  @Test
  fun `a large library splits into several bounded batches`() {
    val ids = (1..2000).map { "15%04d".format(it) }
    val batches = batchIdsByUrlLength(ids)
    assertTrue("2000 books must split", batches.size > 1)
    batches.forEach { batch ->
      val len = batch.joinToString(",").length
      assertTrue("a batch's id string must stay within the cap, was $len", len <= MAX_ID_STRING_LENGTH)
    }
    assertEquals("every id must appear exactly once", ids, batches.flatten())
  }

  /** A single id longer than the cap must still be requested rather than silently dropped. */
  @Test
  fun `an oversized single id is still emitted`() {
    val huge = "9".repeat(MAX_ID_STRING_LENGTH + 50)
    val batches = batchIdsByUrlLength(listOf(huge))
    assertEquals(1, batches.size)
    assertEquals(listOf(huge), batches.single())
  }

  @Test
  fun `no ids means no requests`() {
    assertEquals(emptyList<List<String>>(), batchIdsByUrlLength(emptyList()))
  }

  @Test
  fun `batches preserve order so a partial failure is attributable`() {
    val ids = (1..500).map { "id$it" }
    assertEquals(ids, batchIdsByUrlLength(ids).flatten())
  }
}
