package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fallback inside the chapter-loading path, where *some* tracks may lack server chapter data.
 *
 * `BookRepository` and `ChapterRepository` both wrote this inline as
 * `listOf(track.asChapter(0L))` — a literal zero offset for every track. Chapter offsets are
 * absolute within the book, so in a multi-file book with no server chapters every chapter claimed
 * to start at 0, and `getChapterAt` then resolves the wrong chapter or none at all.
 */
class AssembleChaptersTest {
  private fun track(
    id: String,
    index: Int,
    duration: Long,
  ) = MediaItemTrack(
    id = id,
    parentKey = "1001",
    index = index,
    duration = duration,
    title = "Track $index",
  )

  private val tracks =
    listOf(
      track("2001", 1, 1_000L),
      track("2002", 2, 2_000L),
      track("2003", 3, 3_000L),
    )

  /** The regression: with no server chapters anywhere, offsets must still accumulate. */
  @Test
  fun `fallback chapters get cumulative offsets, not zero`() {
    val chapters = assembleChapters(tracks) { emptyList() }

    assertEquals(listOf(0L, 1_000L, 3_000L), chapters.map { it.bookStartTimeOffset.millis })
  }

  @Test
  fun `a fallback chapter is produced for every track with no server data`() {
    assertEquals(3, assembleChapters(tracks) { emptyList() }.size)
  }

  @Test
  fun `server chapters are used when present`() {
    val chapters =
      assembleChapters(tracks) { track ->
        listOf(
          Chapter(
            title = "Server ${track.index}",
            id = "4${track.index}",
            index = track.index.toLong(),
            trackId = track.id,
            bookId = "1001",
            bookStartTimeOffset = BookOffset(0L),
            bookEndTimeOffset = BookOffset(track.duration),
          ),
        )
      }

    assertEquals(3, chapters.size)
    assertEquals(listOf("Server 1", "Server 2", "Server 3"), chapters.map { it.title })
  }

  /**
   * The mixed case, which is what makes the running offset non-trivial: a track the server
   * answered for must still advance the offset used by a later track that it did not.
   */
  @Test
  fun `a track without server chapters is offset past the tracks before it`() {
    val chapters =
      assembleChapters(tracks) { track ->
        if (track.index == 1) {
          listOf(
            Chapter(
              title = "Server 1",
              id = "41",
              index = 1L,
              trackId = track.id,
              bookId = "1001",
              bookStartTimeOffset = BookOffset(0L),
              bookEndTimeOffset = BookOffset(1_000L),
            ),
          )
        } else {
          emptyList()
        }
      }

    val fallbacks = chapters.filter { it.title.startsWith("Track") }
    assertEquals(
      "a fallback must start after the tracks preceding it, server-answered or not",
      listOf(1_000L, 3_000L),
      fallbacks.map { it.bookStartTimeOffset.millis },
    )
  }

  @Test
  fun `fallback chapters carry their book and track`() {
    val chapters = assembleChapters(tracks) { emptyList() }

    assertEquals(listOf("1001", "1001", "1001"), chapters.map { it.bookId })
    assertEquals(listOf("2001", "2002", "2003"), chapters.map { it.trackId })
  }

  @Test
  fun `no tracks yields no chapters`() {
    assertEquals(emptyList<Chapter>(), assembleChapters(emptyList()) { emptyList() })
  }

  /**
   * A chapter spanning a track boundary is reported by **both** tracks (cu-18), and concatenating
   * the per-track lists therefore listed it twice.
   *
   * On the fixture book, chapter 4003 arrives from track 2001 *and* from track 2002. The list read
   * "Chapter 3: A Short Rest" twice and the header counted ten chapters for an eight-chapter book,
   * because `Ch n of m` is a size. It went unseen until cu-201: the old adapter was rendering the
   * empty legacy `Audiobook.chapters` column, so no duplicate could reach it.
   *
   * The chapter belongs to the track it **starts** in — which is the rule the `MultiTrackBook`
   * fixture already encodes for `trackId`, and the frame `bookStartTimeOffset` is expressed in.
   */
  @Test
  fun `a chapter reported by two tracks is listed once`() {
    val spanning =
      Chapter(
        title = "Spans the boundary",
        id = "4003",
        index = 3L,
        bookId = "1001",
        bookStartTimeOffset = BookOffset(500L),
        bookEndTimeOffset = BookOffset(1_500L),
      )

    val chapters =
      assembleChapters(tracks) { track ->
        when (track.id) {
          // Both tracks report it, each claiming it as their own — exactly what Plex returns.
          "2001", "2002" -> listOf(spanning.copy(trackId = track.id))
          else -> emptyList()
        }
      }

    assertEquals(listOf("4003", "2003"), chapters.map { it.id })
  }

  /** The surviving copy is the one on the track the chapter starts in, so seeking lands right. */
  @Test
  fun `the surviving copy belongs to the track the chapter starts in`() {
    val spanning =
      Chapter(
        title = "Spans the boundary",
        id = "4003",
        index = 3L,
        bookId = "1001",
        bookStartTimeOffset = BookOffset(500L),
        bookEndTimeOffset = BookOffset(1_500L),
      )

    val chapters =
      assembleChapters(tracks) { track ->
        if (track.id == "2001" || track.id == "2002") {
          listOf(spanning.copy(trackId = track.id))
        } else {
          emptyList()
        }
      }

    assertEquals("2001", chapters.first { it.id == "4003" }.trackId)
  }

  /**
   * Two genuinely different chapters that happen to share an id must both survive.
   *
   * `Chapter.id` is not unique across a book — the per-track fallback uses the *track* id, and
   * Plex hands chapter and track ratingKeys from one sequence (cu-49). De-duplicating on id alone
   * would silently drop a real chapter.
   */
  @Test
  fun `distinct chapters sharing an id both survive`() {
    val chapters =
      assembleChapters(tracks) { track ->
        listOf(
          Chapter(
            title = "Chapter on ${track.id}",
            id = "collision",
            index = track.index.toLong(),
            trackId = track.id,
            bookId = "1001",
            bookStartTimeOffset = BookOffset(track.index * 1_000L),
            bookEndTimeOffset = BookOffset(track.index * 1_000L + 500L),
          ),
        )
      }

    assertEquals(3, chapters.size)
  }
}
