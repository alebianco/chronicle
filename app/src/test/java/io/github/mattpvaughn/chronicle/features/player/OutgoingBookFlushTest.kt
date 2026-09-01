package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.EMPTY_TRACK
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a book switch must flush the outgoing position (cu-91).
 *
 * The old implementation in `AudiobookDetailsViewModel` tested the *opposite* of this and had no
 * tests, so both of its failure modes were invisible: a stray STOPPED report on an ordinary
 * play/pause, and silence in the one case it existed for.
 */
class OutgoingBookFlushTest {
  private fun track(
    id: String,
    bookId: String,
  ) = MediaItemTrack(id = id, parentKey = bookId, title = "Track $id", duration = 10_000L, index = 1)

  /** The case the flush exists for: a different book is taking over. */
  @Test
  fun `switching to a different book flushes`() {
    assertTrue(
      "the device leaving a book must tell the server where it stopped",
      shouldFlushOutgoingBook(track("2001", bookId = "1001"), incomingBookId = "2002"),
    )
  }

  /**
   * The bug the old condition caused: pressing play on the book already playing emitted a STOPPED
   * report for it.
   */
  @Test
  fun `restarting the same book does not flush`() {
    assertFalse(
      "an ordinary play, pause or seek must not report STOPPED",
      shouldFlushOutgoingBook(track("2001", bookId = "1001"), incomingBookId = "1001"),
    )
  }

  /** A different *track* of the same book is still the same book. */
  @Test
  fun `jumping to another track of the same book does not flush`() {
    assertFalse(
      shouldFlushOutgoingBook(track("2007", bookId = "1001"), incomingBookId = "1001"),
    )
  }

  /** Cold start: nothing was playing, so there is no position to preserve. */
  @Test
  fun `starting from nothing does not flush`() {
    assertFalse(shouldFlushOutgoingBook(EMPTY_TRACK, incomingBookId = "1001"))
  }

  /** A track with no parent cannot be attributed to a book; reporting it would be guesswork. */
  @Test
  fun `a track with no parent book does not flush`() {
    assertFalse(
      shouldFlushOutgoingBook(track("2001", bookId = ""), incomingBookId = "1001"),
    )
  }

  /**
   * Ids are Strings and need not be numeric (cu-71, decision-11), so the comparison must be by
   * value with no parsing.
   */
  @Test
  fun `non-numeric book ids compare by value`() {
    assertTrue(
      shouldFlushOutgoingBook(track("t1", bookId = "local:/books/dune"), incomingBookId = "abc-9"),
    )
    assertFalse(
      shouldFlushOutgoingBook(
        track("t1", bookId = "local:/books/dune"),
        incomingBookId = "local:/books/dune",
      ),
    )
  }
}
