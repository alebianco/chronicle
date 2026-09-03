package io.github.mattpvaughn.chronicle.testing

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.TrackOffset

/**
 * A book made of **several** track files, with chapters that cross track boundaries (cu-115).
 *
 * Every other fixture in this suite is a single-track book, and on a single-track book the two
 * coordinate frames coincide: an offset measured from the start of the *track* equals the same
 * offset measured from the start of the *book*. So arithmetic that confuses the two is correct by
 * accident, and the suite cannot see the difference.
 *
 * That is not a hypothetical gap. `Chapter.bookStartTimeOffset`'s own KDoc lists four separate
 * occasions the frame was guessed wrong (cu-13, cu-49, cu-93, cu-96), and the 2026-09-02 review
 * found more still live. cu-93 and cu-96 both carry "reproduce with a multi-track book" as an
 * *open* acceptance criterion. This fixture is what closes them.
 *
 * ### The shape, and why these numbers
 *
 * Three tracks of 10 minutes each, six chapters of 5 minutes each:
 *
 * ```
 * book ms:   0        300k      600k      900k      1200k     1500k     1800k
 *            |---------|---------|---------|---------|---------|---------|
 * chapters:  ch1       ch2       ch3       ch4       ch5       ch6
 * tracks:    |-------- t1 -------|-------- t2 -------|-------- t3 -------|
 * ```
 *
 * The numbers are chosen so a frame confusion cannot pass unnoticed:
 * - Chapter boundaries at 300k, 900k and 1500k fall **inside** a track, not on its edge, so
 *   `chapterAt` must combine track index and in-track offset rather than either alone.
 * - Chapter boundaries at 600k and 1200k fall **exactly** on a track boundary, which is the
 *   off-by-one case.
 * - Every track has the same duration, so a wrong `getTrackStartTime` produces a *plausible*
 *   number (a multiple of 600k) rather than an obviously broken one — the failure mode that let
 *   these bugs survive.
 *
 * Position 750_000 (used by [midBookTracks]) is deliberately awkward: it is 2m30s into **track 2**
 * and inside **chapter 3**, so the in-track offset (150_000) and the book offset (750_000) differ
 * by more than any track duration. Any site that passes one where the other belongs is off by
 * 600_000 ms, which no assertion can round away.
 */
object MultiTrackBook {
  const val BOOK_ID = "1001"

  const val TRACK_DURATION = 600_000L
  const val CHAPTER_DURATION = 300_000L
  const val TRACK_COUNT = 3
  const val BOOK_DURATION = TRACK_DURATION * TRACK_COUNT

  /**
   * 2m30s into track 2 — mid-track *and* mid-chapter, in both frames at once.
   *
   * Kept as raw millis alongside the typed forms below so a test can assert against a literal;
   * [MID_BOOK_OFFSET] and [MID_TRACK_POSITION] are what the typed APIs take (cu-136).
   */
  const val MID_BOOK_POSITION = 750_000L

  /** The same instant expressed in the *other* frame: 150_000 into track 2. */
  const val MID_TRACK_OFFSET = 150_000L

  /** [MID_BOOK_POSITION] in the book frame. */
  val MID_BOOK_OFFSET = BookOffset(MID_BOOK_POSITION)

  /** [MID_TRACK_OFFSET] in the track frame. */
  val MID_TRACK_POSITION = TrackOffset(MID_TRACK_OFFSET)

  /** The track that [MID_BOOK_POSITION] falls in. */
  const val MID_TRACK_ID = "2002"

  /** The chapter that [MID_BOOK_POSITION] falls in (ch3: 600_000..900_000). */
  const val MID_CHAPTER_ID = "3"

  fun tracks(): List<MediaItemTrack> =
    (1..TRACK_COUNT).map { i ->
      MediaItemTrack(
        id = "200$i",
        parentKey = BOOK_ID,
        title = "Part $i",
        index = i,
        discNumber = 1,
        duration = TRACK_DURATION,
        media = "/library/parts/200$i/file.mp3",
        album = "The Long Book",
        artist = "A Narrator",
      )
    }

  /**
   * The tracks with the listener at [MID_BOOK_POSITION].
   *
   * Note what "position" means here per decision-16: track 2 carries an **in-track** offset, and
   * track 1 is played through with `progress = 0`. A reader that sums progress, or that takes the
   * most recently touched track, gets a different (wrong) answer than one that takes the furthest
   * started track and adds the durations before it.
   */
  fun midBookTracks(): List<MediaItemTrack> =
    tracks().map { track ->
      when (track.id) {
        "2001" -> track.copy(progress = 0L, lastViewedAt = 1_000L, viewCount = 1L)
        MID_TRACK_ID -> track.copy(progress = MID_TRACK_OFFSET, lastViewedAt = 2_000L)
        else -> track
      }
    }

  /**
   * Six chapters, each 5 minutes, with offsets **absolute within the book**.
   *
   * `trackId` is assigned by which track the chapter *starts* in, which is how `assembleChapters`
   * does it — chapter 2 starts in track 1 and runs into track 2, which is exactly the case a
   * per-track offset gets wrong.
   */
  fun chapters(): List<Chapter> =
    (1..6).map { i ->
      val start = (i - 1) * CHAPTER_DURATION
      Chapter(
        id = i.toString(),
        bookId = BOOK_ID,
        trackId = "200${(start / TRACK_DURATION).toInt() + 1}",
        title = "Chapter $i",
        index = i.toLong(),
        discNumber = 1,
        bookStartTimeOffset = BookOffset(start),
        bookEndTimeOffset = BookOffset(start + CHAPTER_DURATION),
      )
    }

  fun book(): Audiobook =
    Audiobook(
      id = BOOK_ID,
      source = 1L,
      title = "The Long Book",
      titleSort = "Long Book, The",
      author = "A Narrator",
      duration = BOOK_DURATION,
      leafCount = TRACK_COUNT.toLong(),
      chapters = chapters(),
    )
}
