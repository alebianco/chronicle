package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fallback that turns a multi-file book into a chapter list.
 *
 * Plex audiobooks usually carry embedded chapter data, fetched via `retrieveChapterInfo`. When a
 * book has none, every consumer falls back to one chapter per file — `CurrentlyPlayingSingleton`,
 * `CurrentlyPlayingViewModel`, `AudiobookDetailsViewModel` and `MainActivityViewModel` all do
 * exactly `if (book.chapters.isNotEmpty()) book.chapters else tracks.asChapterList()`.
 *
 * That fallback returned an **empty list** for every book: the loop built each chapter and
 * discarded it, never appending to the list it returned. So a book without embedded chapters
 * showed no chapters at all, in the player and in book details, rather than one per file.
 *
 * The offsets are the other half. Chapter positions are cumulative across the whole book, so
 * `bookEndTimeOffset` has to be the running total — a per-track duration means every chapter after
 * the first reports an end before its own start, and `getChapterAt` then matches nothing.
 */
class AsChapterListTest {
  private fun track(
    id: String,
    index: Int,
    duration: Long,
  ) = MediaItemTrack(id = id, index = index, duration = duration, title = "Track $index")

  @Test
  fun `a chapter is produced for every track`() {
    val chapters =
      listOf(
        track("1", 1, 1_000L),
        track("2", 2, 2_000L),
        track("3", 3, 3_000L),
      ).asChapterList()

    assertEquals("one chapter per file is the whole point of the fallback", 3, chapters.size)
  }

  @Test
  fun `chapter offsets accumulate across tracks`() {
    val chapters =
      listOf(
        track("1", 1, 1_000L),
        track("2", 2, 2_000L),
        track("3", 3, 3_000L),
      ).asChapterList()

    assertEquals(listOf(0L, 1_000L, 3_000L), chapters.map { it.bookStartTimeOffset })
    assertEquals(listOf(1_000L, 3_000L, 6_000L), chapters.map { it.bookEndTimeOffset })
  }

  /**
   * Each chapter must span its own track: end minus start is that file's duration. This is what
   * breaks if `bookEndTimeOffset` is set to the raw duration instead of the running total.
   */
  @Test
  fun `each chapter spans exactly its own track duration`() {
    val tracks =
      listOf(
        track("1", 1, 1_000L),
        track("2", 2, 2_000L),
        track("3", 3, 3_000L),
      )

    val spans = tracks.asChapterList().map { it.bookEndTimeOffset - it.bookStartTimeOffset }

    assertEquals(tracks.map { it.duration }, spans)
  }

  /** A chapter must be locatable by the track it came from, or the player cannot seek to it. */
  @Test
  fun `each chapter keeps a reference to its track`() {
    val chapters =
      listOf(
        track("11", 1, 1_000L),
        track("22", 2, 2_000L),
      ).asChapterList()

    assertEquals(listOf("11", "22"), chapters.map { it.trackId })
  }

  @Test
  fun `chapter titles come from their tracks`() {
    val chapters = listOf(track("1", 1, 1_000L), track("2", 2, 2_000L)).asChapterList()

    assertEquals(listOf("Track 1", "Track 2"), chapters.map { it.title })
  }

  /**
   * The round trip that matters: a position inside the second file must resolve to the second
   * chapter. `getChapterAt` matches on trackId plus a timestamp inside the chapter's span, so a
   * chapter list with broken offsets silently resolves to no chapter at all.
   */
  @Test
  fun `a position inside a later track resolves to that track's chapter`() {
    val tracks =
      listOf(
        track("1", 1, 1_000L),
        track("2", 2, 2_000L),
        track("3", 3, 3_000L),
      )
    val chapters = tracks.asChapterList()

    val found = chapters.getChapterAt("2", 1_500L)

    assertEquals("2", found.trackId)
    assertEquals("Track 2", found.title)
  }

  @Test
  fun `an empty track list yields no chapters`() {
    assertEquals(emptyList<Chapter>(), emptyList<MediaItemTrack>().asChapterList())
  }

  @Test
  fun `a single track yields one chapter starting at zero`() {
    val chapters = listOf(track("1", 1, 5_000L)).asChapterList()

    assertEquals(1, chapters.size)
    assertEquals(0L, chapters[0].bookStartTimeOffset)
    assertEquals(5_000L, chapters[0].bookEndTimeOffset)
  }
}
