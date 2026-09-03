package io.github.mattpvaughn.chronicle.features.player

import android.view.Gravity
import android.widget.Toast
import androidx.media3.common.Player
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.application.MILLIS_PER_SECOND
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.TrackIndex
import io.github.mattpvaughn.chronicle.data.model.TrackOffset
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import timber.log.Timber
import kotlin.math.abs

/**
 * Seek in the play queue by an offset of [durationMillis]. Positive [duration] seeks forwards,
 * negative [duration] seeks backwards
 */
fun Player.seekRelative(
  trackListStateManager: TrackListStateManager,
  durationMillis: Long,
) {
  // if seeking within the current track, no need to calculate seek
  if (durationMillis > 0 && (duration - currentPosition) > durationMillis) {
    Timber.i(
      "Seeking forwards within window: pos = $currentPosition, window duration = $duration, seek= $durationMillis",
    )
    seekTo(currentPosition + durationMillis)
  } else if (durationMillis < 0 && currentPosition > abs(durationMillis)) {
    Timber.i(
      "Seeking backwards within window: pos = $currentPosition, duration = $durationMillis",
    )
    seekTo(currentPosition + durationMillis)
  } else {
    Timber.i("Seeking via trackliststatemanager")
    trackListStateManager.updatePosition(currentMediaItemIndex, currentPosition)
    trackListStateManager.seekByRelative(durationMillis)
    seekTo(
      trackListStateManager.currentTrackIndex,
      trackListStateManager.currentTrackProgress,
    )
  }
}

/**
 * Seek to [target], the only shape `Player.seekTo(mediaItemIndex, positionMs)` actually accepts.
 *
 * Taking the typed target rather than two `Int`/`Long` arguments is the point of cu-136: the
 * index is into the *sorted* playlist and the offset is *within that track*, and both were
 * previously plain numbers that four separate bugs got the wrong way round.
 */
fun Player.seekTo(
  trackIndex: TrackIndex,
  inTrackOffset: TrackOffset,
) = seekTo(trackIndex.value, inTrackOffset.millis)

private fun Player.seekTo(
  target: ChapterSeekTarget,
  nudgeMillis: Long = 0L,
) = seekTo(target.trackIndex, target.inTrackOffset + nudgeMillis)

/** Skip to next chapter */
fun Player.skipToNext(
  trackListStateManager: TrackListStateManager,
  currentlyPlaying: CurrentlyPlaying,
  progressUpdater: ProgressUpdater,
) {
  Timber.i("Player.skipToNext called")
  val currentChapterIndex =
    currentlyPlaying.book.value.chapters.indexOf(
      currentlyPlaying.chapter.value,
    )
  val nextChapterIndex = currentChapterIndex + 1
  if (nextChapterIndex < currentlyPlaying.book.value.chapters.size) {
    val nextChapter = currentlyPlaying.book.value.chapters[nextChapterIndex]
    Timber.d(
      "NEXT CHAPTER: index=$nextChapterIndex id=${nextChapter.id} trackId=${nextChapter.trackId} offset=${nextChapter.bookStartTimeOffset} title=${nextChapter.title}",
    )
    // `seekTo` takes an in-track offset; the chapter's is book-absolute (cu-96).
    val target = chapterSeekTarget(nextChapter, trackListStateManager.trackList)
    if (target == null) {
      Timber.e("Chapter ${nextChapter.id} names track ${nextChapter.trackId}, which is not loaded")
      return
    }
    // The 300ms nudge keeps the seek inside the new chapter rather than on its boundary, where
    // `getChapterAt` could still resolve the previous one.
    seekTo(target, nudgeMillis = CHAPTER_SEEK_NUDGE_MILLIS)
    progressUpdater.updateProgressWithoutParameters()
  } else {
    val toast =
      Toast.makeText(
        Injector.get().applicationContext(),
        R.string.skip_forwards_reached_last_chapter,
        Toast.LENGTH_LONG,
      )
    toast.setGravity(Gravity.BOTTOM, 0, 200)
    toast.show()
  }
}

/** Skip to previous chapter */
fun Player.skipToPrevious(
  trackListStateManager: TrackListStateManager,
  currentlyPlaying: CurrentlyPlaying,
  progressUpdater: ProgressUpdater,
) {
  Timber.i("Player.skipToPrevious called")
  val currentChapterIndex =
    currentlyPlaying.book.value.chapters.indexOf(
      currentlyPlaying.chapter.value,
    )
  // Both operands must be book-absolute. This used to subtract a book-absolute chapter start from
  // `currentPosition`, which is *in-track* — on a multi-track book that yields a large negative,
  // so the branch always chose "previous chapter" and never "restart this one" (cu-96).
  // Sorted, because `currentMediaItemIndex` addresses the player's playlist and that is built in
  // sorted order — the same `TrackIndex` distinction `chapterSeekTarget` documents. The unsorted
  // read was safe only by its caller's grace (cu-136).
  val bookPosition =
    BookOffset(
      trackListStateManager.trackList
        .sorted()
        .take(currentMediaItemIndex)
        .sumOf { it.duration } + currentPosition,
    )
  var previousChapterIndex: Int =
    if (millisIntoChapter(currentlyPlaying.chapter.value, bookPosition) <
      (SKIP_TO_PREVIOUS_CHAPTER_THRESHOLD_SECONDS * MILLIS_PER_SECOND)
    ) {
      Timber.d("skipToPrevious → skip to previous chapter")
      currentChapterIndex - 1
    } else {
      Timber.d("skipToPrevious → back to start of current chapter")
      currentChapterIndex
    }
  if (previousChapterIndex < 0) previousChapterIndex = 0
  val previousChapter = currentlyPlaying.book.value.chapters[previousChapterIndex]
  Timber.d(
    "PREVIOUS CHAPTER: index=$previousChapterIndex id=${previousChapter.id} trackId=${previousChapter.trackId} offset=${previousChapter.bookStartTimeOffset} title=${previousChapter.title}",
  )
  val target = chapterSeekTarget(previousChapter, trackListStateManager.trackList)
  if (target == null) {
    Timber.e("Chapter ${previousChapter.id} names track ${previousChapter.trackId}, not loaded")
    return
  }
  seekTo(target)
  progressUpdater.updateProgressWithoutParameters()
}
