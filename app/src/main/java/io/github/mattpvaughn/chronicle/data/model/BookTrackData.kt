package io.github.mattpvaughn.chronicle.data.model

/**
 * The track-derived facts cached on an [Audiobook]: its position, its length and how many tracks
 * it has.
 *
 * These are a *derivation* of the book's tracks, never independent state ([decision-16] — the
 * per-track `viewOffset` is the source of truth). Grouping them into one type exists so a whole
 * library's worth can be written in a single transaction rather than one statement per book.
 */
data class BookTrackData(
  val bookId: String,
  val bookProgress: Long,
  val bookDuration: Long,
  val trackCount: Int,
)
