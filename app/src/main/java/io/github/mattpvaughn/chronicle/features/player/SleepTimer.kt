package io.github.mattpvaughn.chronicle.features.player

import android.app.Service
import android.hardware.SensorManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaControllerCompat
import android.widget.Toast
import com.squareup.seismic.ShakeDetector
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.SleepTimer.SleepTimerAction.*
import io.github.mattpvaughn.chronicle.util.showToast
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser
import timber.log.Timber
import javax.inject.Inject

/**
 * A countdown timer which pauses playback at the end of countdown.
 *
 * Note: only counts down while playback is active
 */
interface SleepTimer {
  fun cancel()

  fun start(justStarting: Boolean = false)

  fun update(timeRemaining: Long)

  /**
   * Extends a running fixed-duration timer.
   *
   * @return whether anything was extended. False when no fixed-duration timer is running — the
   *   shake gesture uses this to avoid confirming an extension that did not happen.
   */
  fun extend(extensionDurationMS: Long): Boolean

  /**
   * Starts a timer that pauses when the current chapter ends (cu-21).
   *
   * Distinct from `start` with a duration: it carries no deadline, so it cannot be desynced by a
   * seek or a speed change. See [SleepTimerMode.EndOfChapter].
   */
  fun startEndOfChapter()

  fun handleAction(
    action: SleepTimerAction,
    durationMillis: Long,
  )

  companion object {
    const val ACTION_SLEEP_TIMER_CHANGE = "action sleep timer change"
    const val ARG_SLEEP_TIMER_ACTION = "arg sleep timer action"
    const val ARG_SLEEP_TIMER_DURATION_MILLIS = "sleep_timer_duration"

    /**
     * Whether a timer is counting (cu-21).
     *
     * Sent explicitly because the UI used to infer it from the duration being above zero — which
     * is wrong for an end-of-chapter timer, since that has no countdown and publishes 0. Inferring
     * it would show an active timer as inactive: the button unlit, and the chooser offering
     * durations instead of a cancel.
     */
    const val ARG_SLEEP_TIMER_IS_ACTIVE = "sleep_timer_is_active"
  }

  enum class SleepTimerAction {
    BEGIN,

    /** Begin a timer that ends with the current chapter, carrying no duration (cu-21). */
    BEGIN_END_OF_CHAPTER,
    EXTEND,
    CANCEL,
    UPDATE,
  }

  interface SleepTimerBroadcaster {
    fun broadcastUpdate(
      sleepTimerAction: SleepTimerAction,
      durationMillis: Long = 0L,
      isActive: Boolean = durationMillis > 0L,
    )
  }
}

