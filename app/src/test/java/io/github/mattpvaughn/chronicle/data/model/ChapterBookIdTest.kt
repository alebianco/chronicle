package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexChapter
import io.github.mattpvaughn.chronicle.data.sources.plex.model.toChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Every chapter must know which book it belongs to.
 *
 * While chapters were serialized inside `Audiobook.chapters` the containing book was implicit, so
 * neither construction path set [Chapter.bookId] and every chapter carried
 * [NO_AUDIOBOOK_FOUND_ID]. Moving chapters into a shared table (cu-49) makes that fatal: `bookId`
 * is part of the composite primary key, so unset means every chapter in the library collides on
 * the same key and `insertAll` keeps only the last one written.
 */
class ChapterBookIdTest {
  private val plexChapter =
    PlexChapter(
      id = 4001L,
      tag = "Chapter 1",
      index = 1L,
      startTimeOffset = 0L,
      endTimeOffset = 60_000L,
    )

  @Test
  fun `a chapter from Plex carries the book it belongs to`() {
    val chapter =
      plexChapter.toChapter(
        trackId = "2001",
        trackDiscNumber = 1,
        downloaded = false,
        bookId = "1001",
      )

    assertEquals("1001", chapter.bookId)
  }

  @Test
  fun `a chapter from Plex does not silently default its book`() {
    val chapter =
      plexChapter.toChapter(
        trackId = "2001",
        trackDiscNumber = 1,
        downloaded = false,
        bookId = "1001",
      )

    assertNotEquals(
      "an unset bookId collides with every other chapter in the table",
      NO_AUDIOBOOK_FOUND_ID,
      chapter.bookId,
    )
  }

  /**
   * The per-track fallback, used when a book has no embedded chapter data. `parentKey` is the
   * track's book, so it is available without threading an extra parameter through.
   */
  @Test
  fun `a fallback chapter takes its book from the track's parent`() {
    val track = MediaItemTrack(id = "2001", parentKey = "1001", title = "Track 1", duration = 1_000L)

    assertEquals("1001", track.asChapter(0L).bookId)
  }

  @Test
  fun `every chapter in a fallback list carries the same book`() {
    val tracks =
      listOf(
        MediaItemTrack(id = "2001", parentKey = "1001", duration = 1_000L),
        MediaItemTrack(id = "2002", parentKey = "1001", duration = 2_000L),
        MediaItemTrack(id = "2003", parentKey = "1001", duration = 3_000L),
      )

    assertEquals(listOf("1001", "1001", "1001"), tracks.asChapterList().map { it.bookId })
  }

  /**
   * The distinguishing property the composite key relies on: two books' chapters must differ in
   * the key even when the server hands out the same chapter id. Plex assigns chapter and track
   * ratingKeys from one server-wide sequence, so identical ids across books are possible.
   */
  @Test
  fun `chapters from different books are distinguishable despite a shared id`() {
    val fromBookA =
      plexChapter.toChapter(trackId = "2001", trackDiscNumber = 1, downloaded = false, bookId = "1001")
    val fromBookB =
      plexChapter.toChapter(trackId = "2001", trackDiscNumber = 1, downloaded = false, bookId = "1002")

    assertEquals("the server id is legitimately the same", fromBookA.id, fromBookB.id)
    assertNotEquals("but the chapters are not the same row", fromBookA, fromBookB)
  }
}
