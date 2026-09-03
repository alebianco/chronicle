package io.github.mattpvaughn.chronicle.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep timer's semantics (cu-21).
 *
 * The timer had **no tests at all** before this: it was welded to a `Service`, a
 * `MediaControllerCompat` and a real `Handler`. `SleepTimerLogic` is the decision layer pulled out
 * of it, so the behaviours that matter — a countdown that only runs while playing, expiry that can
 * be told apart from cancellation, and an end-of-chapter timer that derives no deadline — can be
 * driven directly.
 */
class SleepTimerLogicTest {
  private val tick = 1_000L

  private fun tick(
    state: SleepTimerState,
    isPlaying: Boolean = true,
    chapterId: String = "ch1",
  ) = SleepTimerLogic.tick(state, isPlaying, chapterId, tick)

  /**
   * A running fixed timer. [original] defaults to [millis] — the shape when a timer has just been
   * set — and is given explicitly where a test needs a partly-elapsed one.
   */
  private fun running(
    millis: Long,
    original: Long = millis,
  ) = SleepTimerState.Running(SleepTimerMode.FixedDuration(millis, original))

  // ---- fixed duration ----

  @Test
  fun `a fixed timer counts down while playing`() {
    val (next, effect) = tick(running(10_000L))

    assertEquals(running(9_000L, original = 10_000L), next)
    assertEquals(SleepTimerEffect.Publish(9_000L), effect)
  }

  /**
   * The reason a sleep timer is measured in *listening* time: pausing to answer the door must not
   * eat it.
   */
  @Test
  fun `a fixed timer holds while paused`() {
    val (next, effect) = tick(running(10_000L), isPlaying = false)

    assertEquals("the remaining time must not move", running(10_000L), next)
    assertEquals(
      "but it keeps publishing, so the countdown display does not go blank",
      SleepTimerEffect.Publish(10_000L),
      effect,
    )
  }

  @Test
  fun `a fixed timer expires when it runs out`() {
    val (next, effect) = tick(running(tick))

    assertEquals(SleepTimerEffect.PauseAndExpire, effect)
    assertTrue("expiry must be distinguishable from cancellation", next is SleepTimerState.Expired)
  }

  /**
   * Expiry keeps the duration it fired with. This is the whole mechanism behind the acceptance
   * criterion — a timer that forgot its duration on firing (which is what `cancel()` used to do)
   * cannot re-arm.
   */
  @Test
  fun `an expired timer remembers the duration it fired with`() {
    val (expired, _) = tick(running(millis = tick, original = 30 * 60_000L))

    assertEquals(
      "the *picked* duration is what a re-arm restores, not the sliver left at expiry",
      30 * 60_000L,
      ((expired as SleepTimerState.Expired).mode as SleepTimerMode.FixedDuration).originalMillis,
    )
  }

  /**
   * The first version of this stored only the remaining time, so a re-arm gave a one-second timer.
   * Caught by the end-to-end case below; pinned here directly.
   */
  @Test
  fun `re-arming restores the picked duration, not the remainder`() {
    val expired =
      SleepTimerState.Expired(SleepTimerMode.FixedDuration(remainingMillis = 0L, originalMillis = 30 * 60_000L))

    val resumed =
      SleepTimerLogic.onPlaybackResumed(expired, autoRestartEnabled = true, currentChapterId = "ch1")

    assertEquals(30 * 60_000L, SleepTimerLogic.remainingMillis(resumed))
  }

  @Test
  fun `an idle timer does nothing`() {
    val (next, effect) = tick(SleepTimerState.Idle)

    assertSame(SleepTimerState.Idle, next)
    assertEquals(SleepTimerEffect.None, effect)
  }

  @Test
  fun `an expired timer does not keep counting`() {
    val expired = SleepTimerState.Expired(SleepTimerMode.FixedDuration(5_000L))

    val (next, effect) = tick(expired)

    assertEquals(expired, next)
    assertEquals(SleepTimerEffect.None, effect)
  }

  // ---- the acceptance criterion: resume without a manual reset ----

