package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.testing.MultiTrackBook
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook.MID_BOOK_POSITION
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook.MID_TRACK_ID
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook.TRACK_DURATION
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Book position on a **multi-track** book (cu-115).
 *
 * On a single-track book — which is every other fixture in this suite — an offset from the start of
 * the track equals an offset from the start of the book, so arithmetic that confuses the two is
 * right by accident. These tests use a three-track fixture where the two frames differ by up to
 * 1,200,000 ms, so a confusion cannot round away.
 */
class MultiTrackPositionTest {
  /** The fixture's own arithmetic, asserted first so a later failure implicates the code. */
  @Test
  fun `the fixture places the listener mid-track and mid-chapter`() {
    val tracks = MultiTrackBook.midBookTracks()
    val active = tracks.getActiveTrack()

    assertEquals("the furthest started track is track 2", MID_TRACK_ID, active.id)
    assertEquals(
      "the in-track offset and the book offset must differ, or the test proves nothing",
      TRACK_DURATION,
      MID_BOOK_POSITION - active.progress,
    )
  }

  /** Book position is the active track's offset plus every track before it. */
  @Test
  fun `book progress combines the track offset with the tracks before it`() {
    assertEquals(MID_BOOK_POSITION, MultiTrackBook.midBookTracks().getProgress().millis)
  }

  @Test
  fun `the start time of the first track is zero`() {
    val tracks = MultiTrackBook.tracks()

    assertEquals(0L, tracks.getTrackStartTime(tracks.first()))
  }

  @Test
  fun `the start time of a later track is the sum of the durations before it`() {
    val tracks = MultiTrackBook.tracks()

    assertEquals(TRACK_DURATION, tracks.getTrackStartTime(tracks[1]))
    assertEquals(TRACK_DURATION * 2, tracks.getTrackStartTime(tracks[2]))
  }

  /**
   * **The bug.** `getTrackStartTime` sums `subList(0, indexOf(track))` — the *receiver's* order,
   * not playback order.
   *
   * That is safe only where the caller happens to pass an ordered list. `LibrarySyncRepository`
   * does not: it derives every book's progress from `trackRepository.getAllTracksAsync()`, whose
   * query is `SELECT * FROM MediaItemTrack` with **no `ORDER BY`**, so the prefix summed is
   * whatever order SQLite returned. The per-book DAO reads *are* ordered, which is why this only
   * bites the whole-library path — and why it went unnoticed.
   *
   * `getActiveTrack` already sorts before deciding; this must too, or the two disagree about what
   * "before" means.
   */
  @Test
  fun `the start time does not depend on the order the list arrives in`() {
    val ordered = MultiTrackBook.tracks()
    val shuffled = listOf(ordered[2], ordered[0], ordered[1])
    val target = ordered[2]

    assertEquals(
      "row order from SQLite must not change a book's position",
      ordered.getTrackStartTime(target),
      shuffled.getTrackStartTime(target),
    )
  }

  /** Same property, one level up: the position itself must be order-independent. */
  @Test
  fun `book progress does not depend on the order the list arrives in`() {
    val ordered = MultiTrackBook.midBookTracks()
    val shuffled = listOf(ordered[2], ordered[0], ordered[1])

    assertEquals(MID_BOOK_POSITION, ordered.getProgress().millis)
    assertEquals(
      "an unordered query result must not move the listener's place",
      MID_BOOK_POSITION,
      shuffled.getProgress().millis,
    )
  }

  /**
   * A book played into a later track must not be dragged back by an earlier one being touched
   * (decision-16 rule 3). Single-track fixtures cannot express this at all.
   */
  @Test
  fun `a recently touched earlier track does not pull the position backwards`() {
    val tracks =
      MultiTrackBook.midBookTracks().map {
        if (it.id == "2001") it.copy(lastViewedAt = 99_000L) else it
      }

    assertEquals(MID_TRACK_ID, tracks.getActiveTrack().id)
    assertEquals(MID_BOOK_POSITION, tracks.getProgress().millis)
  }
}
