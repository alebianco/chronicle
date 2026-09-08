package io.github.mattpvaughn.chronicle.features.player

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.SleepTimerAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
  @Test
  fun `a published update never arrives on the command flow`() =
    runTest {
      turbineScope {
        val bus = SleepTimerBus()
        val commands = bus.commands.testIn(backgroundScope)
        val updates = bus.updates.testIn(backgroundScope)

        // What the timer does every second.
        repeat(3) { bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true)) }

        // Drain the ticks on the flow they *should* reach. `expectNoEvents` means "nothing has
        // arrived **yet**", not "nothing ever will" — measured: it passes with an emission still
        // pending behind a delay. So without this barrier the assertion below could pass merely by
        // running before a leak rather than because there is none. Once all three updates have
        // landed, any command the same `publish` calls might have leaked has had its chance.
        //
        // `replay = 1` on updates means the first `awaitItem` may return the replayed value, so
        // this drains by count rather than asserting a specific one.
        repeat(3) { updates.awaitItem() }

        commands.expectNoEvents()
      }
    }

  @Test
  fun `a command never arrives on the update flow`() =
    runTest {
      turbineScope {
        val bus = SleepTimerBus()
        val commands = bus.commands.testIn(backgroundScope)
        val updates = bus.updates.testIn(backgroundScope)

        bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))

        // The same barrier as above, in the other direction: await the command on its own flow
        // first, so "no update arrived" is a statement about a delivery that has happened rather
        // than one that has not been given the chance.
        commands.awaitItem()

        updates.expectNoEvents()
      }
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
      turbineScope {
        val bus = SleepTimerBus()
        val real = SleepTimerAction.entries.filter { it != SleepTimerAction.UPDATE }
        val commands = bus.commands.testIn(backgroundScope)

        real.forEach { bus.command(SleepTimerCommand(it, durationMillis = 60_000L)) }

        // `awaitItem` per action asserts the **order** as well as the contents, which the old
        // list comparison did only incidentally.
        val received = real.map { commands.awaitItem() }
        assertEquals(
          "the guard must block only UPDATE, not narrow the channel",
          real,
          received.map { it.action },
        )
        assertTrue(
          "the duration must survive the trip",
          received.all { it.durationMillis == 60_000L },
        )
        commands.expectNoEvents()
      }
    }

  @Test
  fun `an end-of-chapter tick keeps isActive true with nothing remaining`() =
    runTest {
      // The distinction the old channel carried in an extra and the UI used to infer wrongly: an
      // end-of-chapter timer is active with 0 remaining, so inferring `isActive` from the duration
      // showed it as off — the button unlit, the chooser offering durations instead of a cancel.
      turbineScope {
        val bus = SleepTimerBus()
        val updates = bus.updates.testIn(backgroundScope)

        bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true))

        assertEquals(SleepTimerUpdate(0L, isActive = true), updates.awaitItem())
        // Kept for parity with the neighbouring tests: the old assertion compared a whole list, so
        // dropping this would make the converted version strictly weaker than what it replaced.
        updates.expectNoEvents()
      }
    }

  @Test
  fun `two identical commands both arrive`() =
    runTest {
      // Why this is a SharedFlow and not a StateFlow: a second CANCEL must not be conflated away.
      turbineScope {
        val bus = SleepTimerBus()
        val commands = bus.commands.testIn(backgroundScope)

        bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))
        bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))

        // Both, in order, and nothing else — the conflation this would suffer as a `StateFlow` is
        // now asserted rather than inferred from a count.
        assertEquals(SleepTimerAction.CANCEL, commands.awaitItem().action)
        assertEquals(SleepTimerAction.CANCEL, commands.awaitItem().action)
        commands.expectNoEvents()
      }
    }

  @Test
  fun `a late collector is not replayed a command it already issued`() =
    runTest {
      // Commands are events. A screen returning to the foreground must not re-issue a CANCEL the
      // user pressed before it went away, which is what the receiver's unregister used to ensure.
      turbineScope {
        val bus = SleepTimerBus()
        bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))

        val commands = bus.commands.testIn(backgroundScope)

        commands.expectNoEvents()
      }
    }

  @Test
  fun `a late collector IS given the latest timer state`() =
    runTest {
      // The opposite rule to commands, and the one that matters most for an end-of-chapter timer:
      // it publishes when armed and then not again until the chapter ends, because `tick` returns
      // `None` for it every intervening second. A subscriber arriving in between — the sheet
      // re-expanded, or the activity recreated, which resets the ViewModel's active flag — would
      // otherwise receive nothing and draw an armed timer as off.
      turbineScope {
        val bus = SleepTimerBus()
        bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true))

        val updates = bus.updates.testIn(backgroundScope)

        assertEquals(
          "a late subscriber must inherit the armed state, not an empty screen",
          SleepTimerUpdate(remainingMillis = 0L, isActive = true),
          updates.awaitItem(),
        )
        updates.expectNoEvents()
      }
    }

  @Test
  fun `only the latest state is replayed, not the whole countdown`() =
    runTest {
      // Replay is 1, so a subscriber inherits where the timer *is*, not a burst of stale ticks —
      // the failure mode `launchWhenStarted` has and the reason `collectWhileStarted` exists.
      turbineScope {
        val bus = SleepTimerBus()
        (5 downTo 1).forEach {
          bus.publish(SleepTimerUpdate(remainingMillis = it * 1000L, isActive = true))
        }

        val updates = bus.updates.testIn(backgroundScope)

        assertEquals(
          SleepTimerUpdate(remainingMillis = 1000L, isActive = true),
          updates.awaitItem(),
        )
        // The four stale ticks must **not** follow. The old assertion compared against a
        // single-element list, which said the same thing only because the list happened to be
        // complete when it was read; this states it.
        updates.expectNoEvents()
      }
    }

  /**
   * **A command sent with no subscriber is silently dropped**, and `command` returns rather than
   * suspending.
   *
   * This is the behaviour `SleepTimerBus.command`'s own KDoc describes — `emit` on a `replay = 0`
   * `SharedFlow` with no subscriber discards the value and returns; it suspends only for a *slow*
   * subscriber with a full buffer. It is parity with the `LocalBroadcastManager.sendBroadcast` this
   * replaced, and it is safe today only because `MediaPlayerService.onCreate` subscribes before the
   * sleep-timer chooser is reachable.
   *
   * It is pinned here because the KDoc names a live hazard: **a new entry point that could set a
   * timer with the service down — Android Auto, a widget, a shortcut — would silently drop the
   * command.** Nothing asserted the shape of that failure, so a later "fix" that made `command`
   * suspend instead (a `subscriptionCount` guard, say) would deadlock those callers with no test
   * objecting.
   *
   * **Run on a real dispatcher on purpose.** Under `runTest`'s `StandardTestDispatcher` this
   * measures nothing: a sender parked on a `SharedFlow` is not resumed by `advanceUntilIdle` —
   * trap 1 in `FlowTestExt` — so `sent` stays 0 whether the bus drops *or* blocks, and the
   * assertion cannot fail. An earlier draft of this test made exactly that mistake and recorded
   * "the first emit suspends" as a finding; it is false. Measured three ways: `StandardTestDispatcher`
   * gives `sent = 0` with the job never completing, while `UnconfinedTestDispatcher` and a real
   * dispatcher both give **`sent = 9`, completing immediately**.
   */
  @Test
  fun `a command sent with no subscriber is dropped rather than blocking the caller`() {
    val bus = SleepTimerBus()
    var sent = 0

    val finished =
      runBlocking {
        withTimeoutOrNull(TIMEOUT_MS) {
          repeat(BUFFER_CAPACITY + 1) {
            bus.command(SleepTimerCommand(SleepTimerAction.CANCEL))
            sent++
          }
          true
        }
      }

    assertEquals(
      "every command must return rather than block a caller that has no service listening — " +
        "making this suspend would deadlock Android Auto or a widget setting a timer cold",
      BUFFER_CAPACITY + 1,
      sent,
    )
    assertTrue("the sends must complete well inside $TIMEOUT_MS ms", finished == true)
  }

  /**
   * And the counterpart: updates **do** drop rather than suspend, which is the opposite choice and
   * equally deliberate.
   *
   * `publish` is called from a `Handler` on the main looper every second and is non-suspending for
   * that reason — blocking there would jank playback. A dropped tick is invisible because the next
   * one is a second away and carries the whole state rather than a delta, which is exactly why
   * `_updates` can afford `DROP_OLDEST` where `_commands` cannot.
   */
  @Test
  fun `publishing many updates with no subscriber neither suspends nor throws`() =
    runTest {
      val bus = SleepTimerBus()

      repeat(100) { bus.publish(SleepTimerUpdate(remainingMillis = it * 1000L, isActive = true)) }

      turbineScope {
        val updates = bus.updates.testIn(backgroundScope)

        // Only the latest survives, and `publish` never blocked the caller to achieve it.
        assertEquals(
          SleepTimerUpdate(remainingMillis = 99_000L, isActive = true),
          updates.awaitItem(),
        )
        updates.expectNoEvents()
      }
    }

  private companion object {
    /** `SleepTimerBus._commands`' `extraBufferCapacity`. Named so the two buffer tests say why 9. */
    const val BUFFER_CAPACITY = 8

    /** Generous: the sends should return instantly, so this only bounds a hang. */
    const val TIMEOUT_MS = 3_000L
  }
}
