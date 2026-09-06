package io.github.mattpvaughn.chronicle.data.model

/**
 * A chapter list, grouped into disc sections where a book has more than one disc (cu-201).
 *
 * The grouping decision, with no view attached — extracted from `ChapterListAdapter` for the same
 * reason `Audiobook.progressState()` was extracted from `bindProgressIndicators` in cu-198: two
 * screens render this and they must not disagree about where a header goes.
 */
sealed interface ChapterRow {
  /** "Disc N" — emitted only for a multi-disc book. */
  data class DiscHeader(val discNumber: Int) : ChapterRow

  data class ChapterItem(
    val chapter: Chapter,
    val isActive: Boolean,
  ) : ChapterRow
}

/**
 * Groups [chapters] into rows.
 *
 * **Headers appear only when the last chapter's disc number is above 1.** That test is the
 * adapter's, kept verbatim: `discNumber` defaults to 1, so a single-disc book — most of this
 * library — would otherwise get a pointless "Disc 1" header above everything.
 *
 * [activeChapter] is compared on `trackId`, `discNumber` and `index` together rather than on `id`:
 * a chapter id is unique within a track, not within a book, so two tracks can carry the same
 * chapter id and matching on it alone highlights the wrong row.
 */
fun chapterRows(
  chapters: List<Chapter>,
  activeChapter: Chapter?,
): List<ChapterRow> {
  if (chapters.isEmpty()) return emptyList()

  fun isActive(chapter: Chapter): Boolean =
    activeChapter != null &&
      chapter.trackId == activeChapter.trackId &&
      chapter.discNumber == activeChapter.discNumber &&
      chapter.index == activeChapter.index

  if (chapters.last().discNumber <= 1) {
    return chapters.map { ChapterRow.ChapterItem(it, isActive(it)) }
  }

  val rows = mutableListOf<ChapterRow>()
  var lastDisc = 0
  chapters.forEach { chapter ->
    if (chapter.discNumber > lastDisc) {
      rows.add(ChapterRow.DiscHeader(chapter.discNumber))
      lastDisc = chapter.discNumber
    }
    rows.add(ChapterRow.ChapterItem(chapter, isActive(chapter)))
  }
  return rows
}