  @Test
  fun `resuming playback re-arms an expired timer`() {
    val expired = SleepTimerState.Expired(SleepTimerMode.FixedDuration(0L, 30 * 60_000L))

    val resumed =
      SleepTimerLogic.onPlaybackResumed(expired, autoRestartEnabled = true, currentChapterId = "ch1")

    assertEquals(
      "the user must not have to pick a duration again",
      running(30 * 60_000L),
      resumed,
    )
  }

  @Test
  fun `resuming does not re-arm when the preference is off`() {
    val expired = SleepTimerState.Expired(SleepTimerMode.FixedDuration(0L, 30 * 60_000L))

    val resumed =
      SleepTimerLogic.onPlaybackResumed(expired, autoRestartEnabled = false, currentChapterId = "ch1")

    assertSame(expired, resumed)
  }

  /** Resuming a *running* timer must not reset it to its original duration. */
  @Test
  fun `resuming leaves a running timer alone`() {
    val partway = running(4_000L)

    val resumed =
      SleepTimerLogic.onPlaybackResumed(partway, autoRestartEnabled = true, currentChapterId = "ch1")

    assertSame(partway, resumed)
  }

  @Test
  fun `resuming with no timer does not invent one`() {
    val resumed =
      SleepTimerLogic.onPlaybackResumed(
        SleepTimerState.Idle,
        autoRestartEnabled = true,
        currentChapterId = "ch1",
      )

    assertSame(SleepTimerState.Idle, resumed)
  }

