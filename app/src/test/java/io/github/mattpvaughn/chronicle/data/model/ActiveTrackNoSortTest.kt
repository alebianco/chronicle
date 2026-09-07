package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `getActiveTrack` must pick the same track as a full sort would, without paying for one.
 *
 * The profile that motivated this: 15 s of sampled playback on a 107-track book put **142 samples
 * in `MediaItemTrack.compareTo`** on the main thread, because `getActiveTrack()` called `sorted()`
 * on every invocation and `ProgressUpdater` publishes once a second. It is also why the cost scales
 * with track count — the same measurement on a 28-track book understated the main-thread total by
 * 5.6×.
 *
 * The *ordering* is load-bearing and is not being dropped: `TrackIndex` means "index into the
 * sorted list", and `getProgress` documents a real bug that came from trusting the list's
 * own order. What changes is that finding one extreme of an ordering is a scan, not a sort.
 *
 * Assertions compare against the explicitly-sorted answer rather than hand-picked expectations, so
 * they catch a divergence in either direction.
 */
class ActiveTrackNoSortTest {
  private fun track(
    id: String,
    index: Int,
    discNumber: Int = 1,
    progress: Long = 0,
  ) = MediaItemTrack(
    id = id,
    index = index,
    discNumber = discNumber,
    progress = progress,
    duration = 1000,
  )

  /**
   * What a full sort answers.
   *
   * `progress > 0` is spelled out rather than reusing the private `hasProgress()`, and that is part
   * of what is pinned: only a real offset counts as started. A timestamp did not, because
   * `markTracksInBookAsWatched` stamps every track and a book marked as read then reported itself
   * half finished (decision-16).
   */
  private fun List<MediaItemTrack>.activeBySorting(): MediaItemTrack {
    val ordered = sorted()
    return ordered.lastOrNull { it.progress > 0L } ?: ordered.first()
  }

  @Test
  fun `an untouched book yields the first track in playback order`() {
    val tracks = listOf(track("c", 3), track("a", 1), track("b", 2))

    assertEquals(tracks.activeBySorting(), tracks.getActiveTrack())
    assertEquals("a", tracks.getActiveTrack().id)
  }

  /**
   * The *furthest started* track, not the most recently touched — `max(lastViewedAt)` made the
   * position jump backwards between devices (decision-16).
   */
  @Test
  fun `the furthest started track wins even when an earlier one also has progress`() {
    val tracks = listOf(track("a", 1, progress = 500), track("b", 2, progress = 700), track("c", 3))

    assertEquals(tracks.activeBySorting(), tracks.getActiveTrack())
    assertEquals("b", tracks.getActiveTrack().id)
  }

  /** Disc number orders before index, so a later index on an earlier disc must not win. */
  @Test
  fun `disc number takes precedence over index`() {
    val tracks =
      listOf(
        track("d2t1", index = 1, discNumber = 2, progress = 10),
        track("d1t9", index = 9, discNumber = 1, progress = 10),
      )

    assertEquals(tracks.activeBySorting(), tracks.getActiveTrack())
    assertEquals("d2t1", tracks.getActiveTrack().id)
  }

  /** Input order must never leak into the answer. */
  @Test
  fun `the result does not depend on the input order`() {
    val tracks = listOf(track("a", 1, progress = 100), track("b", 2, progress = 200), track("c", 3))

    val fromPermutations =
      listOf(
        tracks,
        tracks.reversed(),
        listOf(tracks[2], tracks[0], tracks[1]),
        listOf(tracks[1], tracks[2], tracks[0]),
      ).map { it.getActiveTrack().id }.distinct()

    assertEquals(listOf("b"), fromPermutations)
  }

  @Test
  fun `a single track book yields that track`() {
    val tracks = listOf(track("only", 1, progress = 42))

    assertEquals(tracks.activeBySorting(), tracks.getActiveTrack())
  }

  /**
   * Agreement with a full sort across 200 shuffles of a 107-track book — the shape that made the
   * profile expensive. Many shuffles rather than one, since a scan that mishandles ties agrees with
   * a sort on most inputs but not all.
   */
  @Test
  fun `agrees with a full sort over many shuffles of a large book`() {
    val base = (1..107).map { i -> track("t$i", index = i, progress = if (i <= 40) i * 10L else 0) }
    val rng = java.util.Random(20260904)

    repeat(200) {
      val shuffled = base.shuffled(rng)
      assertEquals(shuffled.activeBySorting(), shuffled.getActiveTrack())
    }
  }

  /**
   * **The tie case, and the one that caught a wrong first attempt.** `compareTo` is `(disc, index)`
   * and neither is unique by construction, so two tracks can compare equal. A stable `sorted()`
   * keeps them in input order and `lastOrNull` answers the **last**; `maxWithOrNull` answers the
   * **first**, because it replaces its candidate only on a strictly greater comparison. A
   * `maxWithOrNull` implementation therefore returns `first` here where the sort returns `second`.
   *
   * Asserted by id as well as against the reference, so the expectation is legible rather than
   * hidden behind a helper.
   */
  @Test
  fun `tied tracks resolve to the last one, as a stable sort does`() {
    val tracks =
      listOf(
        track("first", index = 2, progress = 10),
        track("second", index = 2, progress = 20),
      )

    assertEquals(0, tracks[0].compareTo(tracks[1]))
    assertEquals("second", tracks.activeBySorting().id)
    assertEquals("second", tracks.getActiveTrack().id)
  }
}
