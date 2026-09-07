package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.features.player.SleepTimer.SleepTimerAction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sleep timer's two channels, replacing `LocalBroadcastManager`.
 *
 * `LocalBroadcastManager` is deprecated by AndroidX, which points at observable state for
 * in-process events; this project standardised on that (convention 3). But the interesting part of
 * the migration is not the transport — it is that the old design put **both directions on one
 * action**.
 *
 * `ACTION_SLEEP_TIMER_CHANGE` carried commands from the UI *into* the timer and the timer's ticks
 * *out* to the UI, and the service listened to the action it broadcast on. So the timer's own
 * `UPDATE` came back to it as a command. That was harmless while `update` reassigned a Long to
 * itself; once the state carried a mode it rewrote an end-of-chapter timer as a zero-length
 * countdown, which expired one tick later — the timer fired a second after being set, mid-chapter.
 * The fix at the time was for the receiver to filter `UPDATE` out.
 *
 * **That filter is not reproduced here, because there is nothing left to filter.** The two
 * directions are two flows:
 *
 * - [commands] — UI → timer. Only the UI emits; only the service collects.
 * - [updates] — timer → UI. Only the timer emits; only the UI collects.
 *
 * A tick cannot arrive as a command because it is not on that flow at all. The old hazard needed a
 * guard *and* a comment explaining the guard; this needs neither, and a future change cannot
 * reopen the loop without visibly wiring an emitter to the wrong flow.
 *
 * `SleepTimerBusTest` pins that separation, and `SleepTimerLogicTest` still pins the shape of the
 * original damage (a zero-length fixed timer expires immediately), so the consequence stays
 * covered even though the path to it is gone.
 *
 * **Why `MutableSharedFlow` and not `StateFlow`.** A `CANCEL` followed by another `CANCEL` must
 * deliver twice, and `StateFlow` conflates equal values.
 *
 * The two flows then differ in replay, deliberately. [commands] replays nothing: a command is an
 * event, and a screen returning to the foreground must not re-issue one it already issued.
 * [updates] replays the latest, because an update is *state* — it carries the whole timer state,
 * not a delta — and a late subscriber that receives nothing would render an armed end-of-chapter
 * timer as inactive. See the comment on `_updates`.
 */
@Singleton
class SleepTimerBus
  @Inject
  constructor() {
    private val _commands =
      MutableSharedFlow<SleepTimerCommand>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
      )

    // `replay = 1`, unlike [_commands], and the asymmetry is load-bearing.
    //
    // An end-of-chapter timer publishes **only** when armed and when it expires: `tick` returns
    // `SleepTimerEffect.None` for it on every intervening second, because it has no countdown to
    // report. So with no replay, a screen that subscribes *after* the arming — the player sheet
    // re-expanded, or the activity recreated under memory pressure, which resets the ViewModel's
    // `_isSleepTimerActive` to false — receives nothing and will receive nothing until the chapter
    // ends. The button reads unlit and the chooser offers durations instead of a cancel, while the
    // timer is armed and about to pause playback.
    //
    // That is the exact failure the explicit `isActive` flag was introduced to prevent, and
    // without this replay it comes back through the subscription-timing dimension instead of the
    // inference one. A fixed-duration timer self-heals within a second (its paused arm keeps
    // publishing), which is what makes the end-of-chapter case easy to miss on a device.
    //
    // Replaying is safe here precisely because an update is *state*, not an event: it carries the
    // whole timer state rather than a delta, so re-delivering the latest one is idempotent.
    private val _updates =
      MutableSharedFlow<SleepTimerUpdate>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )

    /** What the UI asks the timer to do. Collected by `MediaPlayerService`. */
    val commands: SharedFlow<SleepTimerCommand> = _commands.asSharedFlow()

    /** What the timer reports. Collected by the player screen. */
    val updates: SharedFlow<SleepTimerUpdate> = _updates.asSharedFlow()

    /**
     * Sends a command to the timer.
     *
     * **Delivered only if the service is collecting.** `emit` on a `replay = 0` `SharedFlow` with
     * no subscriber returns immediately and discards the value — it suspends only when a *slow*
     * subscriber exists and the buffer is full. This is the same behaviour
     * `LocalBroadcastManager.sendBroadcast` had with no registered receiver, so it is parity, not
     * a regression.
     *
     * It is safe today because `MediaPlayerService.onCreate` subscribes before the sleep-timer
     * chooser can be reached: the player sheet's visibility is derived from playback state, so the
     * service is always running by then. **A new entry point that could set a timer with the
     * service down — Android Auto, a widget, a shortcut — would silently drop the command**, and
     * would need to start the service first or check `subscriptionCount`.
     */
    suspend fun command(command: SleepTimerCommand) = _commands.emit(command)

    /**
     * Publishes the timer's current state.
     *
     * Non-suspending, because the timer ticks from a `Handler` on the main looper and must not
     * block there. A dropped tick is invisible — the next one is a second away and carries the
     * whole state, not a delta.
     */
    fun publish(update: SleepTimerUpdate) {
      _updates.tryEmit(update)
    }
  }

/**
 * A command travelling UI → timer.
 *
 * [SleepTimerAction.UPDATE] is deliberately not representable: it was never a command, and
 * modelling the two directions with one enum is what allowed a tick to be mistaken for one.
 */
data class SleepTimerCommand(
  val action: SleepTimerAction,
  val durationMillis: Long = 0L,
) {
  init {
    require(action != SleepTimerAction.UPDATE) {
      "UPDATE is a report, not a command — publish it on SleepTimerBus.updates instead"
    }
  }
}

/**
 * The timer's state, travelling timer → UI.
 *
 * [isActive] is carried explicitly rather than inferred from [remainingMillis]: an end-of-chapter
 * timer is active with nothing to count down, so a `remainingMillis > 0` test reports it as
 * inactive — the button unlit, and the chooser offering durations instead of a cancel.
 */
data class SleepTimerUpdate(
  val remainingMillis: Long,
  val isActive: Boolean,
)