  /**
   * A re-armed end-of-chapter timer must adopt the chapter playing **now**. Re-arming to the
   * chapter it expired in would wait for a boundary already crossed, so the timer would never
   * fire again.
   */
  @Test
  fun `re-arming end-of-chapter adopts the current chapter`() {
    val expired = SleepTimerState.Expired(SleepTimerMode.EndOfChapter("ch1"))

    val resumed =
      SleepTimerLogic.onPlaybackResumed(expired, autoRestartEnabled = true, currentChapterId = "ch2")

    assertEquals(SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch2")), resumed)
  }

  // ---- end of chapter ----

  @Test
  fun `end-of-chapter holds while the chapter is unchanged`() {
    val state = SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch1"))

    val (next, effect) = tick(state, chapterId = "ch1")

    assertSame(state, next)
    assertEquals(SleepTimerEffect.None, effect)
  }

  @Test
  fun `end-of-chapter expires when the chapter changes`() {
    val state = SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch1"))

    val (next, effect) = tick(state, chapterId = "ch2")

    assertEquals(SleepTimerEffect.PauseAndExpire, effect)
    assertEquals(SleepTimerState.Expired(SleepTimerMode.EndOfChapter("ch1")), next)
  }

  /**
   * The bug this mode replaces: the old implementation baked
   * `(chapterDuration - chapterProgress) / speed` into a countdown at pick time, so seeking
   * backwards left it firing mid-chapter and seeking forwards left it firing long after. Watching
   * the chapter cannot drift, because no deadline is derived.
   */
  @Test
  fun `end-of-chapter survives a seek within the chapter`() {
    var state: SleepTimerState = SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch1"))

    // Many ticks, as if the user seeked back and forth for an hour inside one chapter.
    repeat(3_600) {
      val (next, effect) = tick(state, chapterId = "ch1")
      state = next
      assertEquals("a seek inside the chapter must never expire the timer", SleepTimerEffect.None, effect)
    }

    assertTrue(SleepTimerLogic.isActive(state))
  }

  /**
   * A book with no chapter metadata publishes an empty id. Treating that as a change would pause
   * playback about a second after the user set the timer.
   */
  @Test
  fun `end-of-chapter does not fire when no chapter is known`() {
    val state = SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch1"))

    val (next, effect) = tick(state, chapterId = "")

    assertSame(state, next)
    assertEquals(SleepTimerEffect.None, effect)
  }

  // ---- extend ----

  @Test
  fun `extending adds to a running fixed timer`() {
    val extended = SleepTimerLogic.extend(running(60_000L), 5 * 60_000L)

    assertEquals(running(6 * 60_000L), extended)
  }

  /**
   * Extending while idle used to leave a positive remaining time with nothing armed, so the next
   * tick counted down a timer the user never set.
   */
  @Test
  fun `extending an idle timer is a no-op`() {
    assertSame(SleepTimerState.Idle, SleepTimerLogic.extend(SleepTimerState.Idle, 5 * 60_000L))
  }

  @Test
  fun `extending an expired timer is a no-op`() {
    val expired = SleepTimerState.Expired(SleepTimerMode.FixedDuration(1_000L))

    assertSame(expired, SleepTimerLogic.extend(expired, 5 * 60_000L))
  }

  /** An end-of-chapter timer has no duration to add to; converting it would discard the request. */
  @Test
  fun `extending an end-of-chapter timer is a no-op`() {
    val state = SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch1"))

    assertSame(state, SleepTimerLogic.extend(state, 5 * 60_000L))
  }

  /**
   * The bug that shipped for a second in cu-21, found on device.
   *
   * `ACTION_SLEEP_TIMER_CHANGE` carries commands *into* the timer and its ticks *out* of it, and
   * the service listened to the same action it broadcast on. So the timer's own `UPDATE(0)` came
   * straight back as a command. That was invisible while `update` only reassigned a Long to
   * itself; once the state carried a *mode*, the loop rewrote an end-of-chapter timer as a
   * zero-length countdown, which expired one tick later — the timer fired a second after being
   * set, mid-chapter.
   *
   * The service now filters `UPDATE`. This pins the shape of the damage so a future change that
   * reopens the loop fails here rather than on a device: a zero-duration fixed timer expires
   * immediately, so nothing may ever construct one from a tick.
   */
  @Test
  fun `a zero-length fixed timer expires at once, so nothing may create one from a tick`() {
    val (next, effect) = tick(running(0L))

    assertEquals(SleepTimerEffect.PauseAndExpire, effect)
    assertTrue(next is SleepTimerState.Expired)
  }

  // ---- readouts ----

  @Test
  fun `remaining time is zero when nothing is counting`() {
    assertEquals(0L, SleepTimerLogic.remainingMillis(SleepTimerState.Idle))
    assertEquals(
      0L,
      SleepTimerLogic.remainingMillis(
        SleepTimerState.Expired(SleepTimerMode.FixedDuration(5_000L)),
      ),
    )
    assertEquals(
      "an end-of-chapter timer has no countdown to show",
      0L,
      SleepTimerLogic.remainingMillis(SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch1"))),
    )
  }

  @Test
  fun `only a running timer reports as active`() {
    assertTrue(SleepTimerLogic.isActive(running(1_000L)))
    assertTrue(SleepTimerLogic.isActive(SleepTimerState.Running(SleepTimerMode.EndOfChapter("ch1"))))
    assertFalse(SleepTimerLogic.isActive(SleepTimerState.Idle))
    assertFalse(
      "an expired timer is not active, even though it is remembered",
      SleepTimerLogic.isActive(SleepTimerState.Expired(SleepTimerMode.FixedDuration(1_000L))),
    )
  }

  /**
   * The full sequence the acceptance criterion describes, end to end: count down, expire, pause,
   * resume, and be counting again with no user action.
   */
  @Test
  fun `a timer runs out and comes back on resume without a manual reset`() {
    var state: SleepTimerState = running(3 * tick)

    // Counting down while playing.
    repeat(2) {
      val (next, _) = tick(state)
      state = next
    }
    assertEquals(running(tick, original = 3 * tick), state)

    // The last tick fires it.
    val (expired, effect) = tick(state)
    state = expired
    assertEquals(SleepTimerEffect.PauseAndExpire, effect)

    // Paused: nothing happens, and the duration is still remembered.
    val (held, heldEffect) = tick(state, isPlaying = false)
    state = held
    assertEquals(SleepTimerEffect.None, heldEffect)

    // The user presses play. No timer is set by hand.
    state = SleepTimerLogic.onPlaybackResumed(state, autoRestartEnabled = true, currentChapterId = "ch1")

    assertTrue("the timer must be counting again", SleepTimerLogic.isActive(state))
    assertEquals(
      "and with the duration it originally fired with",
      3 * tick,
      SleepTimerLogic.remainingMillis(state),
    )
  }
}
