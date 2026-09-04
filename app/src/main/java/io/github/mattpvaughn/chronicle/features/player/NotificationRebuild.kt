package io.github.mattpvaughn.chronicle.features.player

/**
 * Everything the now-playing notification actually renders (cu-157).
 *
 * Two of these being equal means a rebuild would produce a byte-identical notification, so the
 * build — five actions, a `MediaStyle`, an icon lookup and an artwork lookup — can be skipped.
 *
 * **The playback state is part of the key on purpose.** A pause changes no title, but the
 * notification's action buttons and its foreground status both follow the state, so a key of
 * titles alone would leave a pause button that no longer matches what the player is doing. The
 * dead `NotificationData` that cu-50 removed carried a `playbackState` field for the same reason;
 * this is that idea finished.
 */
data class NotificationContentKey(
  val bookId: String,
  val trackId: String,
  val chapterId: String,
  val playbackState: Int,
)

/**
 * Remembers the last notification content posted, so an identical one is not rebuilt (cu-157).
 *
 * **Stateful "same as last time", never a set of seen keys.** Playback legitimately returns to a
 * previous state — pause then resume — and a set would swallow the second one, leaving a paused
 * notification while audio played. A test pins that.
 *
 * Not thread-safe, and does not need to be: every caller reaches it from the same service scope.
 */
class NotificationRebuildTracker {
  private var last: NotificationContentKey? = null

  /**
   * Whether [key] differs from the last one posted; records it when it does.
   *
   * Returns true the first time, so nothing suppresses the initial notification.
   */
  fun shouldRebuild(key: NotificationContentKey): Boolean {
    if (key == last) {
      return false
    }
    last = key
    return true
  }

  /** Forgets the last key, so the next post always rebuilds. */
  fun reset() {
    last = null
  }
}
