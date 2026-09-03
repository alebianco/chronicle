package io.github.mattpvaughn.chronicle.data.model

import androidx.room.TypeConverter

/**
 * Millisecond offsets, with the frame they are measured from carried in the type.
 *
 * Six bugs came from the same mistake before these existed: a value measured from the start of a
 * **track** used where one measured from the start of the **book** belongs, or the reverse
 * (cu-13, cu-49, cu-93, cu-96, and four more found in the 2026-09-02 review). Both are `Long`, so
 * the compiler could not help — and on a single-track book, which is most of this library, the
 * two are the *same number*, so every one of them worked by accident until a multi-track book
 * appeared.
 *
 * Prose did not fix it. `Chapter.bookStartTimeOffset` was renamed to say the frame outright and
 * carries a KDoc explaining it; the frame was then still guessed wrong twice more. So the
 * distinction is a type, and a mix-up fails to compile.
 *
 * `@JvmInline` means no allocation and no boxing in the common path, which matters because these
 * are read on the 1 Hz progress tick.
 *
 * ## Converting between them
 *
 * Never by hand. `chapterSeekTarget` is the one home for book → track, because the conversion
 * needs the *sorted* track list and a track's own start; the two places that inlined
 * `offset - trackStart` instead are precisely where cu-115 found bugs.
 */
@JvmInline
value class BookOffset(val millis: Long) : Comparable<BookOffset> {
  override fun compareTo(other: BookOffset): Int = millis.compareTo(other.millis)

  operator fun minus(other: BookOffset): Long = millis - other.millis

  operator fun plus(deltaMillis: Long): BookOffset = BookOffset(millis + deltaMillis)

  companion object {
    val ZERO = BookOffset(0L)
  }
}

/**
 * Milliseconds from the start of a **track**. See [BookOffset] for why this is a type.
 *
 * This is what `Player.seekTo(mediaItemIndex, positionMs)` takes for its position, and what
 * `MediaItemTrack.progress` stores.
 */
@JvmInline
value class TrackOffset(val millis: Long) : Comparable<TrackOffset> {
  override fun compareTo(other: TrackOffset): Int = millis.compareTo(other.millis)

  operator fun minus(other: TrackOffset): Long = millis - other.millis

  operator fun plus(deltaMillis: Long): TrackOffset = TrackOffset(millis + deltaMillis)

  /** Clamped at zero: a negative seek position throws, and starting the track over is safe. */
  fun coerceAtLeastZero(): TrackOffset = if (millis < 0L) TrackOffset(0L) else this

  companion object {
    val ZERO = TrackOffset(0L)
  }
}

/**
 * An index into the **sorted** track list, which is what `Player.seekTo` means by
 * `mediaItemIndex`.
 *
 * "Index into what" is the untyped distinction that makes `TrackListStateManager` safe only by
 * its callers' grace: `getActiveTrack()` sorts internally, and the result was then looked up in
 * the *unsorted* list. Both callers happen to pass a DAO-ordered list, so the indices agree by
 * convention rather than by construction — the same shape as the bug cu-115 fixed, one caller
 * away from biting.
 */
@JvmInline
value class TrackIndex(val value: Int) {
  companion object {
    /** No track resolved. Callers must not seek — guessing an index seeks somewhere arbitrary. */
    val NONE = TrackIndex(-1)
  }

  val isResolved: Boolean get() = value >= 0
}

/**
 * Room stores these as the plain `INTEGER` columns they always were, so no migration is needed —
 * verified by diffing the exported schema before and after the retype.
 */
class OffsetConverters {
  @TypeConverter
  fun toBookOffset(millis: Long): BookOffset = BookOffset(millis)

  @TypeConverter
  fun fromBookOffset(offset: BookOffset): Long = offset.millis

  @TypeConverter
  fun toTrackOffset(millis: Long): TrackOffset = TrackOffset(millis)

  @TypeConverter
  fun fromTrackOffset(offset: TrackOffset): Long = offset.millis
}
