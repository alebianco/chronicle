package io.github.mattpvaughn.chronicle.features.player

/**
 * What a sleep timer should do next, as a pure function of its state and one tick of input.
 *
 * Extracted from [SimpleSleepTimer] (cu-21) so the semantics are testable without a `Service`, a
 * `MediaControllerCompat`, a `SensorManager` or a real `Handler` — the timer had **no tests at all**
 * before this, and the two behaviours it needed (expiring vs. being cancelled; ending on a chapter
 * boundary rather than a deadline) are exactly the kind that go wrong silently.
 */
sealed interface SleepTimerMode {
  /**
   * Pause after a set amount of *listening* time.
   *
   * [remainingMillis] only decreases while audio is actually playing, so pausing to answer the
   * door does not eat the timer.
   *
   * [originalMillis] is what the user picked, kept **separately** because that is what a re-arm
   * on resume must restore. Re-arming to the remaining time would give a timer of about one
   * second, since a timer expires with almost none left — useless, and the first version of this
   * did exactly that until a test caught it.
   */
  data class FixedDuration(
    val remainingMillis: Long,
    val originalMillis: Long = remainingMillis,
  ) : SleepTimerMode

  /**
   * Pause when the current chapter ends.
   *
   * Deliberately carries **no deadline** — only the chapter it started in. A duration computed at
   * pick time (which is what this used to be) is wrong twice over: a seek does not change it, so
   * it fires mid-chapter or long after; and it was divided by the playback speed at pick time, so
   * any later speed change desynced it — now more likely, since cu-20 made speed per book. Asking
   * "is this still the same chapter?" cannot drift, because it derives no deadline at all.
   */
  data class EndOfChapter(val chapterId: String) : SleepTimerMode
}

/** The timer's lifecycle. */
sealed interface SleepTimerState {
  /** No timer set. */
  data object Idle : SleepTimerState

  /** A timer is counting. */
  data class Running(val mode: SleepTimerMode) : SleepTimerState

  /**
   * The timer fired and playback was paused, but the duration is remembered.
   *
   * The state that did not exist before cu-21, and whose absence *was* the bug: expiry called the
   * same `cancel()` the user's "cancel" did, zeroing everything — so resuming playback left no
   * timer and the user had to pick a duration again. Distinguishing "fired" from "dismissed" is
   * what lets the timer re-arm on resume.
   */
  data class Expired(val mode: SleepTimerMode) : SleepTimerState
}

/** What the caller should do after a tick. */
sealed interface SleepTimerEffect {
  /** Nothing to do. */
  data object None : SleepTimerEffect

  /** Publish the remaining time to the UI. */
  data class Publish(val remainingMillis: Long) : SleepTimerEffect

  /** Pause playback: the timer has run out or the chapter has ended. */
  data object PauseAndExpire : SleepTimerEffect
}

/**
 * The sleep timer's decisions, with no Android types.
 *
 * A tick carries everything the decision needs — whether audio is playing and which chapter is
 * current — rather than reaching for it, so a test can drive any sequence directly.
 */
