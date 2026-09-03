package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.TrackIndex
import io.github.mattpvaughn.chronicle.data.model.TrackOffset
import io.github.mattpvaughn.chronicle.data.model.getActiveTrack
import io.github.mattpvaughn.chronicle.data.model.getTrackStartTime
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max

/**
 * Shadows the state of tracks in the queue in order to calculate seeks for
 * [AudiobookMediaSessionCallback] with information that exoplayer's window management doesn't
 * have (access to track durations outside of current track)
 */
class TrackListStateManager {
  /** The list of [MediaItemTrack]s currently playing */
  var trackList: List<MediaItemTrack> = emptyList()
    set(value) {
      field = value
      sortedTracks = value.sorted()
    }

  /**
   * [trackList] in the order the player's playlist uses. See [currentTrackIndex].
   *
   * Sorted once on assignment rather than on each read: [currentBookPosition] and [currentTrack]
   * are read on the 1 Hz progress path, and sorting there would be exactly the per-tick work whose
   * result cannot change that cu-110 was about.
   */
  private var sortedTracks: List<MediaItemTrack> = emptyList()

  /**
   * The index of the current track within the **sorted** [trackList].
   *
   * Sorted, because this feeds `Player.seekTo`'s `mediaItemIndex`, which addresses the player's
   * playlist — and that playlist is built in sorted order. Both callers happen to assign a
   * DAO-ordered list, so the two agreed by convention rather than by construction; [TrackIndex]
   * plus the explicit sort in [seekToActiveTrack] makes it hold by construction (cu-136).
   */
  var currentTrackIndex: TrackIndex = TrackIndex(0)
    private set

  /** The offset from the start of the currently playing track. */
  var currentTrackProgress: TrackOffset = TrackOffset.ZERO
    private set

  private val currentTrack: MediaItemTrack
    get() = sortedTracks[currentTrackIndex.value]

  /**
   * The position measured from the start of the **book** — the preceding tracks' durations plus
   * [currentTrackProgress]. Not authoritative, since [MediaItemTrack.duration] is not necessarily
   * correct.
   *
   * **Has no production caller any more.** Its one reader was `seekRelative`'s service-is-dead
   * branch, which wrote it into `MediaItemTrack.progress` — a *track* column — inflating the row
   * by every preceding track's duration (cu-136). Kept rather than deleted because the two frames
   * being available side by side is what `TrackListStateManagerFrameTest` pins, and a future
   * caller wanting a book position should find one here instead of deriving it inline for a
   * seventh time.
   */
  val currentBookPosition: BookOffset
    get() = BookOffset(sortedTracks.getTrackStartTime(currentTrack) + currentTrackProgress.millis)

  /**
   * Update [currentTrackIndex] to [activeTrackIndex] and [currentTrackProgress] to
   * [offsetFromTrackStart]
   */
  fun updatePosition(
    activeTrackIndex: Int,
    offsetFromTrackStart: Long,
  ) {
    if (activeTrackIndex >= trackList.size) {
      throw IndexOutOfBoundsException(
        "Cannot set current track index = $activeTrackIndex if tracklist.size == ${trackList.size}",
      )
    }
    currentTrackIndex = TrackIndex(activeTrackIndex)
    currentTrackProgress = TrackOffset(offsetFromTrackStart)
  }

  /**
   * Update position based on tracks in [trackList], picking the one with the most recent
   * [MediaItemTrack.lastViewedAt]
   */
  fun seekToActiveTrack() {
    Timber.i("Seeking to active track")
    // `getActiveTrack()` sorts internally, so the index must come from the sorted list too. It
    // used to come from the unsorted `trackList`, which agreed only because both callers pass a
    // DAO-ordered list — one caller away from seeking to the wrong track (cu-136).
    val ordered = sortedTracks
    val activeTrack = ordered.getActiveTrack()
    currentTrackIndex = TrackIndex(ordered.indexOf(activeTrack))
    currentTrackProgress = TrackOffset(activeTrack.progress)
  }

  /** Seeks forwards or backwards in the playlist by [offsetMillis] millis*/
  fun seekByRelative(offsetMillis: Long) {
    if (offsetMillis >= 0) {
      seekForwards(offsetMillis)
    } else {
      seekBackwards(abs(offsetMillis))
    }
  }

  /** Seek backwards by [offset] ms. [offset] must be a positive [Long] */
  private fun seekBackwards(offset: Long) {
    check(offset >= 0) { "Attempted to seek by a negative number: $offset" }
    val ordered = sortedTracks
    var offsetRemaining =
      offset + (ordered[currentTrackIndex.value].duration - currentTrackProgress.millis)
    for (index in currentTrackIndex.value downTo 0) {
      if (offsetRemaining < ordered[index].duration) {
        currentTrackProgress = TrackOffset(max(0, ordered[index].duration - offsetRemaining))
        currentTrackIndex = TrackIndex(index)
        return
      } else {
        offsetRemaining -= ordered[index].duration
      }
    }
    currentTrackIndex = TrackIndex(0)
    currentTrackProgress = TrackOffset.ZERO
  }

  private fun seekForwards(offset: Long) {
    check(offset >= 0) { "Attempted to seek by a negative number: $offset" }
    val ordered = sortedTracks
    var offsetRemaining = offset + currentTrackProgress.millis
    for (index in currentTrackIndex.value until ordered.size) {
      if (offsetRemaining < ordered[index].duration) {
        currentTrackIndex = TrackIndex(index)
        currentTrackProgress = TrackOffset(offsetRemaining)
        return
      } else {
        offsetRemaining -= ordered[index].duration
      }
    }
    currentTrackIndex = TrackIndex(ordered.size - 1)
    currentTrackProgress = TrackOffset(ordered.lastOrNull()?.duration ?: 0L)
  }
}
