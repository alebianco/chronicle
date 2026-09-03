package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Singleton

/**
 * A global store of state containing information on the [Audiobook]/[MediaItemTrack]/[Chapter]
 * currently playing and the relevant playback information.
 */
@ExperimentalCoroutinesApi
interface CurrentlyPlaying {
  val book: StateFlow<Audiobook>
  val track: StateFlow<MediaItemTrack>
  val chapter: StateFlow<Chapter>

  /**
   * The listening position **as an offset from the start of the book**, in millis.
   *
   * Published here rather than left to each consumer to derive, because deriving it needs the
   * track list — which is not exposed — and every consumer that tried got it wrong. A
   * `MediaItemTrack.progress` is an offset within *its own track*; subtracting a
   * `Chapter.bookStartTimeOffset` from it mixes the two frames and, on a multi-track book, yields
   * a large negative number (cu-115). On a single-track book the two are the same value, which is
   * why it went unnoticed for so long.
   */
  val bookPosition: StateFlow<BookOffset>

  fun setOnChapterChangeListener(listener: OnChapterChangeListener)

  fun update(
    track: MediaItemTrack,
    book: Audiobook,
    tracks: List<MediaItemTrack>,
  )

  /**
   * Republishes the current book with a new per-book speed override (cu-20).
   *
   * Narrow on purpose. The override is written to the DB by the popover, and `ProgressUpdater`
   * would eventually re-read the book and republish it — but its tick is gated on `isPlaying`, so
   * a change made **while paused** would not reach the player until playback resumed. Coupling a
   * setting's propagation to a progress tick is incidental anyway; this makes it explicit.
   *
   * A no-op when [bookId] is not the book currently loaded, so a stale popover cannot overwrite
   * the state of a book that has since changed.
   */
  fun updateSpeedOverride(
    bookId: String,
    speed: Float,
  )
}

interface OnChapterChangeListener {
  fun onChapterChange(chapter: Chapter)
}

/**
 * Implementation of [CurrentlyPlaying]. Values default to placeholder values until data is
 * made available (the user
 */
@ExperimentalCoroutinesApi
@Singleton
class CurrentlyPlayingSingleton : CurrentlyPlaying {
  override val book = MutableStateFlow(EMPTY_AUDIOBOOK)
  override val track = MutableStateFlow(EMPTY_TRACK)
  override val chapter = MutableStateFlow(EMPTY_CHAPTER)
  override val bookPosition = MutableStateFlow(BookOffset.ZERO)

  private var tracks: List<MediaItemTrack> = emptyList()

  /**
   * The tracks' identity and timing, which is all the chapter list depends on — deliberately
   * *not* their progress, which changes every second and would defeat the comparison.
   */
  private var trackShape: List<Pair<String, Long>> = emptyList()
  private var chapters: List<Chapter> = emptyList()

  private var listener: OnChapterChangeListener? = null

  override fun setOnChapterChangeListener(listener: OnChapterChangeListener) {
    this.listener = listener
  }

  override fun updateSpeedOverride(
    bookId: String,
    speed: Float,
  ) {
    val current = book.value
    if (current.id != bookId) {
      Timber.i("Ignoring speed override for $bookId; ${current.id} is loaded")
      return
    }
    if (current.playbackSpeed != speed) {
      book.value = current.copy(playbackSpeed = speed)
    }
  }

  override fun update(
    track: MediaItemTrack,
    book: Audiobook,
    tracks: List<MediaItemTrack>,
  ) {
    // Assign only on change. `ProgressUpdater` calls this **once a second** during playback, and a
    // `StateFlow` write fans out to every collector even when the value is identical — which is
    // most ticks, since the book and the track change rarely and only the position moves. cu-110
    // fixed this shape one layer up (per-tick Room invalidation); this is the same fix here.
    //
    // Measured on the 107-track fixture before the guard: 277 main-thread jiffies / 10 s against
    // **1** while paused, 100% janky frames, and `uiautomator dump` failing with "could not get
    // idle state" — the saturation switching entirely with playback state.
    if (this.book.value != book) {
      this.book.value = book
    }
    if (this.track.value != track) {
      this.track.value = track
    }

    // The chapter list depends on the tracks' *identity and timing*, never on their progress —
    // but `ProgressUpdater` writes the playing track's progress to Room every second, so the list
    // re-read on the next tick is a genuinely different value. Comparing the lists therefore says
    // "changed" every tick and guards nothing; the comparison has to ignore the field that is
    // *meant* to change.
    //
    // `asChapterList()` walks every track (107 in the live fixture), so doing it per tick is pure
    // waste.
    val trackShape = tracks.map { it.id to it.duration }
    val shapeChanged = this.trackShape != trackShape
    this.tracks = tracks
    if (shapeChanged || (this.chapters.isEmpty() && book.chapters.isNotEmpty())) {
      this.trackShape = trackShape
      this.chapters =
        if (book.chapters.isNotEmpty()) {
          book.chapters
        } else {
          tracks.asChapterList()
        }
    }

    // Set before the chapter lookup below, which uses the same derivation. This one *does* change
    // every tick — it is the position — so it is written unconditionally, and `StateFlow` already
    // suppresses a re-emission when the value is equal.
    val bookPosition = tracks.getProgress()
    this.bookPosition.value = bookPosition

    if (tracks.isNotEmpty() && chapters.isNotEmpty()) {
      // One lookup, by book position. This was two: `getChapterAt(track.id, track.progress)`
      // first, falling back to `chapterAtBookProgress` when it returned EMPTY_CHAPTER — the
      // fallback added by cu-87 because publishing EMPTY_CHAPTER left every consumer stale, and
      // that matters beyond display since `PlayerExt` drives skip-to-next/previous-chapter off it.
      //
      // The first lookup was **passing a track offset where `getChapterAt` wants a book one**
      // (cu-136 — the retype is what surfaced it). So on any multi-track book it matched nothing
      // and the fallback did all the work; on a single-track book the two frames are the same
      // number and it happened to work. Fixed, it would be the fallback plus a redundant track-id
      // filter over the same position, so the two collapse into the one that was always correct.
      val chapter = chapters.chapterAtBookProgress(bookPosition)
      if (this.chapter.value != chapter) {
        this.chapter.value = chapter
        listener?.onChapterChange(chapter)
      }
    }

    printDebug(shapeChanged)
  }

  /**
   * Logs only when something actually changed.
   *
   * This used to run every tick, putting a `Timber.i` — string interpolation and a logcat write —
   * on the main thread once a second for the whole of playback, for a line that repeats itself.
   */
  private fun printDebug(changed: Boolean) {
    if (!changed) {
      return
    }
    Timber.i(
      "Currently Playing: track=${track.value.title}, index=${track.value.index}/${tracks.size}",
    )
  }
}