object SleepTimerLogic {
  /**
   * Advances the timer by [tickMillis].
   *
   * @param isPlaying whether audio is actually flowing. A [SleepTimerMode.FixedDuration] only
   *   counts down while it is, which is why a pause does not consume the timer.
   * @param currentChapterId the chapter playing now, for [SleepTimerMode.EndOfChapter].
   */
  fun tick(
    state: SleepTimerState,
    isPlaying: Boolean,
    currentChapterId: String,
    tickMillis: Long,
  ): Pair<SleepTimerState, SleepTimerEffect> {
    val running = state as? SleepTimerState.Running ?: return state to SleepTimerEffect.None

    return when (val mode = running.mode) {
      is SleepTimerMode.FixedDuration -> {
        if (!isPlaying) {
          // Hold, and keep publishing: the countdown display should not go blank while paused.
          return state to SleepTimerEffect.Publish(mode.remainingMillis)
        }
        val remaining = mode.remainingMillis - tickMillis
        if (remaining <= 0L) {
          SleepTimerState.Expired(mode) to SleepTimerEffect.PauseAndExpire
        } else {
          SleepTimerState.Running(mode.copy(remainingMillis = remaining)) to
            SleepTimerEffect.Publish(remaining)
        }
      }

      is SleepTimerMode.EndOfChapter -> {
        // An empty id means the chapter is not known yet (no chapter metadata, or playback has
        // not published one). Treating that as "the chapter changed" would pause immediately, so
        // it holds instead — an end-of-chapter timer on a book with no chapters simply never
        // fires, which is honest, rather than stopping playback a second after being set.
        if (currentChapterId.isEmpty() || currentChapterId == mode.chapterId) {
          state to SleepTimerEffect.None
        } else {
          SleepTimerState.Expired(mode) to SleepTimerEffect.PauseAndExpire
        }
      }
    }
  }

  /**
   * The state after playback becomes active again.
   *
   * Re-arms an [SleepTimerState.Expired] timer to the duration it fired with — the acceptance
   * criterion for cu-21. Anything else is returned unchanged, so resuming a *running* timer does
   * not reset it and resuming with no timer does not invent one.
   *
   * A re-armed [SleepTimerMode.EndOfChapter] adopts the chapter playing **now**, not the one it
   * expired in: it already ended, so waiting for it again would never fire.
   */
  fun onPlaybackResumed(
    state: SleepTimerState,
    autoRestartEnabled: Boolean,
    currentChapterId: String,
  ): SleepTimerState {
    if (state !is SleepTimerState.Expired || !autoRestartEnabled) {
      return state
    }
    return when (val mode = state.mode) {
      // Back to the full duration the user picked, not the sliver left when it fired.
      is SleepTimerMode.FixedDuration ->
        SleepTimerState.Running(
          mode.copy(remainingMillis = mode.originalMillis),
        )
      is SleepTimerMode.EndOfChapter ->
        if (currentChapterId.isEmpty()) {
          state
        } else {
          SleepTimerState.Running(SleepTimerMode.EndOfChapter(currentChapterId))
        }
    }
  }

  /**
   * Extends a running fixed-duration timer.
   *
   * A no-op unless a fixed timer is **running**: extending while idle used to leave
   * `sleepTimeRemaining` positive with nothing armed, so the next tick counted down a timer the
   * user never set. Extending an end-of-chapter timer is also a no-op — it has no duration to add
   * to, and silently converting it to a countdown would discard what the user asked for.
   */
  fun extend(
    state: SleepTimerState,
    extensionMillis: Long,
  ): SleepTimerState {
    val mode = (state as? SleepTimerState.Running)?.mode
    if (mode !is SleepTimerMode.FixedDuration || extensionMillis <= 0L) {
      return state
    }
    // The extension raises the original too, so a re-arm after a shaken-awake night restores the
    // timer the user effectively ended up with rather than the one they first picked.
    return SleepTimerState.Running(
      mode.copy(
        remainingMillis = mode.remainingMillis + extensionMillis,
        originalMillis = mode.originalMillis + extensionMillis,
      ),
    )
  }

  /** The remaining time to show, or 0 when there is nothing to count down. */
  fun remainingMillis(state: SleepTimerState): Long {
    val mode =
      when (state) {
        is SleepTimerState.Running -> state.mode
        is SleepTimerState.Expired, SleepTimerState.Idle -> return 0L
      }
    return when (mode) {
      is SleepTimerMode.FixedDuration -> mode.remainingMillis
      is SleepTimerMode.EndOfChapter -> 0L
    }
  }

  /** Whether the timer is counting — what the UI's "active" indicator reflects. */
  fun isActive(state: SleepTimerState): Boolean = state is SleepTimerState.Running
}
