package io.github.mattpvaughn.chronicle.data.model

/**
 * Assembles a book's chapter list from per-track chapter data, filling gaps with the track itself.
 *
 * Extracted because both `BookRepository.syncAudiobook` and `ChapterRepository.loadChapterData`
 * did this inline, and both had the same defect: the per-track fallback was
 * `listOf(track.asChapter(0L))`, a literal zero for *every* track. Chapter offsets are absolute
 * within the book, so in a multi-file book where Plex returns no chapters, every chapter claimed to
 * start at 0 — and `getChapterAt` matches on a timestamp inside `startTimeOffset..endTimeOffset`,
 * so the wrong chapter (or none) resolves. Same class of bug as the one cu-13 fixed in
 * [asChapterList], in the path that runs when the server *does* answer for some tracks.
 *
 * @param tracks the book's tracks, in playback order.
 * @param chaptersForTrack chapters the source reported for one track, already mapped to [Chapter].
 *   Return an empty list when the source has none, and this falls back to one chapter for the whole
 *   track.
 */
inline fun assembleChapters(
  tracks: List<MediaItemTrack>,
  chaptersForTrack: (MediaItemTrack) -> List<Chapter>,
): List<Chapter> {
  val assembled = mutableListOf<Chapter>()
  var trackStartOffset = 0L
  for (track in tracks) {
    val reported = chaptersForTrack(track)
    if (reported.isEmpty()) {
      assembled.add(track.asChapter(trackStartOffset))
    } else {
      assembled.addAll(reported)
    }
    trackStartOffset += track.duration
  }
  return assembled.sorted()
}
