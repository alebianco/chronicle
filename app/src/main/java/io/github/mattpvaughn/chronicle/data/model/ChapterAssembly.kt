package io.github.mattpvaughn.chronicle.data.model

/**
 * Assembles a book's chapter list from per-track chapter data, filling gaps with the track itself.
 *
 * Extracted because both `BookRepository.syncAudiobook` and `ChapterRepository.loadChapterData`
 * did this inline, and both had the same defect: the per-track fallback was
 * `listOf(track.asChapter(0L))`, a literal zero for *every* track. Chapter offsets are absolute
 * within the book, so in a multi-file book where Plex returns no chapters, every chapter claimed to
 * start at 0 — and `getChapterAt` matches on a timestamp inside `bookStartTimeOffset..bookEndTimeOffset`,
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
  var trackStartOffset = BookOffset.ZERO
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

/**
 * Picks the best available chapter list, table first (cu-82).
 *
 * Three levels, in descending order of trust:
 *  1. `ChapterDatabase` rows — the source of truth since cu-49, and the only one a future backend
 *     has to populate.
 *  2. [Audiobook.chapters] — the legacy column. Still consulted because the backfill (cu-158) is
 *     *launched, not awaited* (`ChronicleApplication.backfillChapterTable`), so a book can still
 *     have no rows on the first launch after an upgrade. Retiring this level needs a released build
 *     that has run the backfill, which is why cu-82 does not drop the column.
 *  3. [asChapterList] — cu-13's no-chapter-data fallback, which is **permanent**: a book whose
 *     server reports no chapters has nothing to fall back *to*.
 *
 * Kept pure and free of Room types so the precedence is testable without a database.
 */
fun resolveChapters(
  fromTable: List<Chapter>,
  fromBook: List<Chapter>,
  tracks: List<MediaItemTrack>,
): List<Chapter> =
  when {
    fromTable.isNotEmpty() -> fromTable
    fromBook.isNotEmpty() -> fromBook
    else -> tracks.asChapterList()
  }
