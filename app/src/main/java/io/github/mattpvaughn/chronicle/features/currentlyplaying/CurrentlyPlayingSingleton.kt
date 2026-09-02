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
  val bookPosition: StateFlow<Long>

  fun setOnChapterChangeListener(listener: OnChapterChangeListener)

  fun update(
    track: MediaItemTrack,
    book: Audiobook,
    tracks: List<MediaItemTrack>,
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
  override val bookPosition = MutableStateFlow(0L)

  private var tracks: List<MediaItemTrack> = emptyList()
  private var chapters: List<Chapter> = emptyList()

  private var listener: OnChapterChangeListener? = null

  override fun setOnChapterChangeListener(listener: OnChapterChangeListener) {
    this.listener = listener
  }

  override fun update(
    track: MediaItemTrack,
    book: Audiobook,
    tracks: List<MediaItemTrack>,
  ) {
    this.book.value = book
    this.track.value = track

    this.tracks = tracks

    this.chapters =
      if (book.chapters.isNotEmpty()) {
        book.chapters
      } else {
        tracks.asChapterList()
      }

    // Set before the chapter lookup below, which uses the same derivation.
    this.bookPosition.value = tracks.getProgress()

    if (tracks.isNotEmpty() && chapters.isNotEmpty()) {
      // Falls back to the position derived from saved progress when the exact lookup misses.
      // getChapterAt matches on trackId *and* a timestamp inside the chapter's span, so it returns
      // EMPTY_CHAPTER whenever the two disagree — and EMPTY_CHAPTER used to be published, leaving
      // every consumer stale. That matters beyond display: PlayerExt drives skip-to-next-chapter
      // and skip-to-previous-chapter off this value, so a stale one skips to the wrong place
      // (cu-87).
      val chapter =
        chapters.getChapterAt(track.id, track.progress)
          .takeIf { it != EMPTY_CHAPTER }
          ?: chapters.chapterAtBookProgress(tracks.getProgress())
      if (this.chapter.value != chapter) {
        this.chapter.value = chapter
        listener?.onChapterChange(chapter)
      }
    }

    printDebug()
  }

  private fun printDebug() {
    Timber.i(
      "Currently Playing: track=${track.value.title}, index=${track.value.index}/${tracks.size}",
    )
  }
}
