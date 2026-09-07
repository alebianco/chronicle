package io.github.mattpvaughn.chronicle.features.player

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback failures travelling service → UI, replacing `LocalBroadcastManager`.
 *
 * One direction only: `MediaPlayerService` and `AudiobookMediaSessionCallback` report, the activity
 * shows a message. Unlike the sleep timer this was never bidirectional, so the migration is a
 * transport swap and nothing more.
 *
 * The [diagnosis] is the raw cause-chain description, not user-facing text. That split is
 * deliberate and predates this change: the service knows the HTTP status or IO failure, the UI
 * knows which string resource explains it to a person, and the mapping lives with the strings.
 *
 * **Events, not state.** Replay is 0 so an activity returning to the foreground does not re-show a
 * stale failure the user has already seen and dismissed — which is what the STARTED-scoped
 * register/unregister pair achieved before. Two identical failures in a row must both arrive, so
 * this cannot be a `StateFlow`.
 */
@Singleton
class PlaybackErrorBus
  @Inject
  constructor() {
    private val _errors =
      MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
      )

    /** Raw playback-failure diagnoses. Collected by the activity while it is STARTED. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * Reports a failure.
     *
     * Non-suspending: both callers are on `Player.Listener` callbacks, which run on the main
     * looper and must not block. Dropping the oldest of a burst is right — a stalled stream can
     * raise several in a row and the user needs one message, not four.
     */
    fun report(diagnosis: String) {
      _errors.tryEmit(diagnosis)
    }
  }
