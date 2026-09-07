package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.features.player.SleepTimer.SleepTimerAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the separation that replaced `ACTION_SLEEP_TIMER_CHANGE`.
 *
 * The bug this replaces: commands and ticks shared one broadcast action, and the service listened to the
 * action it broadcast on, so the timer's own `UPDATE` came back as a command and rewrote an
 * end-of-chapter timer into a zero-length countdown that expired a tick later. The fix at the time
 * was a filter in the receiver — one line, easy to delete, and its absence invisible until a timer
 * fired mid-chapter on a device.
 *
 * `SleepTimerBus` removes the shared channel instead of guarding it. These tests hold that in
 * place: **a tick must never be observable as a command**, by any route. `SleepTimerLogicTest`
 * still pins the *consequence* (a zero-length fixed timer expires immediately), so both the cause
 * and the damage stay covered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerBusTest {
  /**
   * Subscribes to [flow] and returns a live view of what it receives.
   *
   * Two traps here, both measured rather than guessed, and both of the "green for the wrong
   * reason" kind this project keeps hitting.
   *
   * **1. Subscribe before emitting.** Both buses use `replay = 0`, so anything published before a
   * collector exists is dropped. `onSubscription` makes the subscription a fact; without the await,
   * three tests in this file failed outright and — far worse — the two "never arrives" assertions
   * passed **vacuously** on an empty list. They would have stayed green with the feedback
   * loop fully reinstated.
   *
   * **2. `advanceUntilIdle()` does not deliver.** A `backgroundScope` collector of a `SharedFlow`
   * is not resumed by `advanceUntilIdle` — the emission sits in the buffer and the list stays
   * empty. `yield()` does resume it. Measured directly: after `publish`, `advanceUntilIdle` left
   * the list empty and the next `yield` produced the value. So [settle] is what tests call after
   * emitting, never `advanceUntilIdle`.
   *
   * This is also why the buses expose `SharedFlow` rather than `Flow`: `onSubscription` is a
   * `SharedFlow` extension, and testing a `replay = 0` channel honestly depends on it.
   */
  private suspend fun <T> TestScope.record(flow: SharedFlow<T>): List<T> {
    val seen = mutableListOf<T>()
    val subscribed = CompletableDeferred<Unit>()
    backgroundScope.launch {
      flow.onSubscription { subscribed.complete(Unit) }.collect { seen.add(it) }
    }
    subscribed.await()
    return seen
  }

  /** Lets a `backgroundScope` collector run. See [record] — `advanceUntilIdle` will not do. */
  private suspend fun settle() = yield()

  @Test
  fun `a published update never arrives on the command flow`() =
    runTest {
      val bus = SleepTimerBus()
      val commands = record(bus.commands)

      // What the timer does every second.
      repeat(3) { bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true)) }
      settle()

      assertEquals(
        "a tick reached the command flow — the feedback loop is back",
        emptyList<SleepTimerCommand>(),
        commands,
      )
    }

  @Test
  fun `a command never arrives on the update flow`() =
    runTest {
      val bus = SleepTimerBus()
      val updates = record(bus.updates)

      bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))
      settle()

      assertEquals(
        "a command reached the update flow, so the UI would render a request as a report",
        emptyList<SleepTimerUpdate>(),
        updates,
      )
    }

  @Test
  fun `UPDATE cannot be constructed as a command at all`() {
    // The type system carries the rule, so a future caller cannot reintroduce the loop by passing
    // the enum value the old shared channel allowed.
    val thrown =
      assertThrows(IllegalArgumentException::class.java) {
        SleepTimerCommand(SleepTimerAction.UPDATE)
      }

    assertTrue(
      "the message should say where an UPDATE belongs, got: ${thrown.message}",
      thrown.message!!.contains("updates"),
    )
  }

  @Test
  fun `every real command is allowed through unchanged`() =
    runTest {
      val bus = SleepTimerBus()
      val real =
        SleepTimerAction.entries.filter { it != SleepTimerAction.UPDATE }

      val commands = record(bus.commands)

      real.forEach { bus.command(SleepTimerCommand(it, durationMillis = 60_000L)) }
      settle()

      assertEquals(
        "the guard must block only UPDATE, not narrow the channel",
        real,
        commands.map { it.action },
      )
      assertTrue(
        "the duration must survive the trip",
        commands.all { it.durationMillis == 60_000L },
      )
    }

  @Test
  fun `an end-of-chapter tick keeps isActive true with nothing remaining`() =
    runTest {
      // The distinction the old channel carried in an extra and the UI used to infer wrongly: an
      // end-of-chapter timer is active with 0 remaining, so inferring `isActive` from the duration
      // showed it as off — the button unlit, the chooser offering durations instead of a cancel.
      val bus = SleepTimerBus()

      val updates = record(bus.updates)

      bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true))
      settle()

      assertEquals(listOf(SleepTimerUpdate(0L, isActive = true)), updates)
    }

  @Test
  fun `two identical commands both arrive`() =
    runTest {
      // Why this is a SharedFlow and not a StateFlow: a second CANCEL must not be conflated away.
      val bus = SleepTimerBus()

      val commands = record(bus.commands)

      bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))
      bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))
      settle()

      assertEquals(2, commands.size)
    }

  @Test
  fun `a late collector is not replayed a command it already issued`() =
    runTest {
      // Commands are events. A screen returning to the foreground must not re-issue a CANCEL the
      // user pressed before it went away, which is what the receiver's unregister used to ensure.
      val bus = SleepTimerBus()
      bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))

      val commands = record(bus.commands)
      settle()

      assertEquals(
        "a returning screen replayed a command",
        emptyList<SleepTimerCommand>(),
        commands,
      )
    }

  @Test
  fun `a late collector IS given the latest timer state`() =
    runTest {
      // The opposite rule to commands, and the one that matters most for an end-of-chapter timer:
      // it publishes when armed and then not again until the chapter ends, because `tick` returns
      // `None` for it every intervening second. A subscriber arriving in between — the sheet
      // re-expanded, or the activity recreated, which resets the ViewModel's active flag — would
      // otherwise receive nothing and draw an armed timer as off.
      val bus = SleepTimerBus()
      bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true))

      val updates = record(bus.updates)
      settle()

      assertEquals(
        "a late subscriber must inherit the armed state, not an empty screen",
        listOf(SleepTimerUpdate(remainingMillis = 0L, isActive = true)),
        updates,
      )
    }

  @Test
  fun `only the latest state is replayed, not the whole countdown`() =
    runTest {
      // Replay is 1, so a subscriber inherits where the timer *is*, not a burst of stale ticks —
      // the failure mode `launchWhenStarted` has and the reason `collectWhileStarted` exists.
      val bus = SleepTimerBus()
      (5 downTo 1).forEach {
        bus.publish(SleepTimerUpdate(remainingMillis = it * 1000L, isActive = true))
      }

      val updates = record(bus.updates)
      settle()

      assertEquals(
        listOf(SleepTimerUpdate(remainingMillis = 1000L, isActive = true)),
        updates,
      )
    }
}