class SimpleSleepTimer
  @Inject
  constructor(
    private val service: Service,
    private val broadcastManager: SleepTimer.SleepTimerBroadcaster,
    private val mediaController: MediaControllerCompat,
    private val sensorManager: SensorManager,
    private val toneGenerator: ToneGenerator,
    private val prefsRepo: PrefsRepo,
    private val currentlyPlaying: CurrentlyPlaying,
  ) : SleepTimer {
    private val sleepTimerUpdateFrequencyMs = 1000L
    private val sleepTimerHandler = Handler(Looper.getMainLooper())
    private val updateSleepTimerAction = { start(false) }
    private val shakeToSnoozeDurationMs = 5 * 60 * 1000L
    private val shakeOccurredSoundDurationMs = 150

    /**
     * How long an expired timer waits for a resume before forgetting itself: one hour of ticks.
     */
    private val expiredTickBudget = 60 * 60L

    /**
     * The whole of the timer's state, owned here and reasoned about in [SleepTimerLogic].
     *
     * Was three fields (`sleepTimeRemaining`, `isActive`, and the implicit "was it cancelled or did
     * it fire?" that did not exist) which could disagree — `extend` could leave a positive
     * remaining time with `isActive` false, and the next tick would count down a timer nobody set.
     */
    private var state: SleepTimerState = SleepTimerState.Idle

    /**
     * Whether the last tick saw audio flowing.
     *
     * The re-arm has to happen on the *transition* into playing, not on every tick while playing,
     * or an expired timer would re-arm and immediately be re-armed again.
     */
    private var wasPlaying = false

    /**
     * Whether a tick is scheduled.
     *
     * Tracked separately from [state] because the two answer different questions: the state says
     * what the timer *is*, this says whether the loop is running. Conflating them is what made
     * `start(justStarting = true)` a no-op — see the guard in [start].
     */
    private var isTicking = false

    /**
     * Ticks spent in [SleepTimerState.Expired], against [expiredTickBudget].
     *
     * Reset whenever the timer is armed or re-armed, so it only counts a *continuous* unused
     * stretch.
     */
    private var expiredTicks = 0L

    private val shakeDetector =
      ShakeDetector(
        ShakeDetector.Listener {
          Timber.i("Shake detected. Extending")
          if (prefsRepo.shakeToSnooze) {
            // The tone and toast belong to the *gesture*, not to extending: a shake needs
            // confirmation because the user cannot see whether it registered, while the "+5
            // minutes" menu item is its own confirmation. `extend` therefore stays silent, and
            // only announces here — and only when it actually did something, since a shake with
            // no timer running is a no-op.
            if (extend(shakeToSnoozeDurationMs)) {
              toneGenerator.startTone(
                ToneGenerator.TONE_CDMA_PIP,
                shakeOccurredSoundDurationMs,
              )
              showToast(
                service,
                BottomSheetChooser.FormattableString.from(
                  R.string.sleep_timer_extended_message,
                ),
                Toast.LENGTH_SHORT,
              )
            }
          }
        },
      )

    override fun handleAction(
      action: SleepTimer.SleepTimerAction,
      durationMillis: Long,
    ) {
      when (action) {
        // Outbound-only; the service filters it before it reaches here. Handled for exhaustiveness,
        // and because accepting it would let the timer's own broadcast reset its state.
        UPDATE -> Timber.w("Ignoring an inbound UPDATE: it is what this timer publishes")
        EXTEND -> extend(durationMillis)
        CANCEL -> cancel()
        BEGIN -> {
          update(durationMillis)
          start(true)
        }
        BEGIN_END_OF_CHAPTER -> startEndOfChapter()
      }
    }

    /**
     * The user turned the timer off: forget everything, including the duration.
     *
     * The counterpart to [expire], which keeps the duration so the timer can re-arm. Conflating
     * the two is what cu-21 fixed — expiry used to call this, so a fired timer was indistinguishable
     * from a dismissed one and resuming left the user with nothing.
     */
    override fun cancel() {
      // no need to broadcast a cancel, the cancel has to come from the UI, and the UI for the
      // sleep timer is a single point as of now
      Timber.i("Sleep timer canceled")
      shakeDetector.stop()
      sleepTimerHandler.removeCallbacksAndMessages(null)
      state = SleepTimerState.Idle
      wasPlaying = false
      isTicking = false
      expiredTicks = 0L
    }

    override fun startEndOfChapter() {
      val chapterId = currentlyPlaying.chapter.value.id
      if (chapterId.isEmpty()) {
        Timber.w("Cannot set an end-of-chapter timer: no chapter is playing")
        return
      }
      Timber.i("Sleep timer set to end of chapter $chapterId")
      state = SleepTimerState.Running(SleepTimerMode.EndOfChapter(chapterId))
      beginTicking()
    }

    override fun start(justStarting: Boolean) {
      if (justStarting) {
        // Cannot start a new timer if there is already one *ticking*. Guarded on the handler, not
        // on the state: `BEGIN` is a two-step `update(duration)` then `start(true)`, and `update`
        // already leaves the state Running — so guarding on "is the state active?" made every
        // `BEGIN` return here without ever scheduling a tick, and the timer sat at its full
        // duration forever. We return rather than throw because the downside of ignoring a
        // duplicate start is small.
        if (isTicking) {
          return
        }
        if (state !is SleepTimerState.Running) {
          // A BEGIN with no duration set is a caller error; starting nothing is better than
          // counting down from zero and pausing immediately.
          Timber.w("Sleep timer asked to start with no duration set")
          return
        }
        beginTicking()
        return
      }
      tick()
    }

    /** Arms the shake detector and schedules the first tick. */
    private fun beginTicking() {
      shakeDetector.start(sensorManager, SensorManager.SENSOR_DELAY_GAME)
      wasPlaying = mediaController.playbackState.isPlaying
      expiredTicks = 0L
      publish()
      sleepTimerHandler.removeCallbacksAndMessages(null)
      isTicking = true
      sleepTimerHandler.postDelayed(updateSleepTimerAction, sleepTimerUpdateFrequencyMs)
    }

    /**
     * One tick: re-arm if playback just resumed, then advance the timer and apply the effect.
     */
    private fun tick() {
      val isPlaying = mediaController.playbackState.isPlaying
      val chapterId = currentlyPlaying.chapter.value.id

      if (isPlaying && !wasPlaying) {
        val resumed =
          SleepTimerLogic.onPlaybackResumed(
            state = state,
            autoRestartEnabled = prefsRepo.autoRestartSleepTimer,
            currentChapterId = chapterId,
          )
        if (resumed !== state) {
          Timber.i("Playback resumed; re-arming the sleep timer")
          state = resumed
          expiredTicks = 0L
          shakeDetector.start(sensorManager, SensorManager.SENSOR_DELAY_GAME)
          publish()
        }
      }
      wasPlaying = isPlaying

      val (next, effect) =
        SleepTimerLogic.tick(
          state = state,
          isPlaying = isPlaying,
          currentChapterId = chapterId,
          tickMillis = sleepTimerUpdateFrequencyMs,
        )
      state = next

      when (effect) {
        is SleepTimerEffect.Publish -> publish(effect.remainingMillis)
        SleepTimerEffect.PauseAndExpire -> expire()
        SleepTimerEffect.None -> Unit
      }

      // Keep ticking while there is anything to watch for. An expired timer still ticks, because
      // that is how it notices playback resuming — the whole point of cu-21.
      //
      // But it does not tick forever. Someone who never resumes and never cancels would otherwise
      // leave a 1 Hz handler post running for the life of the service, which is exactly the kind of
      // per-second work cu-110 was about. After [expiredTickBudget] the timer gives up and forgets
      // itself: an hour after falling asleep, "press play and get the same timer back" is no longer
      // what a resume means.
      when {
        state is SleepTimerState.Idle -> isTicking = false
        state is SleepTimerState.Expired && ++expiredTicks > expiredTickBudget -> {
          Timber.i("Expired sleep timer went unused for an hour; forgetting it")
          cancel()
        }
        else -> sleepTimerHandler.postDelayed(updateSleepTimerAction, sleepTimerUpdateFrequencyMs)
      }
    }

    /**
     * The timer fired: pause playback but **keep** the duration so it can re-arm on resume.
     */
    private fun expire() {
      Timber.i("Sleep timer expired; pausing playback")
      shakeDetector.stop()
      publish()
      mediaController.transportControls.pause()
    }

    /**
     * Publishes the current state to the UI.
     *
     * The active flag comes from the state, never from the remaining time: an end-of-chapter timer
     * is active with a remaining time of 0.
     */
    private fun publish(remainingMillis: Long = SleepTimerLogic.remainingMillis(state)) {
      broadcastManager.broadcastUpdate(
        UPDATE,
        remainingMillis,
        isActive = SleepTimerLogic.isActive(state),
      )
    }

    /**
     * Seeds a fixed duration, ahead of [start].
     *
     * Only [SleepTimer.SleepTimerAction.BEGIN] reaches this now — it is the two-step "set the
     * duration, then arm it" the interface has always had.
     */
    override fun update(timeRemaining: Long) {
      state = SleepTimerState.Running(SleepTimerMode.FixedDuration(timeRemaining))
    }

    /** @return whether a timer was actually extended, so a caller can confirm only if so. */
    override fun extend(extensionDurationMS: Long): Boolean {
      val extended = SleepTimerLogic.extend(state, extensionDurationMS)
      if (extended === state) {
        Timber.i("Ignoring an extension: no fixed-duration timer is running")
        return false
      }
      Timber.i("Sleep timer extended by $extensionDurationMS milliseconds")
      state = extended
      publish()
      return true
    }
  }
