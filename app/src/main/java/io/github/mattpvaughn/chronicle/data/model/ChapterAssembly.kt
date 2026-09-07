package io.github.mattpvaughn.chronicle.data.model

/**
 * Assembles a book's chapter list from per-track chapter data, filling gaps with the track itself.
 *
 * Extracted because both `BookRepository.syncAudiobook` and `ChapterRepository.loadChapterData`
 * did this inline, and both had the same defect: the per-track fallback was
 * `listOf(track.asChapter(0L))`, a literal zero for *every* track. Chapter offsets are absolute
 * within the book, so in a multi-file book where Plex returns no chapters, every chapter claimed to
 * start at 0 — and `getChapterAt` matches on a timestamp inside `bookStartTimeOffset..bookEndTimeOffset`,
 * so the wrong chapter (or none) resolves. Same class of bug as the one fixed in
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
  // A chapter that spans a track boundary is reported by **both** tracks, so concatenating
  // the per-track lists listed it twice — visibly in the chapter list, and in every `Ch n of m`
  // readout, since the `m` is a size. Seen on the fixture book, where chapter 4003 arrives from
  // 2001 and again from 2002.
  //
  // The chapter is kept for the track it **starts** in, which is the rule `trackId` already
  // follows and the frame `bookStartTimeOffset` is measured in — so the first report wins and the
  // later duplicate is dropped. Identity is the id **and** the book offsets together: `id` alone
  // is not unique within a book, and dropping on it would take a real chapter with it.
  val seen = mutableSetOf<Triple<String, Long, Long>>()
  var trackStartOffset = BookOffset.ZERO
  for (track in tracks) {
    val reported = chaptersForTrack(track)
    if (reported.isEmpty()) {
      assembled.add(track.asChapter(trackStartOffset))
    } else {
      for (chapter in reported) {
        val identity =
          Triple(
            chapter.id,
            chapter.bookStartTimeOffset.millis,
            chapter.bookEndTimeOffset.millis,
          )
        if (seen.add(identity)) assembled.add(chapter)
      }
    }
    trackStartOffset += track.duration
  }
  return assembled.sorted()
}

/**
 * Picks the best available chapter list, table first.
 *
 * Two levels since the legacy `Audiobook.chapters` column was dropped:
 *  1. `ChapterDatabase` rows — the source of truth, and the only one a future backend
 *     has to populate. `syncAudiobook` refetches them from Plex whenever a book is opened, so a
 *     book with no rows repairs itself.
 *  2. [asChapterList] — the no-chapter-data fallback, which is **permanent**: a book whose
 *     server reports no chapters has nothing to fall back *to*, and this derives one chapter per
 *     track instead.
 *
 * Kept pure and free of Room types so the precedence is testable without a database.
 */
fun resolveChapters(
  fromTable: List<Chapter>,
  tracks: List<MediaItemTrack>,
): List<Chapter> = if (fromTable.isNotEmpty()) fromTable else tracks.asChapterList()

/**
 * [resolveChapters] over the nullable values a flow combine hands out.
 *
 * The three ViewModels that combine a book with its tracks share this exact shape. A null source
 * has simply not emitted yet, so it is treated as "nothing from that level" — the resolution then
 * falls through to the next one, and re-runs when the source arrives.
 */
fun resolveChaptersFromCache(
  fromTable: List<Chapter>?,
  tracksAsChapters: List<Chapter>?,
): List<Chapter> = if (!fromTable.isNullOrEmpty()) fromTable else tracksAsChapters ?: emptyList()
