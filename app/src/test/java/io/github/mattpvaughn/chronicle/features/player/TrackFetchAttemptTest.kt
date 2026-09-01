package io.github.mattpvaughn.chronicle.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry budget on the empty-track playback path (cu-97).
 *
 * Found by review rather than on the device: it needs a book that resolves to zero tracks *after* a
 * successful fetch, so it never came up in ordinary use. The consequence if the budget is wrong is
 * not a wrong screen but a request loop against the user's Plex server.
 */
class TrackFetchAttemptTest {
  /** The legitimate case: nothing local, so fetch once. */
  @Test
  fun `the first attempt is allowed`() {
    assertTrue(mayFetchTracksAgain(attemptsAlreadyMade = 0))
  }

  /**
   * The bug. `handlePlayBookWithNoTracks` called `playBook` again on success, which found no tracks
   * again and came straight back — one network request per pass, forever.
   */
  @Test
  fun `a second attempt is refused`() {
    assertFalse(
      "a fetch that succeeds while yielding no tracks must not be retried; that is the loop",
      mayFetchTracksAgain(attemptsAlreadyMade = 1),
    )
  }

  /** Guards the comparison's direction: nothing beyond the budget may pass either. */
  @Test
  fun `no further attempt is allowed once the budget is spent`() {
    assertFalse(mayFetchTracksAgain(attemptsAlreadyMade = 2))
    assertFalse(mayFetchTracksAgain(attemptsAlreadyMade = 17))
  }

  /**
   * Pins the budget itself. Raising it is a deliberate decision about how hard the app may push a
   * server that has already answered, not a tuning knob — so a change here should fail a test and
   * be argued for in the commit.
   */
  @Test
  fun `the budget is one attempt`() {
    assertEquals(1, MAX_TRACK_FETCH_ATTEMPTS)
  }
}
