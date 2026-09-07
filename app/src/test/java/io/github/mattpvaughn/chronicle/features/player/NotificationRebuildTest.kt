package io.github.mattpvaughn.chronicle.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a notification actually renders, and therefore when a rebuild is redundant.
 *
 * Measured on the tablet before writing this: starting playback of a 107-track book produced **29**
 * notification builds. Two independent causes, and the dedup the task imagined would have hidden
 * the first rather than removed it:
 *
 * 1. `onPlaybackStateChanged` fires **three times per real transition** — 6 of 9 callbacks were
 *    same-state repeats.
 * 2. Every `updateNotification` builds the notification **twice**: once without artwork, then again
 *    inside `postArtwork()`, since `buildNotification` is
 *    `withArtwork(buildNotificationWithoutArtwork(...))`.
 *
 * The key must include the **playback state**, not just the titles: a pause that changed no text
 * still has to re-post, or the notification keeps a pause button that no longer matches.
 */
class NotificationRebuildTest {
  @Test
  fun `an identical state and content is a redundant rebuild`() {
    val first = NotificationContentKey("b1", "t1", "c1", 3)
    val second = NotificationContentKey("b1", "t1", "c1", 3)
    assertTrue("the same content in the same state must be recognised", first == second)
  }

  /** The trap the dead `NotificationData` had a `playbackState` field for. */
  @Test
  fun `a state change with unchanged text is NOT redundant`() {
    val playing = NotificationContentKey("b1", "t1", "c1", 3)
    val paused = NotificationContentKey("b1", "t1", "c1", 2)
    assertFalse("play to pause must re-post even with identical titles", playing == paused)
  }

  @Test
  fun `a chapter change is not redundant`() {
    val ch1 = NotificationContentKey("b1", "t1", "c1", 3)
    val ch2 = NotificationContentKey("b1", "t1", "c2", 3)
    assertFalse(ch1 == ch2)
  }

  @Test
  fun `a track or book change is not redundant`() {
    val base = NotificationContentKey("b1", "t1", "c1", 3)
    assertFalse(base == NotificationContentKey("b1", "t2", "c1", 3))
    assertFalse(base == NotificationContentKey("b2", "t1", "c1", 3))
  }

  /**
   * The dedup must be *stateful* — "same as last time" — not a set, or a legitimate return to a
   * previous state (pause, then resume) would be swallowed.
   */
  @Test
  fun `returning to a previous state still re-posts`() {
    val tracker = NotificationRebuildTracker()
    val playing = NotificationContentKey("b1", "t1", "c1", 3)
    val paused = NotificationContentKey("b1", "t1", "c1", 2)

    assertTrue("the first post always happens", tracker.shouldRebuild(playing))
    assertFalse("an immediate repeat is skipped", tracker.shouldRebuild(playing))
    assertTrue("pause must post", tracker.shouldRebuild(paused))
    assertTrue("resuming must post again, not be treated as already seen", tracker.shouldRebuild(playing))
  }

  @Test
  fun `the burst of identical callbacks collapses to one`() {
    val tracker = NotificationRebuildTracker()
    val key = NotificationContentKey("b1", "t1", "c1", 6)
    val posted = (1..3).count { tracker.shouldRebuild(key) }
    assertEquals("three identical STATE_BUFFERING callbacks must post once", 1, posted)
  }
}
